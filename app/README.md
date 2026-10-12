# 记牌器 Android 工程

当前 0.2.2-regression 为个人回归测试版。第一局真实测试中途因炸弹遮挡后重复扣牌失败，已据录像修复。97 项核心检查、手机八项颜色/遮挡/OCR检查与CPU固定图复测通过；完整录像的三家28次逐事件回放通过，第二局真机仍待验收。GPU本次验证停滞，当前测试手机使用CPU。见 [第一局修复记录](../docs/development/first-phone-round.md) 与 [真机计划](../docs/development/live-test-plan.md)。

固定 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、compile/target SDK 36、min SDK 28，当前手机实际 API 37；首版 ABI arm64-v8a。界面为 Kotlin 原生 View / Canvas，内部保存使用 SharedPreferences / SQLite。

```powershell
./tools/android/bootstrap.ps1
./tools/android/prepare-assets.ps1 -FontPath '本机官方钉钉进步体.ttf'
./tools/android/prepare-model.ps1 -ModelPath '本机合法持有的 cards.tflite'
node tools/release/identity.mjs
./tools/android/build.ps1 -Tasks ':core:test',':app:assembleRelease',':app:lintRelease'
```

字体与参考模型只在本机作个人适配，不进入 Git；构建严格检查 SHA-256，不悄悄替换。新克隆需另行准备本地资产。私钥只在 `.signing`，必须另行备份；初始化脚本不覆盖已有完整身份。

正式包 `com.cyotun.jpq` 使用自研项目持续签名，调试包 `.dev` 与其分开，不覆盖参考软件。回归版版本号不代表已通过准确率验收。截图和日志只留在忽略目录 `captures/phone`。

观察到完整新局手牌后才显示精确数字；画面缺失、阶段冲突或归属不明时等待下一局。不提供手动补记或纠错。调整页 `PreviewData` 明确标为示例，不进入账本。

Cloudflare 仅用于更新；清单、整包哈希、包名、版本、安装证书与系统版本全部通过检查后才交给官方安装页面，对局期间不安装。尚未发布稳定版本时返回 204，服务器不接收游戏截图、手牌或账本。

依据：[AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[LiteRT](https://developers.google.com/edge/litert/migration)、[随包 OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[官方字体](https://page.dingtalk.com/wow/dingtalk/default/dingtalk/y-W5aF3_ZJwzulU0nceIl)。
