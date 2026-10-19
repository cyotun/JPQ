# 记牌器 Android 工程

稳定源码基线 `v0.2.26`，实测包保留 `0.2.26-regression`、versionCode 28、包名 `com.cyotun.jpq`。[版本记录](../docs/releases/0.2.26.md) 说明范围；当前行为以 [当前规格](../docs/design/current-spec.md) 为准，旧版“识别未接通／缺图即等待下一局”已被覆盖。

## 本地准备

固定 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、compile/target SDK 36、min SDK 28、ABI arm64-v8a。原生 View/Canvas 界面，纯逻辑在 `:core`，Android 采集和识别在 `:app`。

仓库根目录执行：

```powershell
./tools/android/bootstrap.ps1
./tools/android/prepare-assets.ps1 -FontPath '本机官方钉钉进步体.ttf'
./tools/android/prepare-model.ps1 -ModelPath '本机合法持有的 cards.tflite'
./tools/android/prepare-reference-preprocess.ps1 -NativeLibraryDirectory '本机原生库目录' -PackedModelDirectory '本机packed模型目录'
node tools/release/identity.mjs
./tools/android/build.ps1 -Tasks ':core:test',':app:testReleaseUnitTest',':app:assembleRelease',':app:assembleReleaseAndroidTest',':app:lintRelease'
```

字体、模型、packed 模型及指定 arm64 库只在本机准备，脚本检查 SHA-256，不自动从 Git 恢复或替换。`overlay.ttf`、`models/`、`jniLibs/` 和真实截图测试资产不在仓库。缺资产时构建/截图测试失败是预期条件，不能删除校验或用假资产绕过。

`.signing/release.properties` 与密钥仅本机并另行备份。身份初始化不覆盖已有完整身份；后续升级必须使用原证书。没有原签名的产物不能覆盖已安装版，也不能作为稳定更新。调试包 `.dev` 与正式包分开。

## 输出与检查

- 应用：`app/build/outputs/apk/release/app-release.apk`。
- 手机测试包：`app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`。
- 实测冻结 APK 在本机忽略目录 `artifacts/`，完整校验值见版本记录。
- 核心检查 `core/src/test/`，宿主检查 `app/src/test/`，手机截图/采集/UI 检查 `app/src/androidTest/`。
- 连续素材、完整 ZIP、截图和报告在本机 `captures/`，不随源码分发。

回放和排障见 [维护指南](../docs/development/maintenance.md)。真实对局只允许 LogsOnly 采集，禁止同时运行 instrumentation、电脑截图、录屏或第二个共享。

## 更新边界

识别、配置、牌局和诊断都在本地。Cloudflare 仅更新；客户端检查清单签名、哈希、包名、版本、SDK、证书后调用系统安装，对局中不安装。GitHub 标签不代表服务已有新包；见 [更新服务](../services/updates/README.md)。
