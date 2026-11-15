"""ASL1 UDP framing: gzip JSON, bounded reassembly, CRC32, session/sequence ordering."""
import collections
import json
import socket
import struct
import threading
import time
import uuid
import zlib

PORT = 38583
HEADER = struct.Struct('!4s16s16sQHHII')
PAYLOAD = 1144
MAX_COMPRESSED = 2 * 1024 * 1024
MAX_RAW = 16 * 1024 * 1024
MAX_PENDING = 16
MAX_BUFFERED = 8 * 1024 * 1024
TTL = 5.0


class Reassembler:
    def __init__(self):
        self.pending = collections.OrderedDict()
        self.current = collections.OrderedDict()
        self.retired = collections.deque(maxlen=128)
        self.dropped = 0
        self.invalid = 0

    def expire(self, now=None):
        now = time.monotonic() if now is None else now
        for key, frame in list(self.pending.items()):
            if now - frame['created'] > TTL:
                del self.pending[key]
                self.dropped += 1

    def feed(self, packet, ip, now=None):
        now = time.monotonic() if now is None else now
        self.expire(now)
        try:
            return self._feed(packet, ip, now)
        except (ValueError, TypeError, KeyError, struct.error, zlib.error, UnicodeError, RecursionError):
            self.invalid += 1
            return None

    def _feed(self, packet, ip, now):
        if not HEADER.size < len(packet) <= HEADER.size + PAYLOAD:
            raise ValueError('packet length')
        magic, device, run, seq, index, count, size, crc = HEADER.unpack_from(packet)
        if magic != b'ASL1' or not 0 < size <= MAX_COMPRESSED or not seq:
            raise ValueError('header')
        if count != (size + PAYLOAD - 1) // PAYLOAD or index >= count:
            raise ValueError('fragment index/count')
        payload = packet[HEADER.size:]
        if len(payload) != min(PAYLOAD, size - index * PAYLOAD):
            raise ValueError('fragment size')
        if (device, run) in self.retired:
            return None
        previous = self.current.get(device)
        if previous and previous[0] == run and seq <= previous[1]:
            return None
        key = (device, run, seq, ip)
        if key not in self.pending:
            while len(self.pending) >= MAX_PENDING:
                self.pending.popitem(last=False)
                self.dropped += 1
            self.pending[key] = {'created': now, 'count': count, 'size': size, 'crc': crc, 'parts': {}}
        frame = self.pending[key]
        if (frame['count'], frame['size'], frame['crc']) != (count, size, crc):
            del self.pending[key]
            raise ValueError('inconsistent fragments')
        frame['parts'][index] = payload
        if sum(sum(len(p) for p in f['parts'].values()) for f in self.pending.values()) > MAX_BUFFERED:
            self.pending.popitem(last=False)
            self.dropped += 1
            return None
        if len(frame['parts']) != count:
            return None
        del self.pending[key]
        compressed = b''.join(frame['parts'][i] for i in range(count))
        if zlib.crc32(compressed) & 0xffffffff != crc:
            raise ValueError('CRC mismatch')
        inflater = zlib.decompressobj(31)
        raw = inflater.decompress(compressed, MAX_RAW + 1)
        if len(raw) > MAX_RAW or not inflater.eof or inflater.unused_data:
            raise ValueError('gzip size/stream')
        data = json.loads(raw.decode('utf-8'))
        device_id, run_id = str(uuid.UUID(bytes=device)), str(uuid.UUID(bytes=run))
        if not isinstance(data, dict) or data.get('schemaVersion') != 1:
            raise ValueError('schema')
        if data.get('deviceId') != device_id or data.get('runId') != run_id or data.get('sequence') != seq:
            raise ValueError('identity mismatch')
        activities = data.get('activities')
        if not isinstance(activities, list) or len(activities) > 64:
            raise ValueError('activities')
        pending_nodes = []
        for activity in activities:
            if not isinstance(activity, dict) or not isinstance(activity.get('windows', []), list):
                raise ValueError('activity')
            for window in activity.get('windows', []):
                if not isinstance(window, dict) or not isinstance(window.get('root'), dict):
                    raise ValueError('window')
                pending_nodes.append((window['root'], 0))
        nodes = 0
        while pending_nodes:
            node, depth = pending_nodes.pop()
            nodes += 1
            if nodes > 80000 or depth > 128 or not isinstance(node, dict):
                raise ValueError('tree limit/type')
            children = node.get('children', [])
            if not isinstance(children, list):
                raise ValueError('children')
            pending_nodes.extend((child, depth + 1) for child in children)
        if previous and previous[0] != run:
            self.retired.append((device, previous[0]))
        self.current[device] = (run, seq)
        self.current.move_to_end(device)
        while len(self.current) > 64:
            self.current.popitem(last=False)
        for older in list(self.pending):
            if older[0] == device and (older[1] != run or older[2] <= seq):
                del self.pending[older]
        return {'data': data, 'device': device_id, 'ip': ip, 'received': now,
                'compressedBytes': size, 'rawBytes': len(raw)}


class Receiver:
    def __init__(self, port=PORT):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)
        try:
            self.socket.bind(('0.0.0.0', port))
        except OSError:
            self.socket.close()
            raise
        self.socket.settimeout(0.25)
        self.reassembler = Reassembler()
        self.lock = threading.Lock()
        self.updates = collections.OrderedDict()
        self.stopped = threading.Event()
        self.error = ''
        self.frames = 0
        self.thread = threading.Thread(target=self._receive, daemon=True)
        self.thread.start()

    def _receive(self):
        while not self.stopped.is_set():
            try:
                packet, (ip, _) = self.socket.recvfrom(65535)
            except socket.timeout:
                self.reassembler.expire()
                continue
            except OSError as exc:
                if not self.stopped.is_set():
                    self.error = str(exc)
                break
            result = self.reassembler.feed(packet, ip)
            if result:
                self.frames += 1
                with self.lock:
                    self.updates[result['device']] = result
                    self.updates.move_to_end(result['device'])
                    while len(self.updates) > 32:
                        self.updates.popitem(last=False)

    def take_updates(self):
        with self.lock:
            updates = list(self.updates.values())
            self.updates.clear()
        return updates

    def close(self):
        self.stopped.set()
        self.socket.close()
        self.thread.join(timeout=1)
