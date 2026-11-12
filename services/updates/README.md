# JPQ 更新服务

当前正式版 **0.2.27 / code 29**，签名清单和整包生产下载已验证；见 [发布记录](../../docs/releases/0.2.27.md)。服务只用于 APP 更新，不上传任何对局数据。

`jpq` 是公开入口，`jpq-release-store` 是独立、无公开路由的 Static Assets 存储，通过 `RELEASE_STORE` service binding 读取。没有启用 R2，也不复用其他项目。APK 按 20 MiB 分片，清单和索引作为同一 Worker 版本切换。代码中的 `JPQ_RELEASES` R2 适配当前未使用。

| 路径 | 行为 |
| --- | --- |
| `/`、`/health` | 状态与健康检查 |
| `/v1/releases/latest` | 当前签名清单；没有有效版本时 204 |
| `/v1/releases/{versionCode}/download` | 仅当前版本可下载；流式整包、单段 Range、HEAD |

仅 GET/HEAD；不公开静态资产路径或提供写入。客户端验证 Ed25519 清单、大小/哈希、包名、版本、SDK 和安装证书。正式版 `com.cyotun.jpq` 沿用同一身份；BETA `com.cyotun.jpq.candidate` 禁用正式更新，打包脚本拒绝 BETA 包。

## 本地检查与发布

从仓库根目录执行；Node 22+，锁定 Wrangler 版本。不得把 dry-run 当发布。

```text
npm ci
npm test
npm run check
npm run build
node tools/release/test-assets-integration.mjs
node tools/release/package.mjs <ASCII路径的已验证正式APK> <发布metadata.json>
node tools/release/verify-release.mjs --apk <APK> --manifest services/updates/release-assets/latest.json --report <本地报告.json>
```

metadata 字段为 packageName、versionCode、versionName、minSdk、publishedAt、notes。签名身份在本机 `.signing/`，不能生成新身份代替；安装包和发布资产不进入 Git。使用 ASCII 文件路径避免 Windows 上旧 aapt 的路径限制，应用显示名仍为「记牌器」。

发布只针对专用存储，先暂存、最后切流：

```text
node node_modules/wrangler/bin/wrangler.js versions upload --config wrangler.release.jsonc --tag <版本> --message <发布说明>
node node_modules/wrangler/bin/wrangler.js versions deploy <新版本ID>@100 --config wrangler.release.jsonc --yes
node tools/release/verify-release.mjs --apk <APK> --manifest <已冻结清单.json> --base https://jpq.240438457.workers.dev --report <生产验证.json>
```

上传后、切流前核实生产 latest 仍是旧版本；切换后验证新清单、整包、HEAD、跨片 Range、后缀 Range 和私有路径。若改变入口 Worker 代码或绑定，再单独 `npm run deploy`；常规 APK 发布不用修改其他项目。

正式 APP 设置一级菜单的更新行右侧按钮执行检查/下载/安装；安装由系统确认，对局期间不发起更新安装。网络分发与真实手机安装是两种证据，本轮新增手机安装由用户豁免，不写为通过。

## 回退

保留上一 Worker 版本、原 APK、清单、哈希、证书和源码标签。必要时切回上一存储版本止住问题分发；这不会降级已安装客户端。已安装客户端的修复应使用旧稳定源码、同证书和更高 versionCode 重新发布。当前与上一版本 ID 见 [部署状态](../../docs/deployment/cloudflare-status.md)。
