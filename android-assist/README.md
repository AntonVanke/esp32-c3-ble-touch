# AssistStructure 测试 App

用于在小米 14 / Android 14+ 验证默认数字助理能否获得前台应用的界面结构。独立于 C3 固件；测试读取结构时不需要连接开发板。

**[下载 v0.3.0 APK](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.3.0/assist-structure-lab-0.3.0.apk)** · [发布页](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/tag/assist-v0.3.0) · [桌面监看程序](../desktop-monitor/README.md)

v0.3.0 新增 MediaProjection 实时画面、前台服务持续通知、可拖动悬浮控制窗，以及更快的结构采集。发布 APK 使用与旧版本相同的签名，可覆盖安装。

## 连续采集与局域网监看

1. 手机与 Windows 电脑连接同一局域网，打开电脑监看程序，允许其通过专用网络防火墙。
2. 手机 App 设为默认助理，确认“结构读取服务：已启用”。
3. 允许通知；如需悬浮控制，点击 **允许悬浮窗** 后开启系统权限。小米可在系统应用设置中允许后台运行 / 自启动，并在最近任务中锁定 App；入口名称随 HyperOS 版本变化。
4. 选择结构目标间隔（100 / 200 / 500 / 1000 毫秒，默认 200）和画面档位，点击 **开始实时画面与结构共享**。在系统弹窗授权共享整个屏幕；App 退到后台，结构在 3 秒后开始。
5. 桌面自动发现手机，同时显示实时画面和最新结构。通知、悬浮窗或 App 中的停止按钮都能停止全部采集和传输。

画面档位：流畅为长边 720、目标 15 fps、JPEG 质量 55；均衡为 960、10 fps、质量 65；清晰为 1280、10 fps、质量 75。分辨率保持原始比例，横竖屏切换时更新。画面走 TCP 38584，仅向连接的桌面发送，最多同时连接 2 台电脑；发送与接收均只保留最新待处理帧，慢连接会断开重连。静止画面可能没有新的图像缓冲，显示的接收 fps 会下降。

可取消“同时共享实时手机画面”以单独共享结构。每个新的屏幕共享会话都需要用户在系统弹窗授权；权限失效后不能静默恢复。受保护页面可能黑屏。[Android MediaProjection 文档](https://developer.android.com/media/grow/media-projection)

连续模式不显示本 App 的助理面板，但 Android/HyperOS 仍可能显示系统识屏提示。结构请求串行执行，目标间隔扣除本次处理耗时，不再叠加完整等待。实际速度取决于系统和页面；画面与结构独立采集，并非严格同步。超过 8 秒回传已有结果/超时状态；整个结构处理超过 12 秒则停止共享，避免请求积压。

锁屏/熄屏时暂停读取；如果系统同时撤销屏幕共享，将停止全部采集，需要回 App 重新授权。切换默认助理或进程结束也会停止，不自动恢复。持续通知和悬浮窗提高后台存活机会，不保证系统永不终止。连续结果只保留在内存，不保存录像；可在桌面导出当前结构 JSON。

结构使用 UDP **38583** 广播，并携带画面 TCP **38584** 的发现信息。传输未加密，同网段设备可接收页面文字和连接画面；点击开始后才发送。路由器访客网络或 AP 隔离可能阻断连接。桌面接收端校验并重组结构分片，丢弃不完整、重复或过期帧。

## 手机上测试

1. 下载并安装 APK，打开 **Assist 结构测试**。Android 可能要求允许浏览器安装应用。
2. 点击 **设为默认数字助理**，选择本 App。也可使用 **打开系统助理设置** 手动选择。主界面的“结构读取服务”应显示 **已启用**。
3. 在系统的助理设置中，允许“使用屏幕上的文字／屏幕内容”（如有此项）。HyperOS 的菜单名称和第三方助理支持情况因版本而异。
4. 点击 **5 秒后获取页面结构**，App 会退到后台。立即切到目标 App，在目标页面停留；5 秒后会弹出助理结果面板。也可直接在目标页面用系统助理手势／快捷键触发。
5. 等待面板出现节点统计，点击 **关闭，返回目标页面**。重新打开测试 App，查看结果；**保存完整 JSON…** 可以导出到下载目录。

先测试系统设置里的普通列表页，再测试你实际需要控制的 App。导出后再抓下一页：本 App 只保留最近一次助理会话。

若小米无法选择第三方默认助理、角色显示已选择但服务未启用、或者目标页面始终无数据，请记录 HyperOS/Android 版本和界面提示；本 App 不使用调试或权限绕过。

## 如何判断结果

每个 `activities[]` 元素有单独状态：

| 状态 | 含义 |
|---|---|
| `structure_received` | 收到至少一个窗口；还需检查实际节点、文本和 ID 是否满足需求 |
| `empty_structure` | 结构没有窗口，可能受安全窗口或系统策略限制 |
| `null_structure` | 系统没有提供结构；结合授权、`showFlags`、禁用标志判断，不能仅凭此确定原因 |
| `read_error` | 读取结构异常，错误文字保留在 `error` |

顶层 `received_callbacks` 仅表示收到了回调，不代表所有页面结构完整。`no_callback_after_8_seconds` 表示本次请求 8 秒内没有回调，后续连续请求可以重试。`errors` 记录结构传输错误。

JSON 包含机型、系统构建版本、时间、助理上下文标志、来源 Activity、窗口和父子节点；节点包含类名、文本、描述、资源 ID、尺寸、滚动和变换、可点击等状态。`boundsOnScreenEstimate` 是按窗口位置、父节点变换和滚动计算的外接矩形估计，不保证是实际可点击区域，也不做遮挡裁剪。

每个 Activity 最多遍历 20,000 个节点、128 层；超出时标记 `truncated`。App 内预览限 50,000 字符，JSON 导出保留完整已采集结果。此结构并非 UIAutomator / AccessibilityNodeInfo 树，目标应用可以不提供或限制内容。

## 数据与权限

没有无障碍服务、ADB、Root 或麦克风采集，不执行点击。画面通过系统授权的 MediaProjection 获取；`INTERNET` 用于局域网传输，前台服务权限支持持续共享，通知和悬浮窗由用户授权。结构共享服务声明 `specialUse` 用途，屏幕共享同时使用 `mediaProjection` 服务类型。Manifest 中的 `BIND_VOICE_INTERACTION` / `BIND_SPEECH_RECOGNITION` 限制系统绑定服务；识别服务只是满足助理声明要求，不做语音识别。

单次结果保存在本机应用私有目录，关闭备份；连续模式将结构广播到局域网，软件不上传到互联网。导出文件可能包含页面文字。测试结束后可停止采集，并在系统设置中恢复原默认助理。

## 构建

JDK 17、Gradle 8.10.2、Android SDK 35，AGP 8.7.3；无第三方运行时依赖。

```sh
cd android-assist
gradle --no-daemon :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 的 **Build AssistStructure apps** 工作流生成 APK 和 Windows EXE artifact。不同构建机器的 debug 签名可能不同；覆盖安装发生签名冲突时需先卸载旧测试版，卸载会删除其本地结果。

已提供编译后的测试版；小米 14 的默认助理接入、目标 App 内容完整性需由实机测试确定。

接口参考：[VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)、[onHandleAssist](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#onHandleAssist(android.service.voice.VoiceInteractionSession.AssistState))、[ViewNode](https://developer.android.com/reference/android/app/assist/AssistStructure.ViewNode)。
