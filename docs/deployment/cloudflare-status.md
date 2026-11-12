# Cloudflare 当前部署 · 2026-11-12

正式更新 **v0.2.27 / versionCode 29** 已上线。安装包身份和本轮真机/实战豁免见 [发布记录](../releases/0.2.27.md)。

| 组件 | 当前配置 |
| --- | --- |
| 公共入口 | `jpq`，`https://jpq.240438457.workers.dev` |
| 前端源码 / 配置 | `services/updates/src/worker.mjs` / `wrangler.jsonc` |
| 私有发布存储 | `jpq-release-store`，仅 `RELEASE_STORE` service binding 可达，无公开路由 |
| 存储源码 / 配置 | `services/updates/src/release-store.mjs` / `wrangler.release.jsonc` |
| APK 存储 | Workers Static Assets，20 MiB 分片；未启用 R2 |
| 当前存储版本 | `0aee6e9b-fc4b-4703-b34d-1aa2cb556bb6`，100% |
| 前一存储版本 | `98aece11-1003-40fe-ada2-374278b87a77`，v0.2.26 |
| 更新清单 | `/v1/releases/latest`，schema 1，Ed25519 签名 |
| 当前下载 | `/v1/releases/29/download`，HEAD / 单段 Range / 整包 |
| Git 仓库 / 主分支 | `cyotun/JPQ` / `main`；Git 构建不等于发布 APK |

先 `versions upload` 暂存安装包分片及清单；确认线上仍为 code 28，再 `versions deploy <id>@100` 切换。2026-11-12 00:17:24 UTC 已验证 code 29 的清单签名、整包 73,361,414 字节及 SHA-256、HEAD、跨片 Range、尾部 Range，直接 `/latest.json` 返回 404。

服务仅允许公开 GET/HEAD，不接收诊断 ZIP、图片、录像或手牌；不提供上传接口。没有改动其他 Cloudflare 项目、域名、路由或存储。安装仍由用户在 Android 系统中确认；本轮按用户要求未执行新的手机安装。

部署与回退见 [服务说明](../../services/updates/README.md)。旧正式包恢复的 [2026-10-24 记录](../development/official-updates-20261024.md) 保留为历史，不代表当前 latest。
