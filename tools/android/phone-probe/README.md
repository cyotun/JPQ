# 本机屏幕采集 UI 测试工具

仅用于已授权的本机 USB 测试。没有桌面入口，不属于记牌器正式 APK。运行前必须确认没有真实对局，记牌器采集已停止。

Android Gradle 的 androidTest manifest 会覆盖 instrumentation target；这个独立小 APK 临时替换已有的 `com.cyotun.jpq.tests` 测试包，并使用相同的本机签名。它以自身为 instrumentation target，结束后不会终止 `com.cyotun.jpq` 的采集进程。完成操作后恢复正常 releaseAndroidTest APK。

构建：`tools/android/build.ps1 -Tasks ':phoneProbe:assembleDebug'`。本机 `.signing/release.properties` 必须存在。输出 `tools/android/phone-probe/build/outputs/apk/debug/phoneProbe-debug.apk`，不提交二进制或密钥。

原 UI driver 只支持显式参数 `startCaptureProbe=true` 或 `stopCaptureProbe=true`。根节点属于记牌器时才点击其「开始记牌／停止记牌」；系统节点明确包含记牌器名称时才点击标准屏幕共享按钮。其他应用、游戏、解锁界面和设置不会被点击，15 秒内没有匹配即退出。

工具返回成功只表示标准 UI 动作已发出。必须另外读取 CaptureService、MediaProjection 和实际三帧图像，核对 display0、全屏、全 crop、真实模型输入及日志。不得把工具完成当作记牌准确性验收，也不得在真实对局中运行。

## 独立的单应用共享范围实验

`ScopeProbeActivity` 和 `ScopeProbeService` 只属于本机测试 APK，不修改生产 APK、旧 UI driver 或旧动态测试画面。先确认真实对局已结束，生产记牌器的屏幕采集已停止。

仅显示专用测试图（没有 opt-in 参数会立即退出）：

```powershell
adb shell am start -W -n com.cyotun.jpq.tests/com.cyotun.jpq.vision.ScopeProbeActivity --ez scopeProbe true --es orientation landscape --ei timeoutSeconds 45
```

额外加 `--ez requestConsent true` 会在自家画面就绪后自动打开标准系统共享选择窗口。只有 `scopeProbe=true` 时不会自动请求权限。也可点自家「开始单应用共享实验」。测试工具不代替用户选应用，不打开游戏，不调用隐藏任务/截图接口。

分别完成两次阳性/排除对照；每次都是一份新授权和单次 MediaProjection 会话：

1. 在标准选择窗口选「单个应用」→ **记牌器手机测试**，正常允许共享。
2. 单次实验自动停止后，再启动一次标准选择窗口，选「整个屏幕」并允许共享。

两次实验都应留在专用测试图。系统文案和选项可能因澎湃 OS 版本不同而变化；选项不明确时先取消，不能用 token 或收到帧就宣布单应用共享成功。拒绝授权不会创建 projection 或 PNG。独立测试 APK 如未获悬浮窗权限，只记录 `permission_missing`，不自动打开权限设置；由已授权的本机操作者单独配置后重新实验。

底图是纯绿，带蓝/橙位置块及每 250ms 变化的 16 位黑黄计数条。至少两幅不同计数的无遮挡基线通过后，服务才创建已知位置的不透明洋红 `TYPE_APPLICATION_OVERLAY`。仅在本专用 Activity 位于前台并有焦点、颜色/标记几何/动态条符合时保存 PNG；其他画面只记数值并在连续 5 秒无法确认底图时停止。最多保存 12 幅 PNG，无云上传。

结果位于 `/sdcard/Android/data/com.cyotun.jpq.tests/files/scope-probe/<runId>/`：

- `events.jsonl`：授权后的尺寸、resize/可见性回调、原始图像时间戳、颜色统计、定位块与底图矩形、物理测试图/洋红覆盖层坐标、旋转、计数值、PNG 文件名。
- `frame-*.png`：只含已确认的专用测试图；基线、覆盖层稳定后和旋转尺寸均可对照。
- `summary.json`：`OVERLAY_PIXELS_PRESENT`、`OVERLAY_PIXELS_NOT_OBSERVED` 或 `INCONCLUSIVE`。结论要求基线/覆盖后多幅独立动态帧，而非仅收到图像。

`NOT_OBSERVED` 还必须与**独立的实际屏幕截图**对照，确认手机上洋红覆盖层确实可见，同时采集 PNG 的相应区域实际仍为绿色。`PRESENT` 应能在全屏阳性对照中检测到相应区域的洋红。未显示覆盖层、未知画面、没有足够动态帧、回调终止等均不能作成功证明。

默认 45 秒自动结束，可显式设置 15–90 秒。点「停止实验」、通知中的「停止」、退出本测试 Activity、projection 被系统终止、连续无法确认测试图都会清理。旋转可只改变本 Activity 的 `orientation` extra（仍须 `scopeProbe=true`，不加 `requestConsent`）；服务在 `onCapturedContentResize` 中 resize 原有 VirtualDisplay 并替换 ImageReader，绝不重复 createVirtualDisplay。`summary.displayCreations` 应始终为 1。

完成后确认 MediaProjection 已停止、洋红覆盖层已移除，再恢复正常的 releaseAndroidTest APK。不能在这项实验运行时启动原 instrumentation driver，它以此测试 APK 为 target，结束时可能终止本实验进程。

2026-10-15 当前状态：用户选择稍后进行。实验工具最终构建和静态检查通过（0 错误、10 条警告），尚未请求实际共享、启动实验投影或生成实验结果；不能宣称本机单应用共享通过。手机在最终清理前断开，临时 USB 保持亮屏原值 0／最后值 3、测试包悬浮 app-op 原值 default／最后值 allow 待连接后恢复。常规 AndroidTest 包也待恢复；实验开始前应重新安装最终冻结产物，避免使用首次构建候选。
