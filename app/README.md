# 记牌器 Android 工程

正式稳定回滚基线为 `v0.2.26` / versionCode 28。当前正式版 `v0.2.27` / code 29 已发布，包名 `com.cyotun.jpq`、签名身份保持不变。线上签名清单、整包下载与身份已验证；本轮按用户要求未重测手机安装。以 [本轮发布记录](../docs/releases/0.2.27.md) 和 [当前规格](../docs/design/current-spec.md) 为准。

## 本地准备

固定 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、compile/target SDK 36、min SDK 28、ABI arm64-v8a。运行时 Android API 37 及以上使用 `APP_TASK` 单应用采集，旧系统使用 `DISPLAY`；Android 系统授权步骤对两者都保留。采集能力由 `SINGLE_APP_CAPTURE_ENABLED` 控制，独立于 `SINGLE_APP_CAPTURE_CANDIDATE` BETA 身份/包名。正式包启用单应用能力不意味着改变 `com.cyotun.jpq` 包名或安装签名。原生 View/Canvas 界面，纯逻辑在 `:core`，Android 采集和识别在 `:app`。

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
- 本轮最小私人回归素材在本机 `artifacts/regression-minimal-0.2.27/`，保留 `source-manifest.json`、`normal-*.tape`、`expected-*.json`、`interrupted-input.tape`、回归报告及未核实来源的完整原始诊断 ZIP；不随源码分发。其余已验证录像/模拟器和重复采集数据按授权清理。中断输入仍未核实，不计为通过。

回放和排障见 [维护指南](../docs/development/maintenance.md)。本轮用户明确豁免新增真机及实战验收，不能改记为通过。未来如开展实战，始终由用户操作；对局期间只读证据，不操作游戏、不改设置、不重启采集。

## 更新边界

识别、配置、牌局和诊断都在本地。Cloudflare 仅更新；客户端检查清单签名、哈希、包名、版本、SDK、证书后调用系统安装，对局中不安装。设置一级菜单右侧的单一更新按钮执行检查/下载，系统负责安装确认。GitHub 标签不代表服务已有新包；见 [更新服务](../services/updates/README.md)。
