# 构建、测试与维护

基线 `v0.2.26`，2026-10-19。先读 [当前规格](../design/current-spec.md)、[架构](architecture.md) 和 [版本记录](../releases/0.2.26.md)，不要从旧修复文档推断当前状态。

## 1. 建立可恢复基线

源码以 Git 标签定位；APK 以文件名、大小、SHA-256、包名、versionCode、证书共同定位。字体、模型、原生库、签名身份和私人测试素材独立本机备份，Git 不包含它们。恢复源码本身不能恢复这些私有资产。

当前应用版本 `0.2.26-regression` / 28，不为稳定标签重构建。下一版至少 29，保持包名 `com.cyotun.jpq` 和原证书。配置存 SharedPreferences，牌局记录由 `RoundRepository` 保存，升级不要清用户数据。旧 JSON 缺新增字段时走默认值和 `validate()`；改字段语义须定义迁移和旧备份测试。

## 2. 修改和验证顺序

1. 新问题先保存原 ZIP、截图及时间/局号，不覆盖旧失败目录。区分牌面识别、座位归属、事件边界、账本、显示缓存和采集生命周期。
2. 在最小负责模块修复并补能重现问题的测试；不要只隐藏错误提示或用数量差补猜牌。
3. 一批相关修复完成后冻结源码与输入；先定向回放，再完整连续回放可用的正常局来源。运行中源码/输入变动则结果作废重跑。
4. 核心、宿主、整包、Lint、手机截图/交互/共享启停分别记录。手机检查前确认没有进行中的真实对局和共享；恢复所有临时配置。
5. 确认实际大厅持续进入识别后，由用户正常打完整局。期间不点击游戏、不改设置、不安装、不启动 instrumentation，不录屏或创建第二个共享。结束后才完整导出和核对。
6. 本次用户已取消把旧素材缺口作为稳定归档阻塞项；保留原报告。未来新增来源仍须如实记录失败，不删除样本或改异常排除来制造通过。

## 3. 常用命令

工具与本机资产准备见 [app/README](../../app/README.md)。以下从仓库根目录运行，PowerShell 7：

```powershell
./tools/android/build.ps1 -Tasks ':core:test',':app:testReleaseUnitTest',':app:assembleRelease',':app:assembleReleaseAndroidTest',':app:lintRelease'
```

回放工具自检（`<python>` 替换为实际 Python 可执行文件）：

```text
<python> tools/android/test-session-replay.py
<python> tools/android/test-single-card-replay.py
<python> tools/android/test-replay-visibility.py
<python> tools/android/test-replay-cohort.py
```

从完整 ZIP 中明确选择 session，不从精简电脑日志推造缺字段：

```text
<python> tools/android/prepare-session-replay.py --archive captures/phone/<run>/diagnostics.zip --session <session-id> --output captures/phone/<run>/source-replay --manifest captures/phone/<run>/source-replay/provenance.json
```

对应 core 回放任务可输入单 tape 或 tape 目录：

```powershell
./tools/android/build.ps1 -Tasks ':core:replayLoggedFrames','-PloggedTape=D:/JPQ/captures/phone/<run>/source-replay','-PloggedOutput=D:/JPQ/captures/phone/<run>/replay-new'
```

`loggedTape` 和 `loggedOutput` 必须为绝对路径（上例按实际仓库位置替换），JavaExec 默认工作目录属于 core 子工程。重跑原固定来源时使用下面的真实本机路径，每次 `OutputDirectory` 必须全新。素材不在新克隆仓库内，缺失时不能声称执行过：

```powershell
./tools/android/run-replay-cohort.ps1 `
  -Inputs captures/phone/bugs-0.2.16-20261015/replay-inputs.json `
  -SourceLock captures/phone/bugs-0.2.16-20261015/cohort-source-lock.json `
  -OutputDirectory captures/phone/bugs-0.2.16-20261015/replay-NEW `
  -Python '<Python可执行文件绝对路径>' `
  -Exclusions captures/phone/bugs-0.2.16-20261015/verified-normal-game-exclusions.json `
  -RawArchive captures/phone/bugs-0.2.16-20261015/diagnostics.zip
```

脚本固定 33 个源 session；当前历史 gate 仍会因两个旧证据缺口返回 1。检查 `verification.json` 区分 build/source 校验失败与历史 audit gate，不修改旧 gate 来匹配本次稳定决定。将来更换测试集应新建来源清单/哈希和审核记录，保留旧集。

安全 UI 套件先检查共享已停、备份配置、运行并恢复；`-RecoverOnly` 用于恢复同一中断运行：

```powershell
./tools/android/run-ui-suite.ps1 -DeviceSerial '<serial>' -EvidenceDirectory captures/phone/ui-NEW
./tools/android/run-ui-suite.ps1 -EvidenceDirectory captures/phone/ui-NEW -RecoverOnly
```

该脚本是 UI 套件，不等于本版 108 项全部手机检查。手机回放测试需本地 fixtures 和对应类/专用探针配置；原始执行日志保存在各次 preflight 目录，不复制旧 OK 冒充新执行。

真实对局只读记录：

```powershell
./tools/android/collect-live-test.ps1 -DeviceSerial '<serial>' -OutputDirectory captures/phone/live-NEW -LogsOnly -DurationSeconds 1800
```

停止使用该目录 `stop.signal`；必须等用户确认对局结束。完整 ZIP 从 APP「设置 → 牌局与诊断」导出，或在停止采集后按 `DeviceEvidenceTest` 的既有导出测试执行。`SessionTrace` 的电脑日志会精简/省略大帧，不能替代原始 ZIP。

更新服务改动另运行 `npm test`、`npm run check`、`npm run build`（dry-run），不把 dry-run 当成部署成功。

## 4. 证据等级

| 证据 | 能说明 | 不能单独说明 |
| --- | --- | --- |
| 核心/宿主测试 | 输入条件下算法与生命周期不变量 | 实际模型对所有新牌面的准确率 |
| 手机截图回放 | 特定真实图像的解码、OCR 和规则集成 | 连续整局未漏帧 |
| recorded model/scene 回放 | 固定观测输入下事件、恢复和记账连续性 | 模型原始识别一定正确、每手已有人工真值 |
| 数量守恒/正常终局 | 内部统计与结束证据相容 | 每张牌面、花色、座位全部正确 |
| `displayState` 等日志 | 程序发布的显示状态 | 物理屏幕像素从未闪色 |
| 原图、逐手人工标注 | 可见牌面和时间的独立交叉对照 | 被遮挡像素背后的牌面 |

保存原 ZIP/trace 哈希、来源时间、事件 ID、原图 sidecar 绑定、程序版本与源码指纹；后处理花色/数量必须有同帧来源和时间，不向旧帧倒灌未来证据。

## 5. 排障索引

| 现象 | 优先检查 |
| --- | --- |
| 整局未启动 | projection 是否存在、采集尺寸/visibility epoch、持续 inference、场景和初手基线；不要先调显示缓存 |
| 级牌/♥闪问号 | 当局彩色面板确认是否保持、实体分配与显示事务，普通级牌是否重复含红桃级牌 |
| 某牌少扣/重复扣 | 原 glyph、座位、新事件边界、金色数来源；同牌再次出与动画补齐是否混淆 |
| 自己出完后冻结 | SELF_FINISHED 与整局 FINISHED 是否混淆，原末手证明、队友余牌和迟到事件重验 |
| 同花顺/字体闪黄 | rawState 与 displayState、rule scope、teammateExcluded；队友牌池待重验期间是否发布混合池结果 |
| 误报遮挡/提示歪 | 可信级牌区域、物理像素坐标、bar/body offset、tip 自身 mask 和会话忽略状态 |
| 关闭后系统共享仍在 | generation、shutdown 各资源释放、onTaskRemoved/onDestroy、系统 media_projection 与通知 |
| 日志/导出挂起 | 锁屏、USB/进程状态、大帧省略；解锁后新目录重导出，不覆盖失败证据 |
| 新克隆无法构建 | 先检查本地字体/模型/库/签名/SDK，不能为通过构建删除资产校验 |

## 6. 发布与回退

当前稳定基线只推源码、设计/维护文档和标签；私人输入、APK、参考资产、密钥不入 Git。Cloudflare 仅更新，不做诊断上传；自动关联 main 的服务构建不等于已发布新安装包。

未来发布需：原证书签名的新包、增加 versionCode、完整本次验证、冻结哈希、更新 `docs/releases/<version>.md/.json`，再走 `tools/release/package.mjs` 和 [清单契约](../design/update-contract.md) / [服务说明](../../services/updates/README.md)。只有实际上传并验证接口后才标记服务器已发布。

源码回退可从 `v0.2.26` 新建修复分支比较，避免直接覆盖工作区。Android 通常拒绝安装较低 versionCode；不能以卸载清数据作为默认回退。若新版已装，使用旧稳定代码构建一个更高 versionCode、同证书的修复包，并重新验证、发布新的签名清单。服务器“latest”只换文案或指向低版本不会自动绕过客户端版本/哈希/证书校验。
