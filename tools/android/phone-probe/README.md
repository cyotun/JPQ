# 本机屏幕采集 UI 测试工具

仅用于已授权的本机 USB 测试。没有桌面入口，不属于记牌器正式 APK。运行前必须确认没有真实对局，记牌器采集已停止。

Android Gradle 的 androidTest manifest 会覆盖 instrumentation target；这个独立小 APK 临时替换已有的 `com.cyotun.jpq.tests` 测试包，并使用相同的本机签名。它以自身为 instrumentation target，结束后不会终止 `com.cyotun.jpq` 的采集进程。完成操作后恢复正常 releaseAndroidTest APK。

构建：`tools/android/build.ps1 -Tasks ':phoneProbe:assembleDebug'`。本机 `.signing/release.properties` 必须存在。输出 `tools/android/phone-probe/build/outputs/apk/debug/phoneProbe-debug.apk`，不提交二进制或密钥。

只支持显式参数 `startCaptureProbe=true` 或 `stopCaptureProbe=true`。根节点属于记牌器时才点击其「开始记牌／停止记牌」；系统节点明确包含记牌器名称时才点击标准屏幕共享按钮。其他应用、游戏、解锁界面和设置不会被点击，15 秒内没有匹配即退出。

工具返回成功只表示标准 UI 动作已发出。必须另外读取 CaptureService、MediaProjection 和实际三帧图像，核对 display0、全屏、全 crop、真实模型输入及日志。不得把工具完成当作记牌准确性验收，也不得在真实对局中运行。
