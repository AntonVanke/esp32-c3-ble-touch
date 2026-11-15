# ESP32-C3 BLE Touch Lab

ESP32-C3 蓝牙 HID 触摸屏实验：在电脑浏览器中烧录固件，通过 USB 串口发送指令，由 C3 通过 BLE HID 向 Android 手机发送触摸报告。

- **在线烧录与控制台：<https://AntonVanke.github.io/esp32-c3-ble-touch/>**
- **手机触摸测试靶页：<https://AntonVanke.github.io/esp32-c3-ble-touch/target.html>**
- **[AssistStructure App v0.3.0 APK](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.3.0/assist-structure-lab-0.3.0.apk)**：实时画面与页面结构共享，新增悬浮控制窗、前台服务通知与快速采集，见 [手机说明](android-assist/README.md)。
- **[Windows 桌面监看 EXE](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.3.0/Assist-LAN-Monitor-0.3.0-windows-x64.exe)**：同时显示手机画面、结构树和节点属性，见 [桌面说明](desktop-monitor/README.md)。
- 目标：Android 14+，单台手机验证；不需要 ADB、USB 调试、Root 或无障碍服务。
- HID 触摸无需安装 App；画面与页面结构读取使用可选的测试 App，尚未集成多设备群控。
- 设备类型是 HID Digitizers / Touch Screen，无键盘、鼠标或触摸板集合。
- 实验性固件：编译、网页与产物检查不等同于实机验证，具体机型的 HID 分类、映射和多点兼容性需测试。

## 硬件与使用

需要 4 MB Flash 的 ESP32-C3 开发板、USB 数据线、电脑 Chrome/Edge 和支持 BLE 的 Android 手机。

1. C3 用 USB 接电脑。打开在线烧录页，选择 **原生 USB**（SuperMini / USB Serial-JTAG）或 **USB 转串口**（CH340 / CP210x / DevKitM），点击“连接并烧录”。安装会替换板内固件，可选择擦除数据。
2. 原生 USB 板烧录完成后按一下 RESET。关闭烧录弹窗，点击“连接控制台”，选择板子的串口。烧录器和控制台不能同时占用一个串口；必要时拔插 USB。
3. 手机系统蓝牙设置中搜索并配对 `C3-Touch-xxxx`。电脑控制台显示“已就绪”表示已连接、完成加密并订阅 HID 输入报告。
4. 手机浏览器打开测试靶页。手离开手机，从电脑发送中心点击、上下滑动或双指手势，观察输入类型和触点轨迹。
5. `touch` 表示浏览器收到触摸，`mouse` 表示鼠标；蓝牙“已就绪”不能单独证明 Android 已识别触摸屏。浏览器靶页不读取 Android 原生 SOURCE_TOUCHSCREEN，也不能证明来自内置触摸传感器。

蓝牙配对异常或改过 HID 描述符后，在控制台“清除板端配对”，并在手机中忘记该设备，再重新配对。

没有串口时，按住 BOOT，轻按 RESET，再松开 BOOT 进入下载模式。烧录完成后再按 RESET。无 USB 数据连接时无法使用网页控制台。

## 串口协议

115200 baud，ASCII，每条命令以 `\n` 结尾。坐标 **0–1000** 映射为 HID 绝对坐标 0–32767；触点 ID 为 0 或 1。

| 命令 | 行为 |
|---|---|
| `status` | 返回名称、固件版本、连接/加密/订阅状态、已发送报告数 |
| `tap 500 500` | 中心点击，按住约 100 ms |
| `swipe 500 800 500 200 650` | 从 (500,800) 滑到 (500,200)，持续 650 ms；允许 100–5000 ms |
| `pinch out` / `pinch in` | 水平双指展开 / 捏合，持续 800 ms |
| `down 0 200 500` | 触点 0 按下 |
| `move 0 600 500` | 移动已按下触点 |
| `up 0` | 抬起触点 0 |
| `release` | 取消当前手势并释放全部触点 |
| `unpair` | 断开手机并清除板端蓝牙绑定 |

响应为逐行 JSON。`ok` 表示命令接受，`gesture_done` 表示预设手势完成，`error` 提供失败原因。`reports` 仅表示固件发出了通知，不是手机应用的接收确认。

手动触点 2 秒未收到更新会自动释放；网页按住控制区时每 500 ms 续发坐标。预设手势由 C3 本地按约 20 ms 间隔执行，不依赖浏览器逐帧调度。未就绪时拒绝触摸指令；上电不会自动点击。

## HID 格式

`include/touch_report.h` 定义两点并行报告，Usage Page `0x0D` / Usage `0x04`，Input Report ID 1：每点 6 字节（Tip Switch + 7 位 padding、Contact ID、16 位 X、16 位 Y），最后 1 字节 Contact Count，共 13 字节；Feature Report ID 2 返回最大触点数 2。

BLE GATT 的 Report Reference Descriptor 已携带 Report ID，通知载荷不重复添加 ID。两个槽位固定全部上报，包括 Tip Switch=0 的抬起状态，Contact Count=2 表示本帧两个槽位。PnP 标识为本地原型值，不冒充正式认证产品。没有单独的鼠标集合。

Android 的直接触摸分类最终取决于内核 HID 驱动、输入属性和显示映射；若出现鼠标光标或坐标不匹配，需要记录机型、系统版本与现象，不能仅以配对成功宣称兼容。参考 [Android Touch devices](https://source.android.com/docs/core/interaction/input/touch-devices)。

## 本地构建

```sh
python -m pip install platformio==6.1.19
pio run
python scripts/check_release.py
```

依赖固定为 PlatformIO Espressif32 6.13.0 / Arduino-ESP32 2.0.17，使用框架自带 BLE 库。

两个构建环境：`c3_native` 为 USB Serial/JTAG 控制台，`c3_uart` 为 UART0 控制台。合并镜像自动写入 `docs/firmware/`，包含 C3 bootloader（0x0000）、分区表（0x8000）、OTA 初始化（0xe000）、应用（0x10000），以地址 0 烧录。

```sh
pio run -e c3_native -t upload --upload-port COM7
```

将 COM7 替换成实际端口；UART 桥接板改用 `c3_uart`。合并镜像设置为 DIO / 40 MHz / 4 MB。

## GitHub Pages

仓库 Settings → Pages → Source 选择 **GitHub Actions**。推送 `main` 后，工作流编译两种固件、检查合并镜像和 manifest，再部署 `docs/`。构建产物同时保留在 Actions 的 `esp32-c3-ble-touch-firmware` artifact 中；`firmware/build.json` 提供来源 commit 和 SHA-256。

网页烧录使用 [ESP Web Tools](https://esphome.github.io/esp-web-tools/) 10.1.1，要求 HTTPS 或 localhost。网页不上传串口指令或设备日志；烧录组件从 unpkg 加载。

## 实测记录

当前版本尚未在实体 C3 和 Android 手机之间验证 BLE 配对、触摸分类、坐标映射及双指操作。请将验证结果记录为“开发板型号 / USB 类型 / 手机型号 / Android 版本 / touch 或 mouse / 单点 / 双指 / 方向映射”。
