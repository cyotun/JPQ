# 本机屏幕采集 UI 测试工具

仅用于已授权的本机 USB 测试。没有桌面入口，不属于记牌器正式 APK。运行前必须确认没有真实对局，记牌器采集已停止。

Android Gradle 的 androidTest manifest 会覆盖 instrumentation target；这个独立小 APK 临时替换已有的 `com.cyotun.jpq.tests` 测试包，并使用相同的本机签名。它以自身为 instrumentation target，结束后不会终止 `com.cyotun.jpq` 的采集进程。完成操作后恢复正常 releaseAndroidTest APK。

构建：`tools/android/build.ps1 -Tasks ':phoneProbe:assembleDebug'`。本机 `.signing/release.properties` 必须存在。输出 `tools/android/phone-probe/build/outputs/apk/debug/phoneProbe-debug.apk`，不提交二进制或密钥。

原 UI driver 只支持显式参数 `startCaptureProbe=true` 或 `stopCaptureProbe=true`。根节点属于记牌器时才点击其「开始记牌／停止记牌」；系统节点明确包含记牌器名称时才点击标准屏幕共享按钮。其他应用、游戏、解锁界面和设置不会被点击，15 秒内没有匹配即退出。

工具返回成功只表示标准 UI 动作已发出。必须另外读取 CaptureService、MediaProjection 和实际三帧图像，核对 display0、全屏、全 crop、真实模型输入及日志。不得把工具完成当作记牌准确性验收，也不得在真实对局中运行。

## 独立的单应用共享范围实验

`ScopeProbeActivity` 和 `ScopeProbeService` 只属于本机测试 APK，不修改生产 APK、旧 UI driver 或旧动态测试画面。先确认真实对局已结束，生产记牌器的屏幕采集已停止。

审查后实验需要两个测试 APK：底图和投影属于 `com.cyotun.jpq.tests`，洋红覆盖层属于单独 UID 的 `com.cyotun.jpq.scopeoverlay`，与实际游戏和记牌器分别属于两个应用的条件一致。不能用旧版同一应用创建两类窗口的工具代替这次验证。辅助 APK 没有桌面入口、采集、存储或网络权限；只向同签名且 UID 包含指定测试包的客户端提供绑定服务。

准备时先恢复上轮遗留的 USB 保持亮屏和旧测试包悬浮 app-op，再记录本轮要改的原值。构建与安装顺序如下，仅在用户准备好、已解锁且无真实对局时执行：

```powershell
tools/android/build.ps1 -Tasks @(':phoneProbe:testDebugUnitTest', ':phoneProbe:assembleDebug', ':scopeOverlay:assembleDebug')
adb install -r tools/android/scope-overlay/build/outputs/apk/debug/scopeOverlay-debug.apk
adb install -r tools/android/phone-probe/build/outputs/apk/debug/phoneProbe-debug.apk
adb shell appops get com.cyotun.jpq.scopeoverlay SYSTEM_ALERT_WINDOW
# 把上一步原值写入本机 operator-state 后，才设置实验辅助包的悬浮权限。
adb shell appops set com.cyotun.jpq.scopeoverlay SYSTEM_ALERT_WINDOW allow
```

仅显示专用测试图（没有 opt-in 参数会立即退出）：

```powershell
adb shell am start -W -n com.cyotun.jpq.tests/com.cyotun.jpq.vision.ScopeProbeActivity --ez scopeProbe true --es orientation landscape --ei timeoutSeconds 45
```

额外加 `--ez requestConsent true` 会在自家画面就绪后自动打开标准系统共享选择窗口。授权由独立的未导出测试任务请求，以免系统选择器排除底图任务；结果通过本包 PendingIntent 返回，超时或退出即撤销。只有 `scopeProbe=true` 时不会自动请求权限。也可点自家「开始单应用共享实验」。标准选择器中只选专用测试应用，不打开游戏，不调用隐藏任务/截图接口。

分别完成两次阳性/排除对照；每次都是一份新授权和单次 MediaProjection 会话：

1. 在标准选择窗口选「单个应用」→ **记牌器手机测试**，正常允许共享。
2. 单次实验自动停止后，再启动一次标准选择窗口，选「整个屏幕」并允许共享。

两次实验都应留在专用测试图。系统文案和选项可能因澎湃 OS 版本不同而变化；选项不明确时先取消，不能用 token 或收到帧就宣布单应用共享成功。拒绝授权不会创建 projection 或 PNG。独立悬浮层辅助 APK 如未获悬浮窗权限，只记录 `permission_missing` 并停止，不自动打开权限设置；由已授权的本机操作者单独配置后重新实验。

底图是纯绿，带蓝/橙位置块及每 250ms 变化的 16 位黑黄计数条。至少两幅不同计数、不同原始图像时间戳的无遮挡基线通过后，服务才绑定另一个应用并创建主屏上的不透明洋红 `TYPE_APPLICATION_OVERLAY`。仅在本专用 Activity 位于前台并有焦点、颜色/标记几何/动态条符合时保存 PNG；其他画面只记数值。独立定时检查在离开前台 1.5 秒或动态画面停滞 5 秒后停止，即使完全没有图像回调也会执行。最多保存 12 幅 PNG，无云上传。

采集服务检查两个包的实际 UID 不相同，核验辅助程序消息发送 UID、会话 ID、所属主屏和实际窗口范围。辅助程序每次心跳报告自身窗口是否仍显示；采集侧只使用近 500ms 内的窗口状态，失联则结束。解除绑定或心跳停止 3 秒会移除洋红层；辅助程序也有 90 秒硬上限。

结果位于 `/sdcard/Android/data/com.cyotun.jpq.tests/files/scope-probe/<runId>/`：

- `events.jsonl`：授权后的尺寸、resize/可见性回调、原始图像时间戳、颜色统计、定位块与底图矩形、物理测试图/洋红覆盖层坐标、旋转、计数值、PNG 文件名。
- `frame-*.png`：只含已确认的专用测试图；基线、覆盖层稳定后和旋转尺寸均可对照。
- `summary.json`：`OVERLAY_PIXELS_PRESENT`、`OVERLAY_PIXELS_NOT_OBSERVED` 或 `INCONCLUSIVE`。记录两个包的实际 UID；有效结论要求同一稳定尺寸／位置下至少三幅原始时间戳递增且计数至少两种的合格帧。普通的其他动态帧不能给静止的绿区统计补足计数。异常终止只保留原始像素证据，最终结论降为 `INCONCLUSIVE`。

`NOT_OBSERVED` 还必须与**独立的实际屏幕截图**对照，确认手机上洋红覆盖层确实可见，同时采集 PNG 的相应区域实际仍为绿色。`PRESENT` 应能在全屏阳性对照中检测到相应区域的洋红。未显示覆盖层、未知画面、没有足够动态帧、回调终止等均不能作成功证明。

默认 45 秒自动结束，可显式设置 15–90 秒。点「停止实验」、通知中的「停止」、退出本测试 Activity、projection 被系统终止、连续无法确认测试图都会清理。超时后再次进入页面可直接发起新实验；超时撤销后迟到的授权结果不会启动新采集。旋转可只改变本 Activity 的 `orientation` extra（仍须 `scopeProbe=true`，不加 `requestConsent`）；服务在 `onCapturedContentResize` 中 resize 原有 VirtualDisplay 并替换 ImageReader，绝不重复 createVirtualDisplay。旋转或移动后立即清除旧结论，等待实际窗口稳定 600ms，再重新收集独立证据。`summary.displayCreations` 应始终为 1。

完成后确认 MediaProjection 已停止、洋红覆盖层已移除，恢复记录的临时 app-op／亮屏原值，再恢复正常的 releaseAndroidTest APK，并移除本次独立安装的 `com.cyotun.jpq.scopeoverlay` 辅助包。它不保存资料，不得误卸载生产 APP。不能在这项实验运行时启动原 instrumentation driver，它以此测试 APK 为 target，结束时可能终止本实验进程。

2026-10-15 最终状态：整屏阳性和单应用排除对照均已完成，包括横屏→竖屏→横屏、独立 UID、独立物理截图和采集 PNG 对照。20 项工具单元检查通过；实际系统对洋红色窗口的合成结果为 `(204,51,204)`，颜色判定已覆盖该值及负例。原始失败记录也保留。

实验投影停止、正常 AndroidTest 包恢复、临时亮屏值恢复 0、旧测试悬浮 app-op 恢复 default，辅助包卸载。用户选择不接入生产，工具仅留存备用，正式 APP 继续原整屏共享流程。完整结论与局限见 [实测记录](../../../docs/development/scope-probe-20261015.md)，此前审查见 [修复记录](../../../docs/development/review-0.2.16.md)。

## Card recognition fixture display

`CardFixtureProbeActivity` displays a bundled real card fixture only when explicitly launched with `cardFixtureProbe=true`, a `caseIndex` from 1 through 4, and `orientation` set to `landscape` or `portrait`. It reads the existing `app/src/androidTest/assets/fixtures/case1.jpg` through `case4.jpg`; Gradle stages only those four files as helper APK assets, without copying or changing the source fixtures. The screen uses a black background and `FIT_CENTER`, preserving the complete image and its original aspect ratio. System bars are hidden and the screen stays awake. `timeoutSeconds` is optional, defaults to 180, and accepts 15–300 seconds. Invalid arguments cause the activity to exit. Launching it again in the same task can change the case or orientation.

After confirming there is no real game in progress and production capture is stopped, install the local helper APK and launch the fixture through Android's normal Activity entry point:

```powershell
adb shell am start -W -n com.cyotun.jpq.tests/com.cyotun.jpq.vision.CardFixtureProbeActivity --ez cardFixtureProbe true --ei caseIndex 1 --es orientation landscape --ei timeoutSeconds 180
```

Change `caseIndex` to select another fixture; rerunning the command updates the current task. This gives the screen-capture-to-model-input path a deterministic real card image. It does not measure full live recognition accuracy and must never be recorded as a production model-ledger success.

An 8×8 pixel blue/green clock at the far top-right alternates every 250ms. A completely static ImageView may generate only one or two Surface buffers, so a running-frame preflight would otherwise time out despite correctly reading the image. This marker is outside the card detector's accepted horizontal region; exclude it from any whole-image fixture comparison.

## SurfaceView card fixture lifecycle probe

`SurfaceCardFixtureProbeActivity` shows the same bundled cases through a dedicated `SurfaceView` and `SurfaceHolder` Canvas. It reuses the validated `cardFixtureProbe`, `caseIndex`, `orientation`, and `timeoutSeconds` contract. The image is drawn fully with `FIT_CENTER` on black at its original aspect ratio, with system bars hidden, cutout layout enabled, a no-ActionBar theme, and `resizeableActivity=false`. A 8×8 blue/green clock at the top-right changes on each 250 ms Canvas frame. All decoding and drawing stay on the main thread; `surfaceDestroyed` stops the frame loop before the old holder can be used again.

Use the same explicit fixture launch arguments with this Activity name:

```powershell
adb shell am start -W -n com.cyotun.jpq.tests/com.cyotun.jpq.vision.SurfaceCardFixtureProbeActivity --ez cardFixtureProbe true --ei caseIndex 1 --es orientation landscape --ei timeoutSeconds 180
```

To replace the SurfaceView in the existing single-top task while changing the fixture, add `--ez recreateSurface true` and provide the new `caseIndex` or `orientation`. Without that flag the current surface remains and the new case is drawn into it. This checks capture behavior across a real `SurfaceView` buffer lifecycle; it still does not establish successful recognition during a complete live game.

## OpenGL card fixture lifecycle probe

`GlCardFixtureProbeActivity` uses a GLES2 `GLSurfaceView` and a real uploaded texture for the same bundled fixture contract. It draws the full image centered with aspect ratio preserved, maps bitmap top rows to the top of the screen, clears the surrounding area to black, and alternates an 8×8 blue/green marker at the far top-right on its GL render thread about once per second. The clock deliberately leaves time for raw/model diagnostic pairing on a software GLES host; it is not a recognition throughput benchmark. ImageView and Canvas fixtures retain their 250 ms clocks. Its Activity remains responsive while the GL thread renders. `onPause`/`onResume` are forwarded to `GLSurfaceView`; `--ez recreateSurface true` in the existing single-top task removes the old view/context and installs a fresh one, exercising EGL surface/context creation. Bitmap replacement and release are synchronized with GL upload/draw, and the renderer keeps the current bitmap until Activity teardown so a recreated EGL context can upload it again.

Launch it with the same `cardFixtureProbe=true`, `caseIndex`, `orientation`, and optional `timeoutSeconds` arguments, using this Activity name:

```powershell
adb shell am start -W -n com.cyotun.jpq.tests/com.cyotun.jpq.vision.GlCardFixtureProbeActivity --ez cardFixtureProbe true --ei caseIndex 1 --es orientation landscape --ei timeoutSeconds 180
```

The emulator consent fixture runner accepts `-RenderMode gl`; surface lifecycle switches such as `-RecreateSurfaceAfterConsent` and `-RecreateOnTaskReturn` apply to both `surface` and `gl`. This helper only stresses GL-backed capture surfaces. Its successful image capture is not a full live-game recognition result.

## 采集到识别与开局恢复回归

只在模拟器使用 `tools/android/run-emulator-card-fixture.ps1`。`-Case 1..4` 选牌面，`-RenderMode surface` 使用独立绘图层，`-RenderMode gl` 使用 GLES2 纹理；`-InitialOrientation portrait`、`-RecentTaskCycle`、`-RecreateSurfaceAfterConsent` 分别覆盖方向和任务/绘图层生命周期。脚本核对正常授权产生的真实目标 task/UID，原始图与模型图必须有至少一组同时间戳配对，且稳定取样区间至少十帧的生产手牌/出牌结果全部满足 golden。任一失败不算通过。

`-RefreshTaskSurface` 是严格模拟器的手动恢复对照，要求重挂前已实际观测到缺失手牌，未复现缺陷时拒绝判通过。`-ExpectStartupRecovery` 则要求最终 trace 有自动恢复事件，两种方式不能混用。普通 golden 场景不强制要求触发恢复。所有轮次最后应正常停止系统投影；不能在真实对局运行这些会停止共享的插桩脚本。

`run-emulator-unknown-diagnostic.ps1` 独立验证开局未识别画面的本地稀疏 JPEG 保存，要求同一新会话的两组图像/metadata/trace 证据匹配。C3 还必须实际触发有界的 8 秒开局兜底，检查同会话 `detached → reattached → first_frame` 顺序，以及重挂后新 epoch 的 FULL 模型帧。测试画面刻意保持 UNKNOWN；通过只证明自动服务链路和本地证据保存，不验证整局记账，也不为手机布局修复背书。近黑 3 秒快路与 8 秒兜底共享每会话 2 次、至少 15 秒间隔的预算；已绑定的牌局不执行这项恢复。
