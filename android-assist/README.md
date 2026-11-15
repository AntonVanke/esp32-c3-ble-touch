# AssistStructure 测试 App

用于在小米 14 / Android 14+ 验证默认数字助理能否获得前台应用的界面结构。独立于 C3 固件；测试读取结构时不需要连接开发板。

**[下载 v0.1.1 APK](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/download/assist-v0.1.1/assist-structure-lab-0.1.1.apk)** · [发布页](https://github.com/AntonVanke/esp32-c3-ble-touch/releases/tag/assist-v0.1.1)

v0.1.1 修正助理元数据根标签为 Android 要求的 `voice-interaction-service`；v0.1.0 的错误标签会导致系统拒绝启用助理服务。发布版可直接覆盖安装 v0.1.0，再重新选择默认助理。

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

顶层 `received_callbacks` 仅表示收到了回调，不代表所有页面结构完整。`no_callback_after_8_seconds` 表示 8 秒内没有回调，后续回调仍可更新结果。`errors` 记录结构传输错误。

JSON 包含机型、系统构建版本、时间、助理上下文标志、来源 Activity、窗口和父子节点；节点包含类名、文本、描述、资源 ID、尺寸、滚动和变换、可点击等状态。`boundsOnScreenEstimate` 是按窗口位置、父节点变换和滚动计算的外接矩形估计，不保证是实际可点击区域，也不做遮挡裁剪。

每个 Activity 最多遍历 20,000 个节点、128 层；超出时标记 `truncated`。App 内预览限 50,000 字符，JSON 导出保留完整已采集结果。此结构并非 UIAutomator / AccessibilityNodeInfo 树，目标应用可以不提供或限制内容。

## 数据与权限

没有无障碍服务、ADB、Root、录屏、网络或麦克风权限；不采集截图、不执行点击。Manifest 中的 `BIND_VOICE_INTERACTION` / `BIND_SPEECH_RECOGNITION` 用来限制系统绑定服务，不是向用户申请这些权限。识别服务只是满足助理声明要求，不做语音识别。

结果只保存在本机应用私有目录，关闭备份；仅在你选择导出时写入指定文件。导出内容可能包含页面文字。测试结束后可在系统设置中恢复原默认助理。

## 构建

JDK 17、Gradle 8.10.2、Android SDK 35，AGP 8.7.3；无第三方运行时依赖。

```sh
cd android-assist
gradle --no-daemon :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 的 **Build AssistStructure test APK** 工作流也生成可安装的 debug APK artifact。不同构建机器的 debug 签名可能不同；覆盖安装发生签名冲突时需先卸载旧测试版，卸载会删除其本地结果。

已提供编译后的测试版；小米 14 的默认助理接入、目标 App 内容完整性需由实机测试确定。

接口参考：[VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)、[onHandleAssist](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#onHandleAssist(android.service.voice.VoiceInteractionSession.AssistState))、[ViewNode](https://developer.android.com/reference/android/app/assist/AssistStructure.ViewNode)。
