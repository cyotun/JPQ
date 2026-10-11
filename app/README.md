# 记牌器 Android 工程

2026-10-11。设计已获用户确认，开发已经开始。当前是基础开发版：可以构建 APK，包含主页面、显示配置、授权采集、原字体悬浮绘制、隐藏/显示、调整预览和纯记账核心。

**自动牌面识别尚未接通，不能用于实战计数。** `AwaitingValidatedAdapter` 只保留采集接口，明确返回未完成适配，不生成示例牌局。调整页的 `PreviewData` 是标明“示例”的预览，完全不进入实际账本。更新页目前只检查服务状态；签名清单验证、下载、安装和每日自动调度尚未实现。

## 构建

工程固定 AGP 8.13.2、Gradle 8.13、Kotlin 2.3.21、JDK 17、compile/target SDK 36、min SDK 28。主页面采用 Kotlin 原生 View，悬浮条使用同一 Canvas 绘制器供正式视图和调整预览使用。AndroidX Activity 1.12.3 处理返回手势和授权结果。

参考依据：[AGP 官方兼容要求](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Kotlin 构建兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)、[AndroidX Activity 发布说明](https://developer.android.com/jetpack/androidx/releases/activity)。固定这些版本用于首批验证，不表示所有组件都采用当日最新版。

Windows 项目内环境准备：

```powershell
./tools/android/bootstrap.ps1
./tools/android/prepare-assets.ps1 -FontPath '本机官方钉钉进步体.ttf'
./tools/android/build.ps1
```

也可以用已有完整 JDK 17/Android SDK 配置 `local.properties`，再运行 `gradlew.bat :core:test :app:assembleDebug :app:lintDebug`。项目脚本把工具和缓存放在忽略目录 `.android-tools` / `.gradle-home`；不修改系统 PATH。新版 Google CLI 使用 `platforms/android-36` 和 `build-tools/35.0.0` 的包名。

从 [官方字体页面](https://page.dingtalk.com/wow/dingtalk/default/dingtalk/y-W5aF3_ZJwzulU0nceIl) 获取与设计一致的字体，遵守随附声明。字体二进制不进入 Git；构建前检查实际 SHA-256，缺失或不匹配会失败，不悄悄替换字体。当前本机个人调试 APK 包含已核实字体与声明；公开分发前仍按既有字体来源/发布约束处理。不要上传本地签名文件或参考模型。

## 运行边界

- 开发包身份为 `com.cyotun.jpq.dev`，版本 `0.1.0-dev+debug`，Android 调试签名；与正式包 `com.cyotun.jpq` 及其未来固定签名分开。
- 开始时先请求通知（如需要），再请求系统共享授权；服务进入 mediaProjection 前台状态后创建采集会话，不复用旧授权 token。
- 等待条提示识别适配仍在开发。可检查悬浮权限、采集帧数、隐藏/显示、重置开关、双击隐藏、拖动和工具入口。
- 尺寸/牌序页是横屏调整，使用真实绘制器和明确标注的示例；保存才写入配置，退出不保存，旋转保留草稿。
- 原字体/阈值、默认基础右侧宽度与长记录扩展已经接入；真实数值只有有效识别事件提交后才可显示。当前没有这样的识别提交来源。
- 配置备份仅导出显示设置，系统云备份和设备迁移排除应用数据。当前账本在内存，Room 持久化、最近 20 局和完整回放导出尚待实现。

## 下一阶段

继续按参考 APK 可读方法与业务脚本推进适配，建立可验证的牌面/阶段识别链路。原生库、模型实际推理与最新大掼蛋布局尚未验证，不能据 APK 的类名和资源存在就宣布复现。真实手机授权、触摸、两部手机完整对局零错漏、所有目标玩法及更新安装仍需测试。

另需补齐仅计数模式独立尺寸保存、原风格滑块细节、停止/异常状态的最终呈现、本地事件持久化、正式签名身份及更新客户端。当前基础版本不替代最终交付。
