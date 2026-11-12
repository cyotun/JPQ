# 文档完整性审计与逐文件目录

2026-11-12。基线为正式v0.2.27/code29及本轮文档审计，不改生产代码、不重新发布APK、不连接手机。

## 结论与补齐内容

原文档能概括产品，但**还不足以让新会话可靠接续全部要求**：缺少最终需求逐项追踪、账本/场景/显示状态区分、证明家族和存储配置细节；部分旧文档会误导新会话。已补齐下列入口：

- [需求与决策追踪](requirements-traceability.md)：最终有效要求、被取消/替代的方案、实现入口和未完成范围。
- [程序逻辑手册](development/program-logic.md)：从输入到事务和显示的流程、状态表、实体/颜色区别、proof、队友剔除、同花顺规则范围、线程/保存/更新。
- [当前规格](design/current-spec.md)、[架构](development/architecture.md)、[维护](development/maintenance.md)已互相链接并对齐源码事实。
- 根目录接续提示词、交接页和文档导航已把以上列为必读；本页逐份区分当前、历史、研究和原型配置。

目前这些文档可支撑**当前已发布范围**的后续迭代；并不表示最初提出的所有设备/所有玩法都已验收，也不表示所有函数都能脱离源码阅读。阈值细节以指明的实现和测试为准，修改语义须同步文档。

## 检查方法与边界

1. 盘点根目录项目说明、`docs/`、Android/服务/工具README中的Markdown和JSON；逐份登记标题和用途，对全文扫描旧交互、过时发布/测试状态与缺失链接。
2. 对当前权威文档逐段核对用户最终决定，并与核心账本、观察/恢复、花色/级牌/同花顺、界面/配置/生命周期、存储和更新源码对应。
3. 历史文档保留原结果与日期，通过醒目状态头说明其不能作为当前待办；不重写当年的失败为通过。历史JSON原始结果不修改，在目录中分类。
4. 核对当前文档相对路径、Markdown链接、JSON语法、需求编号唯一性和源码文件入口。历史失效素材只列明缺失，不伪造或重建私人证据。
5. 原型HTML/图片/模板运行库作为设计或配置资产登记，不把它们当作Android程序已运行的证据；未逐像素重新审图，也没有新一轮模型/手机/实战测试。

本轮文本校验结果：111份登记文档（97份Markdown、14份JSON），73个唯一需求编号；全部Markdown文件链接均能在当前工作区解析，JSON语法通过，文中显式提及的Kotlin文件名均可逐项定位。56份历史Markdown仅增加状态说明，原历史正文没有删除或改写。新增逻辑管线说明另与源码人工复核，不能把文本检查称为APP运行测试。

审计清单与校验摘要保留于本机`artifacts/documentation-audit-20261112/`，不含新的牌局采集。本轮没有新增录像、模拟器或编译产物，不需要清理新的大体积测试数据。

## 已发现并修正的主要问题

| 问题 | 本次处理 |
| --- | --- |
| 维护指南末尾仍称0.2.27待线上验证 | 改为已发布、生产下载验证完成、手机安装按授权豁免 |
| brief/旧PRD/开发就绪稿仍写长按工具、返回记牌、全局异常停显示 | 标为历史方案，以当前规格及需求追踪为准，不恢复已取消交互 |
| 更新契约仍说只发布0.2.26、没有存储或APK | 更新到实际0.2.27/Static Assets，区分暂存上传和切换latest |
| 单应用BETA时间线、早期同花顺记录将旧状态称“最新/当前” | 历史标记；指向现行逻辑及发布记录 |
| “只算天然”容易误读为没有变牌或UNKNOWN=不可能 | 补构造证据、候选上界、NATURAL_ONLY范围及显示缓存的边界 |
| 场景与账本状态混淆，缺事件/实体/数量来源说明 | 新增实际枚举和状态转换、来源表、事件幂等/拒绝事务及proof消费顺序 |
| 配置即时生效与草稿、诊断开关及保存限额未写清 | 补具体行为、默认系数、schema1、会话边界及各目录限额 |
| “Wi-Fi空闲下载”可能被理解为设备idle或仅Wi-Fi | 记录用户原意及实际非计量网络+停止记牌条件，手动流量确认另列 |
| 原33集和各轮“素材保留”易被当作今天仍可重跑 | 明确当前19正常局+1未核实中断来源、历史清理状态及原证据位置 |

## 仍不能写成“已完成验收”的事项

- 多游戏、不同大掼蛋大厅入口及两种目标手机/系统组合的完整适配覆盖。
- 未核实中断来源丢失历史的恢复；固定观测回归不能证明未知牌面。
- 目标设备的温升/耗电/时延量化，以及99.9%统计证明。
- 本轮用户明确豁免的新增真机/实际安装/实战验证，后续默认不能沿用该豁免。

## 文档维护约定

需求变化先更新追踪编号及当前规格；算法/事务变化更新逻辑手册、架构和不变量；发布更新release、部署状态、维护与根目录提示词；证据清理更新留存现状。旧设计保留历史标签，不能同时维护两套互相冲突的“当前规格”。项目迁移仍使用相对`JPQ/`的路径，不重新写死盘符。

<!-- DOCUMENT-CATALOG:START -->

## 逐文件目录

本次登记 111 份Markdown/JSON，包括原型模板配置；图片、HTML及源码未作为独立文档逐行审计。所有登记项已读取标题/状态并归类，当前关键逻辑另与源码核对。

### 1 当前规范与维护入口

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [后续开发提示词.md](../后续开发提示词.md) | 当前规则、流程或状态；迭代时同步维护 |
| [AGENTS.md](../AGENTS.md) | 当前规则、流程或状态；迭代时同步维护 |
| [app/README.md](../app/README.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/deployment/cloudflare-status.md](deployment/cloudflare-status.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/design/current-spec.md](design/current-spec.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/design/inline-updates.md](design/inline-updates.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/design/update-contract.md](design/update-contract.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/architecture.md](development/architecture.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/beta-release-workflow.md](development/beta-release-workflow.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/maintenance.md](development/maintenance.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/program-logic.md](development/program-logic.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/project-handoff.md](development/project-handoff.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/recognition-recovery-invariants.md](development/recognition-recovery-invariants.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/development/test-data-retention.md](development/test-data-retention.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/documentation-audit.md](documentation-audit.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/README.md](README.md) | 当前规则、流程或状态；迭代时同步维护 |
| [docs/requirements-traceability.md](requirements-traceability.md) | 当前规则、流程或状态；迭代时同步维护 |
| [README.md](../README.md) | 当前规则、流程或状态；迭代时同步维护 |
| [services/updates/README.md](../services/updates/README.md) | 当前规则、流程或状态；迭代时同步维护 |
| [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | 当前规则、流程或状态；迭代时同步维护 |

### 2 当前正式发布冻结记录

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/releases/0.2.27.json](releases/0.2.27.json) | 正式安装身份、发布事实及实际验证边界；不篡改冻结结果 |
| [docs/releases/0.2.27.md](releases/0.2.27.md) | 正式安装身份、发布事实及实际验证边界；不篡改冻结结果 |

### 3 历史正式发布冻结记录

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/releases/0.2.26.json](releases/0.2.26.json) | 旧正式版本与回退依据；不是当前版本 |
| [docs/releases/0.2.26.md](releases/0.2.26.md) | 旧正式版本与回退依据；不是当前版本 |

### 4 工具说明与当时实验记录

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/development/desktop-first-testing.md](development/desktop-first-testing.md) | 可复用工具用法；旧环境/素材和结果须重新核实 |
| [docs/development/emulator-capture-20261020.md](development/emulator-capture-20261020.md) | 可复用工具用法；旧环境/素材和结果须重新核实 |
| [tools/android/phone-probe/README.md](../tools/android/phone-probe/README.md) | 可复用工具用法；旧环境/素材和结果须重新核实 |
| [tools/android/scope-overlay/README.md](../tools/android/scope-overlay/README.md) | 可复用工具用法；旧环境/素材和结果须重新核实 |

### 5 历史方案与被替代设计

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/brief.md](brief.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/acceptance-plan.md](design/acceptance-plan.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/cloudflare-update-plan.md](design/cloudflare-update-plan.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/development-readiness.md](design/development-readiness.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/PRD.md](design/PRD.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/review/README.md](design/review/README.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/review/validation.md](design/review/validation.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |
| [docs/design/technical-plan.md](design/technical-plan.md) | 追溯设计变化；冲突时按需求追踪和当前规格 |

### 6 历史修复、验证及BETA时间线

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/development/adaptation.md](development/adaptation.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.10-live-audit-20261026.md](development/beta-c4.10-live-audit-20261026.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.2-live-review-20261024.md](development/beta-c4.2-live-review-20261024.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.3-inline-updates-20261024.md](development/beta-c4.3-inline-updates-20261024.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.4-live-fixes-20261024.md](development/beta-c4.4-live-fixes-20261024.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.5-live-audit-20261025.md](development/beta-c4.5-live-audit-20261025.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.6-live-audit-20261025.md](development/beta-c4.6-live-audit-20261025.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.7-live-audit-20261025.md](development/beta-c4.7-live-audit-20261025.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.8-live-audit-20261026.md](development/beta-c4.8-live-audit-20261026.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/beta-c4.9-systemic-review-20261026.md](development/beta-c4.9-systemic-review-20261026.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/candidate-c2-failure-20261021.md](development/candidate-c2-failure-20261021.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/candidate-c4-optimization-20261023.md](development/candidate-c4-optimization-20261023.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/candidate-c4-review-20261023.md](development/candidate-c4-review-20261023.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/desktop-testing-20261020.json](development/desktop-testing-20261020.json) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/diagnostic-export-review-20261015.md](development/diagnostic-export-review-20261015.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/first-phone-round.md](development/first-phone-round.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/foundation-verification.json](development/foundation-verification.json) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/foundation.md](development/foundation.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/grade-and-straight-flush-0.2.12.md](development/grade-and-straight-flush-0.2.12.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/late-round-regression-0.2.10.md](development/late-round-regression-0.2.10.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/late-round-regression-0.2.11.md](development/late-round-regression-0.2.11.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/late-round-regression-0.2.9.md](development/late-round-regression-0.2.9.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/live-round-failure-0.2.13.md](development/live-round-failure-0.2.13.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/live-round-failure-0.2.15.md](development/live-round-failure-0.2.15.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/live-test-plan.md](development/live-test-plan.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/native-pipeline-phone-preflight.md](development/native-pipeline-phone-preflight.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/official-updates-20261024.md](development/official-updates-20261024.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-beta-c42-20261024.md](development/phone-beta-c42-20261024.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-beta-c44-20261025.md](development/phone-beta-c44-20261025.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-beta-capture-20261021.md](development/phone-beta-capture-20261021.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-c3-non-game-20261022.md](development/phone-c3-non-game-20261022.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-c4-review-non-game-20261024.md](development/phone-c4-review-non-game-20261024.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-preflight-0.2.13.md](development/phone-preflight-0.2.13.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/phone-preflight-0.2.15.md](development/phone-preflight-0.2.15.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.15.md](development/repair-0.2.15.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.16.md](development/repair-0.2.16.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.17.md](development/repair-0.2.17.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.18.md](development/repair-0.2.18.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.19.md](development/repair-0.2.19.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.20.md](development/repair-0.2.20.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.22.md](development/repair-0.2.22.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.23.md](development/repair-0.2.23.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.24.md](development/repair-0.2.24.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.25.md](development/repair-0.2.25.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/repair-0.2.26.md](development/repair-0.2.26.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/review-0.2.16.md](development/review-0.2.16.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/scope-probe-20261015.md](development/scope-probe-20261015.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/second-phone-round.md](development/second-phone-round.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/single-app-candidate.json](development/single-app-candidate.json) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/single-app-candidate.md](development/single-app-candidate.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |
| [docs/development/straight-flush-implementation.md](development/straight-flush-implementation.md) | 查根因和旧验证；日期、构建、素材不自动沿用 |

### 7 参考研究与方法来源

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/research/apk-analysis.md](research/apk-analysis.md) | 查研究方法/参数来源；不是生产功能或测试结论 |
| [docs/research/display-style.md](research/display-style.md) | 查研究方法/参数来源；不是生产功能或测试结论 |
| [docs/research/github-survey.md](research/github-survey.md) | 查研究方法/参数来源；不是生产功能或测试结论 |
| [docs/research/method-analysis.md](research/method-analysis.md) | 查研究方法/参数来源；不是生产功能或测试结论 |
| [docs/research/reference-capture-occlusion-review.md](research/reference-capture-occlusion-review.md) | 查研究方法/参数来源；不是生产功能或测试结论 |
| [docs/research/reference-display-defaults.json](research/reference-display-defaults.json) | 查研究方法/参数来源；不是生产功能或测试结论 |
| [docs/research/single-app-capture-and-power-20261019.md](research/single-app-capture-and-power-20261019.md) | 查研究方法/参数来源；不是生产功能或测试结论 |

### 8 首页原型及其模板配置

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/design/home-redesign/.openai/hosting.json](design/home-redesign/.openai/hosting.json) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/AGENTS.md](design/home-redesign/AGENTS.md) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/design-qa.md](design/home-redesign/design-qa.md) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/mobile-runtime.lock.json](design/home-redesign/mobile-runtime.lock.json) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/package-lock.json](design/home-redesign/package-lock.json) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/package.json](design/home-redesign/package.json) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/README.md](design/home-redesign/README.md) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/src/mobile/COMPONENTS.md](design/home-redesign/src/mobile/COMPONENTS.md) | 设计/模板资产；不是原生APP实现或手机运行结果 |
| [docs/design/home-redesign/tsconfig.json](design/home-redesign/tsconfig.json) | 设计/模板资产；不是原生APP实现或手机运行结果 |

### 9 已确认视觉及参数依据

| 文档（相对根目录） | 用途与阅读边界 |
| --- | --- |
| [docs/design/display-defaults.json](design/display-defaults.json) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/grade-occlusion/README.md](design/grade-occlusion/README.md) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/history-width-analysis.json](design/history-width-analysis.json) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/history-width.md](design/history-width.md) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/icons/prompts.json](design/icons/prompts.json) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/icons/README.md](design/icons/README.md) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/straight-flush-ranks/README.md](design/straight-flush-ranks/README.md) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |
| [docs/design/straight-flush/README.md](design/straight-flush/README.md) | 视觉、参数及计算依据；生产行为以当前规格与源码为准 |

<!-- DOCUMENT-CATALOG:END -->
