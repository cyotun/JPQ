# 文档导航

当前稳定基线：**v0.2.26，2026-10-19**。已测试安装包仍名为 `0.2.26-regression`（28），本次归档保持代码和二进制不变。

| 需要做什么 | 当前入口 |
| --- | --- |
| 确认 BETA 开发、验收、正式发布与回滚规则 | [根目录项目规则](../AGENTS.md)、[发布流程](development/beta-release-workflow.md) |
| 查看最新 BETA 的六局核查和正式更新恢复 | [BETA C4.2](development/beta-c4.2-live-review-20261024.md)、[正式更新](development/official-updates-20261024.md) |
| 查看 C4.2 真机非对局检查及下一轮实战准备 | [手机检查记录](development/phone-beta-c42-20261024.md) |
| 查看最新 BETA 的一级设置更新按钮 | [C4.3 实现与验证](development/beta-c4.3-inline-updates-20261024.md)、[交互规格](design/inline-updates.md) |
| 看稳定版本、支持范围、验证结果和限制 | [版本说明](releases/0.2.26.md)、[JSON](releases/0.2.26.json) |
| 修改首页、设置或悬浮窗 | [当前规格](design/current-spec.md) |
| 理解识别、账本、复核和同花顺算法 | [架构说明](development/architecture.md) |
| 构建、复测、排障、发布或回退 | [维护指南](development/maintenance.md)、[Android 准备](../app/README.md) |
| 在电脑验证浮窗/通知下层采集方法，再准备手机 Beta | [Android 模拟器采集实测](development/emulator-capture-20261020.md) |
| 查看单应用 Beta 的小米手机覆盖隔离、启停与实战准备 | [手机非对局检查](development/phone-beta-capture-20261021.md) |
| 对识别/账本做图片或整局回归（不验证系统采集隔离） | [电脑识别工具](development/desktop-first-testing.md) |
| 追溯本版问题与证据 | [0.2.26 记录](development/repair-0.2.26.md)；同目录 0.2.20–0.2.25 为此前修复 |
| 看批准的视觉方案 | [首页和级牌](design/home-redesign/README.md)、[同花顺金字](design/straight-flush-ranks/README.md)、[遮挡提示](design/grade-occlusion/README.md) |
| 维护更新服务器 | [接口与部署](../services/updates/README.md)、[更新契约](design/update-contract.md)、[部署记录](deployment/cloudflare-status.md) |
| 查参考 APK 的研究依据 | [研究索引](research/apk-analysis.md)、[方法](research/method-analysis.md)、[遮挡调查](research/reference-capture-occlusion-review.md) |
| 跟进记牌器BETA 单应用采集与降耗，不覆盖正式版 | [BETA 验证与回滚](development/single-app-candidate.md)、[研究依据](research/single-app-capture-and-power-20261019.md) |

## 文档优先级与历史

1. 用户最新确认决定当前范围。
2. `design/current-spec.md` 记录稳定版实际行为；`development/architecture.md` 和源码说明实现。
3. `releases/` 记录冻结版本的验收决定、身份和发布状态。
4. `development/repair-*.md` 保留当时问题、失败和测试，不因后续发布改写历史。
5. 早期 `brief.md`、`design/PRD.md`、`technical-plan.md`、`acceptance-plan.md` 和旧效果图作为设计演进留档；冲突以当前规格为准。网页原型核对不是 Android 真机证据。

后续改变功能或交互时，同次更新当前规格、架构/排障条目、测试证据和新版本说明；不要只改 README 的版本号。设计图静态牌组和 `PreviewData` 不得进入生产账本或验收真值。
