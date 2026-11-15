"""Exercise the Android Java encoder against Python reassembly and a real UDP socket."""
import json
import pathlib
import random
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'desktop-monitor'))
from protocol import Reassembler, Receiver


def sample_frame(sequence=1, run='00000000-0000-0000-0000-000000000002'):
    labels = ['页面结构联调', '网络与连接', '显示与亮度', '声音与振动', '应用管理', '存储空间', '关于设备', '返回']
    children = [{'className': 'android.widget.TextView', 'text': text, 'resourceId': f'lab:id/item_{i}',
                 'boundsOnScreenEstimate': [60, 120 + i * 245, 1020, 300 + i * 245],
                 'clickable': True, 'visibility': 0, 'children': []} for i, text in enumerate(labels)]
    rng = random.Random(7)
    return {'schemaVersion': 1, 'deviceId': '00000000-0000-0000-0000-000000000001', 'runId': run,
            'sequence': sequence, 'capturedAt': '2026-11-16T00:00:00Z', 'model': '协议联调模拟设备',
            'manufacturer': 'Host test', 'intervalMs': 1000, 'captureDurationMs': 73, 'status': 'received_callbacks',
            'testPayload': ''.join(rng.choices('0123456789abcdef', k=24000)),
            'activities': [{'activity': 'lab.test/.Settings', 'status': 'structure_received', 'nodeCount': 9,
                            'windows': [{'bounds': [0, 0, 1080, 2400], 'title': '协议联调示例',
                                         'root': {'className': 'android.widget.LinearLayout', 'children': children,
                                                  'boundsOnScreenEstimate': [0, 0, 1080, 2400]}}]}]}


def read_packets(path):
    data = pathlib.Path(path).read_bytes()
    packets = []
    while data:
        length, = struct.unpack_from('!I', data)
        packets.append(data[4:4 + length])
        data = data[4 + length:]
    return packets


class WireTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='assist-wire-')
        cls.folder = pathlib.Path(cls.temp.name)
        source = ROOT / 'android-assist/app/src/main/java/io/github/antonvanke/assistlab/WirePackets.java'
        helper = pathlib.Path(__file__).with_name('WireFixture.java')
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(cls.folder), str(source), str(helper)], check=True)
        cls.data = sample_frame()
        cls.packets = cls.encode(cls.data)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @classmethod
    def encode(cls, data):
        source, target = cls.folder / 'frame.json', cls.folder / 'packets.bin'
        source.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        subprocess.run(['java', '-cp', str(cls.folder), 'WireFixture', data['deviceId'], data['runId'],
                        str(data['sequence']), str(source), str(target)], check=True)
        return read_packets(target)

    def test_java_encoder_reordered_duplicates(self):
        self.assertGreater(len(self.packets), 5)
        assembler = Reassembler()
        result = None
        for packet in list(reversed(self.packets)) + self.packets:
            result = assembler.feed(packet, '127.0.0.1') or result
        self.assertEqual(result['data'], self.data)

    def test_crc_and_incomplete_frames(self):
        assembler = Reassembler()
        damaged = list(self.packets)
        damaged[-1] = damaged[-1][:-1] + bytes([damaged[-1][-1] ^ 1])
        self.assertTrue(all(assembler.feed(p, '127.0.0.1') is None for p in damaged))
        self.assertEqual(assembler.invalid, 1)
        assembler.feed(self.packets[0], '127.0.0.1', now=10)
        assembler.expire(16)
        self.assertEqual(assembler.dropped, 1)

    def test_sequence_and_run_restart(self):
        assembler = Reassembler()
        newer = self.encode(sample_frame(2))
        for packet in newer:
            result = assembler.feed(packet, '127.0.0.1')
        self.assertEqual(result['data']['sequence'], 2)
        self.assertTrue(all(assembler.feed(p, '127.0.0.1') is None for p in self.packets))
        restarted = self.encode(sample_frame(1, str(uuid.uuid4())))
        for packet in restarted:
            result = assembler.feed(packet, '127.0.0.1')
        self.assertEqual(result['data']['sequence'], 1)
        self.assertTrue(all(assembler.feed(p, '127.0.0.1') is None for p in newer))

    def test_udp_receiver(self):
        receiver = Receiver(port=0)
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
                port = receiver.socket.getsockname()[1]
                for packet in self.packets:
                    sender.sendto(packet, ('127.0.0.1', port))
            deadline = time.monotonic() + 3
            frames = []
            while time.monotonic() < deadline and not frames:
                frames = receiver.take_updates()
                time.sleep(.02)
            self.assertEqual(frames[0]['data'], self.data)
        finally:
            receiver.close()


if __name__ == '__main__':
    unittest.main()
