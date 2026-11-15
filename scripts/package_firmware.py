Import("env")
from pathlib import Path
import subprocess

def package(source, target, env):
    root = Path(env.subst("$PROJECT_DIR"))
    build = Path(env.subst("$BUILD_DIR"))
    name = env.subst("$PIOENV")
    platform = env.PioPlatform()
    esptool = Path(platform.get_package_dir("tool-esptoolpy")) / "esptool.py"
    framework = Path(platform.get_package_dir("framework-arduinoespressif32"))
    output = root / "docs" / "firmware" / (name + ".bin")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        env.subst("$PYTHONEXE"), str(esptool), "--chip", "esp32c3", "merge_bin",
        "--output", str(output), "--flash_mode", "dio", "--flash_freq", "40m",
        "--flash_size", "4MB", "0x0000", str(build / "bootloader.bin"),
        "0x8000", str(build / "partitions.bin"),
        "0xe000", str(framework / "tools" / "partitions" / "boot_app0.bin"),
        "0x10000", str(build / "firmware.bin"),
    ], check=True)

env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", package)
