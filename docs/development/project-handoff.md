# 项目交接与维护入口 · 2026-11-12

当前正式版本 **v0.2.27 / code 29**，源码主线 `main`，生产更新已上线。版本身份、修复、验证范围和限制见 [发布记录](../releases/0.2.27.md)。本轮按用户要求跳过新的真机及实战验收；电脑回归通过不代表所有新画面、设备或未来对局都准确。

## 当前文档入口

1. [AGENTS.md](../../AGENTS.md)：正式/BETA、测试、隐私、清理及发布约束。
2. [当前规格](../design/current-spec.md)：页面、黑金悬浮条、级牌/逢人配、同花顺、位置和隐藏交互。
3. [架构](architecture.md) 和 [恢复不变量](recognition-recovery-invariants.md)：采集、证明、账本、显示缓存与恢复的职责边界。
4. [维护指南](maintenance.md)、[Android 准备](../../app/README.md)：构建、保留回归输入、排障入口。
5. [发布流程](beta-release-workflow.md) 和 [更新服务](../../services/updates/README.md)：版本身份、签名、上传、切流与回退。

上述文档代表当前状态。早期 brief/PRD、设计原型、`repair-*` 和按日期命名的实测报告保留历史，不把其中“当前包”“待测试”或已删除采集路径当作今天的状态。

## 源码与身份

纯逻辑在 `core/`，Android 界面、采集、识别、诊断和更新在 `app/`，更新服务在 `services/updates/`。正式包名 `com.cyotun.jpq`、BETA 包名 `com.cyotun.jpq.candidate` 不变；显示名分别为「记牌器」「记牌器BETA」。

本轮开发分支归档为 `archive/beta-through-0.2.27` 后删除。后续新功能仍在临时 BETA 分支开发、review 和验证，明确验收后再提升正式版；本轮豁免不自动适用于未来发布。不得因为删除分支而卸载手机应用或清空应用数据。

`SINGLE_APP_CAPTURE_ENABLED` 控制已提升的采集/恢复能力；`SINGLE_APP_CAPTURE_CANDIDATE` 只控制 BETA 身份、更新隔离及实验入口。API 37+ 使用系统单应用授权，旧系统使用 DISPLAY；整屏输入的遮挡不可凭空恢复。当前完整 CPU 识别路径不继承旧 reference 资格，尚无本轮新增功耗实测结论。

## 本机必须保留的内容

| 内容 | 位置与用途 |
| --- | --- |
| 签名及发布密钥 | `.signing/`，正式安装与更新信任身份；只在本机和另行安全备份中保存 |
| 合法持有的模型、字体、原生库 | `app/src/main/assets/`、`app/src/main/jniLibs/`；构建工具检查指定哈希，Git 不包含二进制 |
| 最小原图测试资产 | `app/src/androidTest/assets/fixtures/`；并非新一轮手机通过证据 |
| 正式回滚与本次发布 | `artifacts/rollback-v0.2.26/`、`artifacts/release-0.2.27/`；APK、清单、测试报告、源码 bundle、校验信息 |
| 保留电脑回归 | `artifacts/regression-minimal-0.2.27/`；锁定来源、预期事件/牌池及未核实来源的完整原始诊断 |
| 清理执行记录 | `artifacts/maintenance-20261112/`；精确计划、保护文件哈希、删除及空间记录，不提交公共仓库 |

源码仓库不含这些私有二进制。仅克隆 Git 无法直接恢复签名、模型和图像回归；不能删资产校验来绕过。旧官方 APK 的签名与源码 bundle 已验证，本次发布也冻结同类产物。

## 电脑验证入口

```powershell
./tools/android/build.ps1 -Tasks ':core:test',':app:testReleaseUnitTest',':app:assembleRelease',':app:lintRelease'
./tools/android/build.ps1 -Tasks ':app:testCandidateUnitTest',':app:assembleCandidate',':app:lintCandidate','-PjpqCandidate=true'
```

```text
python tools/android/run-retained-regression.py --output artifacts/regression-minimal-0.2.27/replay-NEW
```

先构建以准备编译类和固定 Kotlin 依赖。输出目录必须全新；脚本校验来源 SHA-256，连续重放 19 个正常局、比较完整事件与最终牌池。额外中断输入仍未核实，脚本保留该事实；不得为通过而重置未知历史。tape 回放验证已记录观测的处理，不能代替模型重跑原图或每手独立人工真值。

模拟器和系统镜像本轮已清理；未来确需采集方法实验时按 [历史实验流程](emulator-capture-20261020.md) 重新安装对应工具。JDK、Gradle、Android 编译工具及依赖保留，普通构建不需要模拟器。

## 清理与后续排障

已按本轮明确授权对历史测试采集、录像、模拟器及镜像、过期测试 APK、重复研究环境和构建临时文件生成逐项清单。原本整目录删除被自动审批拒绝后，先保留未核实中断来源的完整原始 ZIP（包括 trace 与图片，字节哈希一致），再提交保留原始证据后的清理计划。用户桌面原文件、源码、必要资产、密钥和正式回滚包不在删除范围。

清理于 2026-11-12 完成：106 个精确目标、63,102 个文件，逻辑大小 57,740,698,230 字节；实际磁盘可用空间增加 57,783,967,744 字节（约 57.8 GB / 53.8 GiB），由 43.9 GB 增至 101.7 GB。保留的 505 个必要文件在删除前后均通过 SHA-256 核对。未核实来源的完整原始 ZIP 保留约 365 MB；当前回归和必要诊断不再依赖旧 `captures/` 路径。历史报告中的路径仅用于追溯。未来出现问题应新建证据目录、按当次版本保留最小失败样本，完成修复/验证后再清理重复采集。

下一正式包 versionCode 至少 30，沿用原证书和更新公钥。不得自动降级或以卸载清数据回退；完整步骤见发布流程。
