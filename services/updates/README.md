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

当前未创建 R2，也未绑定其他项目存储，因此没有发布版本或自研 APK。将来只绑定独立 bucket 为 `JPQ_RELEASES`，latest 清单位于 `releases/stable/latest.json`，APK key 固定为 `releases/{versionCode}/jpq-{sha256}.apk`，对象 metadata 必须有匹配的 sha256。

清单采用 schemaVersion 1，包含 packageName、versionCode、versionName、minSdk、publishedAt、notes、apk（key/sizeBytes/sha256）和 Ed25519 signature。App 在集成时需确定规范化签名载荷并验证签名、APK 哈希及安装签名；服务端的格式校验不能代替客户端真实性校验。

安装签名私钥、发布凭证和参考 APK 研究材料不进入此服务或 Git。当前包名 `com.cyotun.jpq` 是自研应用的拟定独立身份，开发时确认后固定。
