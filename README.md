# 记牌器 · JPQ

**最新实局结论：更换取证方式后的 0.2.15 第二轮主流程连续到结算，仍存在级牌未确认和提示不显示；尚未通过完整验收。此前整局未启动及录像干扰的失败仍保留，见[失败记录](docs/development/live-round-failure-0.2.15.md)。**

面向个人使用的 Android 本地记牌工具。首版目标为适配腾讯独立应用「大掼蛋」，通过用户授权的屏幕采集识别已显示的牌面，使用悬浮窗显示手牌外剩余牌和三家出牌记录。

**当前阶段：0.2.18-regression 修复尾牌漏记、头像「加倍」误重置及冲突后永久停记，接入带源像素绑定的余牌复核，并恢复已确认的条下方级牌提醒。452 项核心检查通过；按用户最新标准，33 份来源（153,200 帧）中的 43 个完整局段通过记录连续性检查，6 个有证据的异常/不完整来源单列，仍有 1 个正常局段待验证。无需硬凑 33 局，但正常局必须全部通过；当前尚未通过整体验收，也没有证明 99.9% 稳定性。候选仍需手机验证。保留整屏共享，不接入单应用共享或诊断上传。**

工程与构建说明见 [Android 工程](app/README.md)，本轮范围、全量验收规则和保留失败见 [0.2.18 修复记录](docs/development/repair-0.2.18.md)，上一版手机结果见 [0.2.17 修复记录](docs/development/repair-0.2.17.md)。故障证据见 [用户诊断 ZIP 分析](docs/development/diagnostic-export-review-20261015.md)。当前安装包为个人适配测试版，尚未作为稳定更新发布。

本轮手机实际检查及失败保留记录见 [0.2.15 手机检查](docs/development/phone-preflight-0.2.15.md)。

更新服务：[jpq.240438457.workers.dev](https://jpq.240438457.workers.dev/)。健康检查已通过，未发布安装包时版本接口返回 204。部署与验证记录见 [Cloudflare 状态](docs/deployment/cloudflare-status.md)。

## 设计资料

设计基线：[已确认的设计稿 v4](docs/design/PRD.md) 与 [六张静态设计图](docs/design/review/README.md)。应用名「记牌器」。右侧基础 320dp，超长记录才自动扩展，左侧/字体保持原样。保留按钮开关与双击隐藏、全自动零错漏验收、原 APK 外观和 Cloudflare 应用自动更新。用户已整体确认 D1–D4，无需再次申请开发许可。

2026-10-15 确认的布局覆盖旧图中的冲突项：[首页与级牌样式](docs/design/home-redesign/README.md)、[视觉核对记录](docs/design/home-redesign/design-qa.md)。服务器仅保留更新功能，不接收诊断日志、图片或录像。

用户已选定 [1 号黑金图标](docs/design/icons/README.md)；设计源图与其余候选已保存。

- [产品方案与待确认设计](docs/brief.md)
- [界面汇总示意（旧稿留档）](docs/design/design-preview.png)
- [参考 APK 的静态研究](docs/research/apk-analysis.md)
- [反混淆后的方法研究](docs/research/method-analysis.md)
- [原 APK 默认显示参数](docs/research/reference-display-defaults.json)
- [JPQ 基础显示参数与最长历史计算](docs/design/history-width.md)
- [原字体与配色证据](docs/research/display-style.md)
- [开始后的等待提示示意](docs/design/waiting-preview.png)
- [GitHub 类似项目调查](docs/research/github-survey.md)
- [技术实现方案](docs/design/technical-plan.md)
- [开发就绪核对与剩余实现工作](docs/design/development-readiness.md)
- [验收与开发里程碑](docs/design/acceptance-plan.md)
- [独立 Cloudflare 自动更新方案](docs/design/cloudflare-update-plan.md)
- [清单签名与下载契约](docs/design/update-contract.md)
- [更新服务源码与接口](services/updates/README.md)

## 已确认的方向

- 首版支持腾讯「大掼蛋」独立 App；保留多平台、多玩法扩展结构。
- 使用设备：小米 17 Pro Max、小米 17 Ultra 徕卡版；用户报告系统为澎湃 OS 3/4。
- 以稳定准确记牌为交付门槛：自动识别、剩余统计和三家出牌记录；不设置手动纠错、补记或改初始手牌。
- 炸弹潜力计数标红，普通计数白色，0 张淡化；牌序按玩法可拖动，尺寸/字号可调，右侧三家记录可关闭。
- 配色、字体、行高和整体交互沿用原 APK；普通点数阈值 4，大小王各自阈值 2。开始后保留左上角等待提示。右侧基础宽度适当缩短，超长才加宽，末尾留少量边距。
- 可关闭悬浮条的重置按钮；可开启双击隐藏并移除隐藏按钮。隐藏仅留显示入口，后台继续记牌。
- 其他交互改进先沟通、确认后实现。
- 记牌在本地离线运行；无会员、收费、登录和广告。仅版本检查/下载安装包使用独立 Cloudflare 服务。
- 参考用户提供 APK 的功能与操作行为，项目源码独立实现。
- 开发使用 Git，源码同步到 [cyotun/JPQ](https://github.com/cyotun/JPQ)。

## 数据与版本管理

原始参考 APK、私人游戏录屏、签名密钥和本机配置不纳入仓库。研究阶段保存分析结论；开发阶段按功能提交，记录实际验证结果。源码和可再分发的自有资产进入 Git，训练原始数据在本地管理。

研究日期：2026-10-11（Asia/Shanghai）。
