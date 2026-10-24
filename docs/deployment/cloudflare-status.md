# JPQ Cloudflare 部署与验证记录

2026-10-24 更新：`jpq-release-store` 已部署原已验收 v0.2.26 的签名清单和四个 Static Assets 分片。生产 latest 从 204 变为 200；整包、签名与跨片 Range 验证通过，未发布 BETA 功能。没有修改其他项目或启用 R2。详见 [本次更新服务记录](../development/official-updates-20261024.md)。以下为 2026-10-11 初次部署时的历史状态。

日期：2026-10-11（Asia/Shanghai）。用户已明确授权查看配置并直接修正部署问题。

## 实际配置

| 项目 | 状态 |
| --- | --- |
| 服务类型 | Cloudflare Workers |
| 项目 | `jpq`，独立连接 `cyotun/JPQ` |
| 账户套餐 | Workers Free，控制台显示 Current plan |
| 生产分支 / 根目录 | main / `/` |
| 构建检查 | `npm run check && npm test` |
| 部署命令 | `npx wrangler deploy` |
| 生产域名 | https://jpq.240438457.workers.dev/，已启用 |
| 预览域名 | 关闭 |
| 自定义域名/其他项目路由 | 无；未修改其他项目 |
| 存储绑定 | 无；未创建/开通 R2，未修改其他项目存储 |

## 修复与证据

原 Git 构建失败，错误为无法识别静态文件目录；当时仓库缺少 Worker 入口和 Wrangler 配置。补齐专用更新服务、锁定工具版本并设置构建检查后，main 自动构建与部署成功。

- 首次成功上线代码提交：`f82d5fc81df13be03ad1ec424d7626a3da51d6c6`。
- 对应 Cloudflare 构建：`0d609f2a-af31-4c41-9dde-2319325f1625`，控制台显示 Ready。
- 对应活跃 Worker 版本短 ID：`6ebb73a3`，100% 流量。
- 本地 7 项功能测试、语法检查、Wrangler dry-run 均通过。

## 外部 HTTP 验证

| 请求 | 实际响应 |
| --- | --- |
| `GET /` | 200，显示 JPQ 更新服务运行中及未发布说明 |
| `GET /health` | 200，`service=jpq-updates`、`status=ok`、`schemaVersion=1` |
| `GET /v1/releases/latest` | 204，空响应体，明确尚无发布版本 |

以上请求通过无登录凭证的外部 HTTPS 访问验证。控制台刷新后确认生产域名开关开启，预览域名仍关闭。

## 当前完成范围

部署验证时已修复更新服务并验证公网接口，Android App 当时尚未开发。随后用户确认设计并已构建基础开发 APK，见 [开发记录](../development/foundation.md)；识别、R2 下载与手机安装链路仍未验证，未发布正式安装包。没有上传参考 APK、原解密脚本、签名密钥或游戏截图。

配置与服务源码由本仓库维护，后续仍只针对 `jpq` 部署。账户内其他项目的域名、数据库、存储绑定和套餐未被修改。
