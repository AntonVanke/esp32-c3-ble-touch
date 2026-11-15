Android 后台连续采集 + Windows 局域网结构监看。

- 手机 APK：默认数字助理连续读取 AssistStructure，每次完成后等待 1 / 2 / 5 秒，通过 UDP 38583 广播压缩分片。
- 桌面 EXE：Windows 10/11 x64，自动发现设备、实时控件树、文字、资源 ID、位置轮廓、节点详情、暂停刷新及 JSON 导出；无需安装 Python。
- 手机与电脑连接同一 IPv4 局域网，电脑启动 EXE，手机点击“开始连续采集并广播”；回手机 App 可停止。
- APK 与 v0.1.1 签名一致，可覆盖安装。不使用无障碍、ADB、Root 或录屏。系统识屏提示仍可能出现。
- 广播未加密，同网段设备可收到页面文字；程序不向互联网上传采集内容。

验证：APK 编译、元数据根标签、签名通过；Windows EXE 使用模拟的本机 UDP 数据验证实时刷新和节点详情；4 项协议测试通过（Java/Python 分片、乱序/重复、CRC/不完整帧、会话排序和 UDP 回环）。小米 / HyperOS 的持续读取与实际 Wi-Fi 广播仍待实机测试。

源码构建提交：`cfa3c04`。

- [手机使用说明](https://github.com/AntonVanke/esp32-c3-ble-touch/tree/main/android-assist)
- [桌面使用说明](https://github.com/AntonVanke/esp32-c3-ble-touch/tree/main/desktop-monitor)
