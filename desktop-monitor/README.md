# Assist LAN Monitor 0.2.0

Windows 10/11 x64 桌面监看程序，接收手机广播的 AssistStructure 页面结构，显示设备、控件树、文本、资源 ID、节点属性及位置轮廓。此版不包含截图或视频。

**[下载 Windows EXE](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.2.0/Assist-LAN-Monitor-0.2.0-windows-x64.exe)** · **[下载手机 APK](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.2.0/assist-structure-lab-0.2.0.apk)**

## 使用

1. 手机和电脑连接同一 IPv4 局域网；电脑运行 EXE，无需安装 Python。
2. 如 Windows 防火墙询问，允许监看程序访问专用网络。程序监听 UDP 38583，不会自动修改防火墙。
3. 手机 App 设为默认数字助理，确认服务已启用，点击 **开始连续采集并广播**。
4. 手机 App 退到后台，3 秒后开始；每次完成后默认等待 1 秒，可选择 2 或 5 秒。桌面自动发现设备并更新结构。
5. 点击树节点或左侧轮廓查看属性；**暂停刷新** 冻结桌面显示，手机仍继续采集。**导出当前 JSON** 保存当前显示帧。
6. 回手机 App 点击 **停止连续采集与广播**。锁屏/熄屏期间暂停读取并发送状态，解锁后继续；服务结束或重启 App 进程后不会自动开始广播。

轮廓绘制第一个 Activity 的第一个窗口，最多绘制 800 个可见节点，其余完整节点仍可从结构树展开查看。位置是结构中的估计边界，不保证实际可点击区域。

若没有设备出现：检查手机是否显示“已发出”或广播错误、电脑是否放行 UDP 38583、是否在同一网段，以及路由器是否启用了访客网络/AP 客户端隔离。UDP 不保证送达；手机“已发出”不是电脑接收确认。

广播未加密，同网段设备可接收页面文字；仅在手机上主动开始后发送。软件不会把数据上传到互联网，也不向手机发送控制指令。Android/HyperOS 仍可能显示系统识屏提示。

## 源码运行与构建

```powershell
python desktop-monitor/monitor.py
python -m unittest discover -s desktop-monitor/tests -v
cd desktop-monitor
python -m pip install pyinstaller==6.22.2
python -m PyInstaller --noconfirm --onefile --windowed --name Assist-LAN-Monitor monitor.py
```

运行使用 Python 标准库；测试需要 JDK 17，直接调用 Android 项目的 Java 分包编码器，验证跨语言解析、乱序/重复、CRC、超时、会话切换和 UDP 回环。构建的 EXE 位于 `dist/`。

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
