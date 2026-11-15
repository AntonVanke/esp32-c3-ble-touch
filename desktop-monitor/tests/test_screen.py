"""Actual Android Java screen encoder/server to desktop decoder integration."""
import pathlib
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'desktop-monitor'))
from screen import ScreenReceiver, decode_frame, MAX_FRAME

DEVICE = '00000000-0000-0000-0000-000000000001'
SESSION = '00000000-0000-0000-0000-000000000003'


class ScreenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='assist-screen-')
        cls.folder = pathlib.Path(cls.temp.name)
        source = ROOT / 'android-assist/app/src/main/java/io/github/antonvanke/assistlab'
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(cls.folder), str(source / 'ScreenWire.java'),
                        str(source / 'ScreenServer.java'), str(pathlib.Path(__file__).with_name('ScreenFixture.java'))], check=True)
        cls.jpeg = cls.folder / 'screen.jpg'
        Image.new('RGB', (360, 800), (20, 100, 220)).save(cls.jpeg)
        target = cls.folder / 'frame.bin'
        cls.java = ['java', '-cp', str(cls.folder), 'io.github.antonvanke.assistlab.ScreenFixture']
        subprocess.run(cls.java + ['encode', str(cls.jpeg), str(target)], check=True)
        cls.wire = target.read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_java_frame_and_validation(self):
        length, = struct.unpack_from('!I', self.wire)
        self.assertEqual(length, len(self.wire) - 4)
        frame = decode_frame(self.wire[4:], DEVICE, SESSION)
        self.assertEqual((frame['width'], frame['height'], frame['sequence']), (1080, 2400, 7))
        self.assertEqual(frame['timestamp'], 1700000000123)
        self.assertEqual(frame['image'].size, (360, 800))
        for payload, device in [(self.wire[4:], SESSION), (b'x' * (MAX_FRAME + 1), DEVICE), (self.wire[4:65], DEVICE)]:
            with self.assertRaises(ValueError):
                decode_frame(payload, device, SESSION)
        invalid = bytearray(self.wire[4:])
        struct.pack_into('!I', invalid, 60, 5000)
        with self.assertRaises(ValueError):
            decode_frame(invalid, DEVICE, SESSION)

    def test_fragmented_tcp_read(self):
        receiver = ScreenReceiver()
        left, right = socket.socketpair()
        right.settimeout(.5)
        def send():
            with left:
                for start in range(0, len(self.wire), 173):
                    left.sendall(self.wire[start:start + 173])
        worker = threading.Thread(target=send)
        worker.start()
        try:
            header = receiver._read(right, 4, None)
            length, = struct.unpack('!I', header)
            self.assertEqual(receiver._read(right, length, None, payload=True), self.wire[4:])
        finally:
            right.close()
            worker.join()
            receiver.close()

    def test_java_server_receive_and_reconnect(self):
        process = subprocess.Popen(self.java + ['serve', str(self.jpeg)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        receiver = ScreenReceiver()
        target = {'device': DEVICE, 'ip': '127.0.0.1', 'data': {'screen': {'protocol': 'ASV1', 'port': 38584, 'sessionId': SESSION}}}
        def wait_frame():
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                frame, _, _ = receiver.snapshot()
                if frame:
                    return frame
                time.sleep(.02)
            self.fail(receiver.snapshot()[1])
        try:
            self.assertEqual(process.stdout.readline().strip(), 'READY')
            receiver.select(target)
            first = wait_frame()
            receiver.select(None)
            self.assertIsNone(receiver.snapshot()[0])
            time.sleep(.15)
            receiver.select(target)
            second = wait_frame()
            self.assertGreater(second['sequence'], first['sequence'])
            self.assertEqual(second['image'].size, (360, 800))
        finally:
            receiver.close()
            process.terminate()
            process.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
