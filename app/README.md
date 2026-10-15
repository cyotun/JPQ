# 记牌器 Android 工程

**最新实局结论：0.2.15 第二轮主流程连续到结算，级牌与提示仍未通过验收；前一轮整局未启动和取证干扰的失败继续保留，见[失败记录](../docs/development/live-round-failure-0.2.15.md)。**

当前 0.2.16-regression（versionCode 18）已安装到手机并校验持续签名，316 项核心检查和本轮 35 项级牌相关手机回放检查通过，静态检查零错误。真实 Q 面板识别与冲突恢复的修复、原条下提示的空间限制和单应用共享验证方向见 [本轮记录](../docs/development/repair-0.2.16.md)。此前 38 项界面/采集检查与连续横竖屏恢复见 [0.2.15 手机记录](../docs/development/phone-preflight-0.2.15.md)，不能代替新版本完整实局验收。识别沿用原 APK 快速管线的个人适配，见 [适配记录](../docs/development/adaptation.md)；下一轮按 [真机计划](../docs/development/live-test-plan.md) 由用户正常操作牌局。

固定 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、compile/target SDK 36、min SDK 28，此前连接手机实际 API 37；首版 ABI arm64-v8a。界面为 Kotlin 原生 View / Canvas，内部保存使用 SharedPreferences / SQLite。

```powershell
./tools/android/bootstrap.ps1
./tools/android/prepare-assets.ps1 -FontPath '本机官方钉钉进步体.ttf'
./tools/android/prepare-model.ps1 -ModelPath '本机合法持有的 cards.tflite'
# 原快速管线的本机探针需另备原APK的指定7个arm64库和2个packed模型：
./tools/android/prepare-reference-preprocess.ps1 -NativeLibraryDirectory '本机原生库目录' -PackedModelDirectory '本机packed模型目录'
node tools/release/identity.mjs
./tools/android/build.ps1 -Tasks ':core:test',':app:assembleRelease',':app:lintRelease'
```

字体、参考模型及原生库只在本机作个人适配，不进入 Git；准备脚本检查 SHA-256，不悄悄替换。新克隆需另行准备本地资产。私钥只在 `.signing`，必须另行备份；初始化脚本不覆盖已有完整身份。

正式包 `com.cyotun.jpq` 使用自研项目持续签名，调试包 `.dev` 与其分开，不覆盖参考软件。回归版版本号不代表已通过准确率验收。截图和日志只留在忽略目录 `captures/phone`。

观察到完整新局手牌后才显示精确数字；画面缺失、阶段冲突或归属不明时等待下一局。不提供手动补记或纠错。调整页 `PreviewData` 明确标为示例，不进入账本。

Cloudflare 仅用于更新；清单、整包哈希、包名、版本、安装证书与系统版本全部通过检查后才交给官方安装页面，对局期间不安装。尚未发布稳定版本时返回 204，服务器不接收游戏截图、手牌或账本。

依据：[AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[LiteRT](https://developers.google.com/edge/litert/migration)、[随包 OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[官方字体](https://page.dingtalk.com/wow/dingtalk/default/dingtalk/y-W5aF3_ZJwzulU0nceIl)。
