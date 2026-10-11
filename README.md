# JPQ · 大掼蛋记牌器

面向个人使用的 Android 本地记牌工具。首版完整适配腾讯独立应用「大掼蛋」，通过用户授权的屏幕采集识别已显示的牌面，使用悬浮窗显示手牌外剩余牌和三家出牌记录。

**当前阶段：Android App 仍在设计确认阶段；按用户新增授权，独立 Cloudflare 更新服务已实现并进行部署修复。当前尚无可安装的自研 APK。**

## 设计资料

- [产品方案与待确认设计](docs/brief.md)
- [界面示意](docs/design/design-preview.png)
- [参考 APK 的静态研究](docs/research/apk-analysis.md)
- [反混淆后的方法研究](docs/research/method-analysis.md)
- [原 APK 默认显示参数](docs/research/reference-display-defaults.json)
- [开始后的等待提示示意](docs/design/waiting-preview.png)
- [GitHub 类似项目调查](docs/research/github-survey.md)
- [技术实现方案](docs/design/technical-plan.md)
- [验收与开发里程碑](docs/design/acceptance-plan.md)
- [独立 Cloudflare 自动更新方案](docs/design/cloudflare-update-plan.md)
- [更新服务源码与接口](services/updates/README.md)

## 已确认的方向

- 首版支持腾讯「大掼蛋」独立 App；保留多平台、多玩法扩展结构。
- 使用设备：小米 17 Pro Max、小米 17 Ultra 徕卡版；用户报告系统为澎湃 OS 3/4。
- 以稳定记牌为首要目标：自动识别、剩余统计、三家出牌记录、手动纠错。
- 炸弹潜力计数标红，普通计数白色，0 张淡化；牌序按玩法可拖动，尺寸/字号可调，右侧三家记录可关闭。
- 配色、默认尺寸和整体交互沿用原 APK；普通点数阈值 4，大小王各自阈值 2。开始后保留左上角等待提示。
- 其他交互改进先沟通、确认后实现。
- 记牌在本地离线运行；无会员、收费、登录和广告。仅版本检查/下载安装包使用独立 Cloudflare 服务。
- 参考用户提供 APK 的功能与操作行为，项目源码独立实现。
- 开发使用 Git，源码同步到 [cyotun/JPQ](https://github.com/cyotun/JPQ)。

## 数据与版本管理

原始参考 APK、私人游戏录屏、签名密钥和本机配置不纳入仓库。研究阶段保存分析结论；开发阶段按功能提交，记录实际验证结果。源码和可再分发的自有资产进入 Git，训练原始数据在本地管理。

研究日期：2026-10-11（Asia/Shanghai）。
