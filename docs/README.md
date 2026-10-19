# 文档导航

当前稳定基线：**v0.2.26，2026-10-19**。已测试安装包仍名为 `0.2.26-regression`（28），本次归档保持代码和二进制不变。

| 需要做什么 | 当前入口 |
| --- | --- |
| 看稳定版本、支持范围、验证结果和限制 | [版本说明](releases/0.2.26.md)、[JSON](releases/0.2.26.json) |
| 修改首页、设置或悬浮窗 | [当前规格](design/current-spec.md) |
| 理解识别、账本、复核和同花顺算法 | [架构说明](development/architecture.md) |
| 构建、复测、排障、发布或回退 | [维护指南](development/maintenance.md)、[Android 准备](../app/README.md) |
| 追溯本版问题与证据 | [0.2.26 记录](development/repair-0.2.26.md)；同目录 0.2.20–0.2.25 为此前修复 |
| 看批准的视觉方案 | [首页和级牌](design/home-redesign/README.md)、[同花顺金字](design/straight-flush-ranks/README.md)、[遮挡提示](design/grade-occlusion/README.md) |
| 维护更新服务器 | [接口与部署](../services/updates/README.md)、[更新契约](design/update-contract.md)、[部署记录](deployment/cloudflare-status.md) |
| 查参考 APK 的研究依据 | [研究索引](research/apk-analysis.md)、[方法](research/method-analysis.md)、[遮挡调查](research/reference-capture-occlusion-review.md) |

## 文档优先级与历史

1. 用户最新确认决定当前范围。
2. `design/current-spec.md` 记录稳定版实际行为；`development/architecture.md` 和源码说明实现。
3. `releases/` 记录冻结版本的验收决定、身份和发布状态。
4. `development/repair-*.md` 保留当时问题、失败和测试，不因后续发布改写历史。
5. 早期 `brief.md`、`design/PRD.md`、`technical-plan.md`、`acceptance-plan.md` 和旧效果图作为设计演进留档；冲突以当前规格为准。网页原型核对不是 Android 真机证据。

后续改变功能或交互时，同次更新当前规格、架构/排障条目、测试证据和新版本说明；不要只改 README 的版本号。设计图静态牌组和 `PreviewData` 不得进入生产账本或验收真值。
