# Cloudflare 自动更新方案 · 待账户信息与交互确认

日期：2026-10-11。用户要求：使用自己的 Cloudflare 免费资源为 App 提供自动更新，与现有其他项目分开部署。当前只完成设计和文档，未连接账户、创建资源或部署。

## 1. 方案与隔离边界

候选方案：新建独立 Worker 提供最新版本信息，通过独立安装包存储提供下载。建议项目名 `jpq-updates`、存储名 `jpq-releases`，只是拟定名称，创建前核对账户和重名。

- 使用 JPQ 专用部署目录与配置，不在原项目目录发布。
- 新 Worker 使用独立访问地址；如使用自定义域名，新建专用子域名，不覆盖原域名/路由。
- 如采用 R2，新建专用 bucket，不复用原项目对象或修改其绑定。
- 发布令牌与配置单独管理，尽量限制到所需资源；对于账户级权限，发布工具校验 JPQ 目标名单。
- 不执行批量覆盖、账户级迁移或原项目套餐变更。

同一账户的免费额度不等于按项目隔离，尤其 Workers 请求额度属于账户计划限制。独立项目解决部署、配置和数据边界，不能宣称免费额度也完全独立。依据 [Workers 限制](https://developers.cloudflare.com/workers/platform/limits/)。

## 2. 免费资源选型

[Workers Free](https://developers.cloudflare.com/workers/platform/pricing/) 当前包含每日 100,000 请求和每次 10ms CPU；小型版本接口采用缓存和轻量逻辑，不运行识别或训练。

[R2 Standard 免费额度](https://developers.cloudflare.com/r2/pricing/) 当前为每月 10 GB-month 存储、100 万 Class A、1,000 万 Class B 操作，出口流量免费；并非所有存储类别都适用，也不是无条件无上限免费。需先确认用户账户是否已启用及其他项目占用，不自动开通可能涉及计费的服务。

[Workers 静态资源限制](https://developers.cloudflare.com/workers/platform/limits/) 的单文件上限为 25 MiB，因此不能假设最终 APK 可直接作为静态文件上传。候选 APK 存储为独立 R2 Standard；若账户未启用且不符合用户免费约束，先沟通其他免费分发方式，不擅自换用别的项目或开通收费产品。

下载通过 Worker 的 R2 binding 流式返回，避免将整个 APK 读入 Worker 内存；支持 Range/ETag。正式分发不直接依赖限流的 R2 开发网址，依据 [R2 公共访问文档](https://developers.cloudflare.com/r2/buckets/public-buckets/)。安装包上传使用合适的分片上传流程，不假设大文件可一次穿过所有免费入口限制。

## 3. App 侧流程提议

整体更新交互优先参考原 APK。下述检查频率、后台下载与安装时机属于待确认细节，不先实现未确认的交互变化。

1. App 启动时检查版本，设置中保留主动检查入口；同版本结果缓存，避免频繁请求。
2. 更新服务仅返回版本、说明、安装包地址/大小、最低系统版本及校验信息。
3. 自动下载是否限 Wi-Fi、检查间隔以及安装提示方式，在开发前确认。
4. 正在对局时保持记牌，提议在合适的空闲时机更新；不强制中断对局。
5. 下载完成校验文件哈希、包名、版本和签名身份，拒绝损坏文件、错误应用及降级。
6. 使用 Android 官方安装 API。符合自更新条件时可尝试免交互更新；系统要求操作时处理 `STATUS_PENDING_USER_ACTION` 并展示系统安装界面，不能承诺所有澎湃版本均可静默安装。依据 [PackageInstaller 官方文档](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))。

更新服务失效/断网时，本地记牌继续运行。更新不上传游戏截图或账本；只有版本检查与下载请求。自研应用采用独立包名和自己的持续签名身份，不覆盖参考 APK。

## 4. 发布协议与流程

拟提供只读版本接口 `GET /v1/releases/latest` 与安装包下载接口。版本清单包含协议版本、包名、versionCode/versionName、最低 Android API、发布时间、更新说明、安装包大小、SHA-256、下载地址及签名信息。

发布流程：测试通过 → 用固定签名身份构建 APK → 上传不可变版本文件 → 校验上传结果 → 最后更新 latest 指针。安装签名私钥及发布凭证不进入 Git，App 不携带部署凭证；版本清单签名在发布时完成，App 内置验证公钥。

保留最近可用版本便于排查。回退发布指针不能令 App 自动安装比当前更低的 versionCode；需要回滚代码时仍发布更高版本号。

## 5. 找到现有 Cloudflare 信息

打开 [Cloudflare 控制台](https://dash.cloudflare.com/) → 选择账户 → Workers & Pages → 现有项目。复制项目名称及访问网址即可。Workers 通常显示 `.workers.dev`，Pages 通常显示 `.pages.dev`，也可能绑定了自定义域名。参考 [Workers 控制台指南](https://developers.cloudflare.com/workers/get-started/dashboard/)。

若没有 Workers & Pages 项目，提供现有项目的访问网址与控制台中显示的服务名称即可，可能是 Cloudflare 代理的其他服务器。无需把密码或 API Token 发在聊天中。

目前插件目录搜索未返回 Cloudflare 专用连接插件；后续在设计确认后选择官方账户授权/部署工具，不据此推断 Cloudflare 不可使用。

待确认：现有服务类型/域名、账户免费资源状态、独立部署目标，以及更新提示和自动下载/安装细节。
