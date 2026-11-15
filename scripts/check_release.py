"""Check the actual web-flash image headers, addresses, and manifest links."""
import hashlib
import json
import os
import struct
from pathlib import Path

root = Path(__file__).resolve().parents[1]
metadata = {"commit": os.getenv("GITHUB_SHA", "local"), "images": {}}
for kind in ("native", "uart"):
    manifest = json.loads((root / f"docs/firmware/manifest-{kind}.json").read_text())
    build = manifest["builds"][0]
    assert build["chipFamily"] == "ESP32-C3"
    assert build["parts"][0]["offset"] == 0
    path = root / "docs/firmware" / build["parts"][0]["path"]
    data = path.read_bytes()
    assert 0x10000 < len(data) <= 0x150000, (path, "image does not fit default app slot")
    for offset in (0, 0x10000):
        assert data[offset] == 0xE9, (path, "missing ESP image", offset)
        assert struct.unpack_from("<H", data, offset + 12)[0] == 5, (path, "wrong chip ID")
    assert data[2] == 2, "bootloader must use DIO flash"
    assert data[3] == 0x20, "bootloader must use 4 MB / 40 MHz flash"
    assert data[0x8000:0x8002] == b"\xaa\x50", "missing partition table"
    metadata["images"][path.name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    print(f"OK {path.name}: C3 / DIO / 4 MB / 40 MHz / {len(data)} bytes")
(root / "docs/firmware/build.json").write_text(json.dumps(metadata, indent=2) + "\n")
