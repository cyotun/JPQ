# 文档导航

当前正式版：**v0.2.27，versionCode 29，已上线**。签名清单和完整生产下载已验证；本轮新增手机及安装测试按用户要求豁免。旧正式回滚基线为 `v0.2.26` / versionCode 28。

| 需要做什么 | 当前入口 |
| --- | --- |
| 开新会话继续维护 APP | [根目录后续开发提示词](../后续开发提示词.md) |
| 了解当前项目及本机保留项 | [交接记录](development/project-handoff.md) |
| 确认 BETA 开发、正式发布和回滚规则 | [项目规则](../AGENTS.md)、[发布流程](development/beta-release-workflow.md) |
| 查看本轮发布状态、验证结果及豁免范围 | [v0.2.27 发布记录](releases/0.2.27.md)、[版本 JSON](releases/0.2.27.json) |
| 查看旧正式回滚基线 | [v0.2.26 发布记录](releases/0.2.26.md)、[版本 JSON](releases/0.2.26.json) |
| 修改首页、设置或悬浮窗 | [当前规格](design/current-spec.md) |
| 理解识别、账本、复核和同花顺算法 | [架构说明](development/architecture.md)、[识别与恢复约束](development/recognition-recovery-invariants.md) |
| 构建、运行最小回归、排障、发布或回退 | [维护指南](development/maintenance.md)、[Android 本地准备](../app/README.md) |
| 修改或检查更新服务 | [接口与部署](../services/updates/README.md)、[更新契约](design/update-contract.md)、[部署记录](deployment/cloudflare-status.md) |
| 查看批准的视觉方案 | [首页和级牌](design/home-redesign/README.md)、[同花顺金字](design/straight-flush-ranks/README.md)、[遮挡提示](design/grade-occlusion/README.md) |
| 查参考 APK 的研究依据 | [研究索引](research/apk-analysis.md)、[方法](research/method-analysis.md)、[遮挡调查](research/reference-capture-occlusion-review.md) |

## 当前采集范围与发布边界

正式版在 Android API 37 及以上使用 `APP_TASK` 单应用采集，旧系统使用 `DISPLAY`；两者都保留 Android 系统授权。能力开关 `SINGLE_APP_CAPTURE_ENABLED` 与 BETA 身份开关 `SINGLE_APP_CAPTURE_CANDIDATE` 独立。BETA 包名和更新身份与正式版隔离；不维护日常 BETA 分支，需要测试时按需临时构建。

本轮电脑回归有 19 个正常局来源、28,442 帧和 681 个事件；另一个 3,521 帧的中断来源尚未核实，不计为通过。用户明确豁免新增真机/实战验收，不能将豁免写成验收通过。完整范围由 v0.2.27 发布记录给出。

## 文档优先级与历史

1. 用户最新明确决定约束当前范围。
2. `design/current-spec.md` 和 `development/architecture.md` 记录当前设计与实现入口；`development/maintenance.md` 记录可重跑流程及证据边界。
3. `releases/` 记录冻结版本的身份、验证范围及线上发布状态。
4. `development/repair-*.md`、按日期命名的 BETA/手机检查和旧回归说明只保留当时的发现及证据，不代表当前源码状态、可用素材位置或本轮验收。
5. 早期 brief、PRD、技术方案和原型是设计演进档案；与当前规格冲突时以当前规格为准。原历史报告仍保留链接和事实，不批量改写。

旧 33-source cohort 和历史 `captures/` 路径不作为本轮当前可用回归集。当前最小私人回归素材仅在本机 `artifacts/regression-minimal-0.2.27/` 保留，具体文件与运行入口见维护指南；私有 tape、manifest 和 expected 结果不提交 Git。
