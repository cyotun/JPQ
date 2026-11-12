# 记牌器 · JPQ

Android 本地记牌工具，当前适配腾讯独立 App「大掼蛋」四人、两副牌玩法。授权屏幕共享后识别已显示的物理牌面，以黑金悬浮条显示手牌外剩余牌、三家出牌数量与明细，以及同花顺可能性。没有账号、会员、广告或手动改牌功能。

测试应用名称固定为 **记牌器BETA**，正式应用名称为 **记牌器**。新功能和修复仍先在独立 BETA 身份下开发、review 和电脑回归；BETA 不接收正式更新。日常开发不维护常驻 BETA 分支，需要验证时按需临时构建。本轮 BETA 工作分支归并前保留 archive 标签以供追溯，再删除分支。项目规则见 [AGENTS.md](AGENTS.md) 和 [BETA 与正式版发布流程](docs/development/beta-release-workflow.md)。

## 当前正式版本

正式版 **v0.2.27（versionCode 29）已上线**，应用名「记牌器」、包名 `com.cyotun.jpq`，沿用原签名身份。签名清单、完整生产下载哈希及安装身份已验证；用户可从原正式 APP 内更新。按用户本轮要求未做新手机安装验证。旧正式回滚基线为 `v0.2.26` / code 28。

本轮电脑回归覆盖 19 个正常局来源，共 28,442 帧、681 个事件。另有一个中断来源共 3,521 帧，结果仍未核实，不计为通过。用户明确豁免本轮新增真机和实战验收；豁免不代表设备兼容、实战验收通过或任何准确率结论。936 项核心、正式/BETA 各 31 项宿主及 8 项服务测试通过；整包与 Lint 检查通过。

正式版 API 37 及以上启用 Android `APP_TASK` 单应用采集，较旧 Android 使用 `DISPLAY` 整屏采集；两种方式均保留 Android 系统屏幕共享授权。采集能力开关 `SINGLE_APP_CAPTURE_ENABLED` 与 BETA 身份开关 `SINGLE_APP_CAPTURE_CANDIDATE` 分离，不能把正式包的采集范围与 candidate 包身份绑定。

- [本轮版本说明与验证范围](docs/releases/0.2.27.md) / [版本 JSON](docs/releases/0.2.27.json)
- [旧正式回滚基线](docs/releases/0.2.26.md) / [版本 JSON](docs/releases/0.2.26.json)
- [项目交接和本机保留项](docs/development/project-handoff.md)
- [项目交接和本机保留项](docs/development/project-handoff.md)
- [文档导航与优先级](docs/README.md)
- [当前产品和 UI 规格](docs/design/current-spec.md)
- [架构、算法与代码入口](docs/development/architecture.md)
- [测试、排障与升级维护](docs/development/maintenance.md)
- [Android 本地资产与构建准备及回归入口](app/README.md)

## 功能与范围

记牌页沿用「平台 → 玩法 → 人数 → 副数 → 牌库 → 开始」结构，授权成功后按钮变为停止。底部仅记牌/设置；未适配游戏保留禁用扩展位置。

支持牌序、尺寸、字号、三家区域显示模式、重置按钮和双击隐藏。展开条与隐藏后的显示按钮均可拖动，位置持久化；长按不弹悬浮设置。级牌角标、♥逢人配格、同花顺图案及金色候选点数按确认稿显示。

识别冲突后台复核，保留最近确认显示，充分确认后再扣牌；缓存不作为新证据，不凭数量猜牌。自己出完后继续队友视角，确认队友余牌后剔除。游戏版本、玩法、布局或设备变化需重新验证，当前实测不推及全部大厅入口和手机。

更新服务使用独立 Cloudflare Worker `jpq`，只服务 APP 更新，不上传诊断、录像或对局图片。设置一级菜单右侧按钮直接完成更新检查/下载并交给 Android 系统安装确认。v0.2.27 已发布；网络分发与客户端校验已核对，实际手机安装未在本轮重测。

## 资产与源码

源码：[cyotun/JPQ](https://github.com/cyotun/JPQ)。项目自有源码 MIT；第三方字体、模型和原生库有独立来源与许可，不随仓库发布。原 APK、私人素材、签名密钥、本机配置和构建产物由 `.gitignore` 排除。新克隆需自行准备合法持有的本地资产，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
