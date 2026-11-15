# Assist LAN Monitor 0.3.0

Windows 10/11 x64 桌面监看程序，左侧显示手机实时画面，右侧显示 AssistStructure 控件树、文本、资源 ID 和节点属性。可以叠加节点位置、点击画面选中节点，并导出结构 JSON。

**[下载 Windows EXE](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.3.0/Assist-LAN-Monitor-0.3.0-windows-x64.exe)** · **[下载手机 APK](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.3.0/assist-structure-lab-0.3.0.apk)**

## 使用

1. 手机和电脑连接同一 IPv4 局域网；电脑运行 EXE，无需安装 Python。
2. 如 Windows 防火墙询问，允许监看程序访问专用网络。程序监听 UDP 38583，不会自动修改防火墙。
3. 手机 App 设为默认数字助理，允许通知和需要的悬浮窗权限，点击 **开始实时画面与结构共享**，在系统弹窗授权整个屏幕。
4. 桌面自动发现设备，并连接手机 TCP 38584 获取画面。默认结构目标间隔 200 ms、画面长边 960 / 目标 10 fps；手机可调整速度和清晰度。
5. 选择设备后只连接该手机的画面。点击树节点或画面查看属性；**叠加控件边界** 显示估计位置。**暂停刷新** 同时冻结画面和结构，手机仍继续采集；**导出当前 JSON** 保存当前显示的结构帧。
6. 手机通知、悬浮窗和 App 均有停止入口。锁屏/熄屏暂停读取；系统撤销录屏授权后须回 App 重新开始，不会静默恢复。

边界绘制第一个 Activity 的第一个窗口，最多绘制 800 个可见节点，其余完整节点仍可从结构树展开查看。位置是估计值；画面与结构独立采集，切换页面或旋转时可能短暂错位。没有画面时保留结构轮廓显示。结构未变化时不会重建树，画面刷新不重建节点。

画面栏显示实际接收 fps 和最新图像距接收的时间，顶栏显示结构距接收的时间。静止画面可能不产生新图像，fps 下降并不一定是断线；断线时单独显示重连状态。

若没有设备出现：检查手机是否显示“已发出”或广播错误、电脑是否放行 UDP 38583、是否在同一网段，以及路由器是否启用了访客网络/AP 客户端隔离。UDP 不保证送达；手机“已发出”不是电脑接收确认。

结构广播与画面传输未加密，同网段设备可接收；仅在手机主动开始并完成相应授权后共享。软件不上传到互联网，也不向手机发送控制指令。Android/HyperOS 仍可能显示系统识屏和屏幕共享提示。

## 源码运行与构建

```powershell
python -m pip install -r desktop-monitor/requirements.txt
python desktop-monitor/monitor.py
python -m unittest discover -s desktop-monitor/tests -v
cd desktop-monitor
python -m pip install pyinstaller==6.22.2
python -m PyInstaller --noconfirm --onefile --windowed --name Assist-LAN-Monitor monitor.py
```

运行使用 Python 标准库和 Pillow；测试需要 JDK 17，直接调用 Android Java 结构/画面编码器与 TCP 服务，验证跨语言解析、结构乱序/重复/CRC、会话切换、UDP 回环和画面连接/重连。构建的 EXE 位于 `dist/`。

## UDP 协议 ASL1

端口 38583，JSON UTF-8 → gzip → 分片。每个数据报最多 1200 字节，其中 56 字节头，1144 字节负载。头采用网络字节序，Python 格式 `!4s16s16sQHHII`：

| 字段 | 字节 |
|---|---:|
| 魔数 `ASL1` | 4 |
| 安装实例 UUID / 采集会话 UUID | 16 + 16 |
| 递增帧序号 | 8 |
| 分片索引（从 0 开始）/ 分片总数 | 2 + 2 |
| 压缩总长度 / 压缩数据 CRC32 | 4 + 4 |

JSON 保留原 AssistStructure 格式，增加 `deviceId`、`runId`、`sequence`、`intervalMs`、`captureDurationMs`。只有完整重组且校验通过的帧会刷新显示；过期、重复、倒序和旧会话帧被丢弃，不混合不同帧的节点。单帧上限为原始 JSON 16 MiB、压缩后 2 MiB。

`screen_locked`、`session_error`、`stream_stopped` 是无结构状态帧。结构为空与网络断联分别显示；设备列表和顶栏显示数据距接收的时间。

## TCP 画面协议 ASV1

手机 TCP 38584 提供长度前缀 JPEG 流；结构 JSON 的 `screen` 包含 `protocol`、`port`、`sessionId`、源尺寸和目标 fps。桌面只连接当前选中手机，自动重连。手机最多同时连接两台电脑。

每帧：4 字节网络字节序长度（不包含长度字段本身），随后 68 字节头和 JPEG。头格式 `!4s16s16sQQIIII`，依次为 `ASV1`、设备 UUID、画面会话 UUID、序号、Unix 毫秒时间戳、原始屏幕宽高、JPEG 宽高。帧总长上限 2 MiB；接收端核对设备、会话、格式与尺寸，后台解码，只向 UI 提交最新图像。发送方每个客户端只保留一帧待发，写入阻塞超过 1.5 秒会断开慢连接。
