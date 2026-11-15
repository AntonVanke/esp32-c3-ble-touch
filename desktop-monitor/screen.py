"""Bounded, latest-frame-only receiver for the phone's ASV1 JPEG stream."""
import collections
import io
import socket
import struct
import threading
import time
import uuid

from PIL import Image

HEADER = struct.Struct('!4s16s16sQQIIII')
MAX_FRAME = 2 * 1024 * 1024


def decode_frame(payload, device, session):
    if not HEADER.size < len(payload) <= MAX_FRAME:
        raise ValueError('画面长度无效')
    magic, sender, run, sequence, timestamp, sw, sh, width, height = HEADER.unpack_from(payload)
    if magic != b'ASV1' or sender != uuid.UUID(device).bytes or run != uuid.UUID(session).bytes:
        raise ValueError('画面设备或会话不匹配')
    if not (0 < sw <= 16000 and 0 < sh <= 16000 and 0 < width <= 2048 and 0 < height <= 2048):
        raise ValueError('画面尺寸无效')
    with Image.open(io.BytesIO(payload[HEADER.size:])) as image:
        if image.format != 'JPEG' or image.size != (width, height):
            raise ValueError('JPEG 尺寸与帧头不匹配')
        bitmap = image.convert('RGB')
    return {'device': device, 'session': session, 'sequence': sequence, 'timestamp': timestamp,
            'width': sw, 'height': sh, 'image': bitmap, 'received': time.monotonic()}


class ScreenReceiver:
    def __init__(self):
        self.lock = threading.Lock()
        self.target = None
        self.latest = None
        self.socket = None
        self.status = '等待手机开启画面共享'
        self.fps = 0
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def select(self, frame):
        target = None
        if frame and frame['data'].get('status') not in ('stream_stopped', 'screen_locked'):
            screen = frame['data'].get('screen')
            if isinstance(screen, dict) and screen.get('protocol') == 'ASV1' and screen.get('port') == 38584:
                try:
                    target = (frame['ip'], 38584, str(uuid.UUID(frame['device'])), str(uuid.UUID(screen['sessionId'])))
                except (ValueError, KeyError, TypeError, AttributeError):
                    pass
        with self.lock:
            if target == self.target:
                return
            self.target, self.latest, self.fps = target, None, 0
            self.status = '正在连接手机画面…' if target else '未开启画面共享 / 手机已停止或锁屏'
            if self.socket:
                try:
                    self.socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.socket.close()

    def snapshot(self):
        with self.lock:
            return self.latest, self.status, self.fps

    def _read(self, sock, count, target, payload=False):
        data = bytearray()
        started = time.monotonic()
        while len(data) < count:
            if self.stopped.is_set() or target != self.target:
                raise OSError('连接已切换')
            if (payload or data) and time.monotonic() - started > 5:
                raise OSError('画面传输超时')
            try:
                chunk = sock.recv(count - len(data))
            except socket.timeout:
                continue
            if not chunk:
                raise OSError('画面连接已关闭')
            if not data:
                started = time.monotonic()
            data.extend(chunk)
        return bytes(data)

    def _run(self):
        while not self.stopped.is_set():
            with self.lock:
                target = self.target
            if target is None:
                self.stopped.wait(.1)
                continue
            try:
                with socket.create_connection(target[:2], timeout=2) as sock:
                    sock.settimeout(.5)
                    with self.lock:
                        if target != self.target:
                            continue
                        self.socket = sock
                        self.status = '画面已连接'
                    arrivals = collections.deque(maxlen=60)
                    last_sequence = 0
                    while not self.stopped.is_set() and target == self.target:
                        length, = struct.unpack('!I', self._read(sock, 4, target))
                        if not HEADER.size < length <= MAX_FRAME:
                            raise ValueError('画面长度超出上限')
                        frame = decode_frame(self._read(sock, length, target, payload=True), target[2], target[3])
                        if frame['sequence'] <= last_sequence:
                            continue
                        last_sequence = frame['sequence']
                        arrivals.append(frame['received'])
                        while len(arrivals) > 2 and arrivals[-1] - arrivals[0] > 2:
                            arrivals.popleft()
                        with self.lock:
                            if target == self.target:
                                self.latest = frame
                                self.fps = (len(arrivals) - 1) / max(.001, arrivals[-1] - arrivals[0]) if len(arrivals) > 1 else 0
                                self.status = '画面已连接'
            except (OSError, ValueError, Image.DecompressionBombError) as exc:
                with self.lock:
                    if target == self.target:
                        self.status = '画面重连中：' + str(exc)
                        self.fps = 0
                self.stopped.wait(.5)
            finally:
                with self.lock:
                    self.socket = None

    def close(self):
        self.stopped.set()
        self.select(None)
        self.thread.join(timeout=2.5)
