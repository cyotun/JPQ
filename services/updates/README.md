# JPQ 更新服务

部署目标只为 Cloudflare Worker `jpq`。仓库根目录的 Wrangler 配置与现有 Cloudflare Git 构建根目录 `/` 对应，不修改其他项目的域名、存储或部署。

```text
npm ci
npm test
npm run build
npm run deploy
```

部署在 Cloudflare 已连接的 Git 构建环境完成；本地 dry-run 不需要账户凭证。生产分支为 main。

## 当前接口

| 路径 | 行为 |
| --- | --- |
| `/` | 服务状态说明 |
| `/health` | JSON 健康检查 |
| `/v1/releases/latest` | 未发布时 HTTP 204；存在有效签名清单时返回原字段 |
| `/v1/releases/{versionCode}/download` | 仅当前版本可下载，APK 从专用 R2 流式返回；支持单段 Range 和 HEAD |

公开接口仅允许 GET/HEAD，不提供上传、账户操作或任意存储对象访问。

2026-10-24 已发布原已验收正式 v0.2.26（versionCode 28），未发布 BETA 功能。当前使用专用 `jpq-release-store` 的 Workers Static Assets，通过 `jpq` 的 `RELEASE_STORE` service binding 提供只读分片下载；没有创建 R2 或复用其他项目的存储。APK 按 20 MiB 分片，服务重组和跨片 Range 已通过实际生产下载验证。`JPQ_RELEASES` 仍是可选的 R2 适配，不是本次实际部署。

清单采用 schemaVersion 1，包含 packageName、versionCode、versionName、minSdk、publishedAt、notes、apk（key/sizeBytes/sha256）和 Ed25519 signature。App 在集成时需确定规范化签名载荷并验证签名、APK 哈希及安装签名；服务端的格式校验不能代替客户端真实性校验。

安装签名私钥、发布凭证和参考 APK 研究材料不进入此服务或 Git。正式身份固定为 `com.cyotun.jpq`（记牌器）；BETA 身份为 `com.cyotun.jpq.candidate`（记牌器BETA），不能发布到此正式通道。签名验证和发布工具已实现，详见 [更新验证](../../docs/development/official-updates-20261024.md) 与 [发布规则](../../AGENTS.md)。
