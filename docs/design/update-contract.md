# JPQ 更新协议补充设计 v1

当前状态（2026-10-24）：Worker、签名发布工具和 Android 客户端验证已实现；生产正式清单、原 v0.2.26 整包与跨分片 Range 已验证。客户端任务恢复修复在 BETA 中，尚未提升替换手机正式 APP。原协议约束保留，实际发布流程见 [发布规则](../development/beta-release-workflow.md)。

## 1. 与现有接口保持一致

最新清单为 `GET /v1/releases/latest`。204 为尚未发布，不当作错误；200 为清单，客户端先验证再采信；503 为暂不可用。下载地址由已验证的 versionCode 构造为本服务的 `/v1/releases/{versionCode}/download`，不接受清单中的任意外部网址。

字段固定为：schemaVersion、packageName、versionCode、versionName、minSdk、publishedAt、notes、apk（key、sizeBytes、sha256）、signature。packageName 为 `com.cyotun.jpq`；signature 是 64 字节 Ed25519 签名的标准 Base64 编码。APK 安装签名与清单 Ed25519 签名是不同职责，分别保管密钥。

## 2. 确定签名载荷，避免 JSON 序列化差异

v1 不对包含 signature 的整个 JSON 签名，也不依赖 JSON 对象顺序、空白或平台浮点格式。签名载荷按以下顺序拼接，每个字段为 UTF-8 的字符串表示，以 `十进制字节数:字节内容` 做长度前缀；载荷开头为 ASCII `JPQ-RELEASE-V1\n`。

```text
schemaVersion
packageName
versionCode
versionName
minSdk
publishedAt
notes
apk.key
apk.sizeBytes
apk.sha256
```

整数使用无前导零的十进制表示，字符串保持解码后的原字符、不做 Unicode 标准化，长度按 UTF-8 字节数（不是字符数）计算。signature 本身不进入载荷。客户端拒绝重复 JSON 字段、错误类型、未知协议版本、非法整数和不完整字段，防止不同解析器采信不同内容；v1 不接受未约定的额外字段。

发布工具和 Kotlin 客户端必须共享独立测试向量：中文说明、换行、冒号、emoji、不同 JSON 空白/字段顺序；单字节变更必须验证失败。端到端测试分别用正确公钥与错误公钥，不用“格式正确”代替“签名正确”。不在设计稿生成或假称已持有生产密钥。

## 3. 下载完整性与重试

验证通过后只接受更高 versionCode、兼容 minSdk 与固定包名。记录清单摘要/版本/大小/hash；任何分片都属于这一个版本。Worker 当前只提供 latest 对应的 APK，发布切换时旧链接可能返回 404，客户端必须重新获取清单，不能假设旧版本无限保留可下载。

恢复下载使用单段 Range；206 必须匹配 Content-Range、总大小及预期 ETag，200 则重启整包写入而非追加，416 则重新核对已下载大小。下载终了仍对整文件计算 SHA-256，不把 R2 metadata 或 ETag 当作实际文件校验完成。HEAD、304 或只有 metadata 的响应不能当作已下载。

清单上限 32 KiB；客户端 APK 初始上限与当前服务 1 GiB 一致，下载前确认实际剩余存储空间；进度按流式字节数累计。签名不符、包名/签名身份不符、版本降级或文件损坏时拒绝安装，清理该无效缓存。

## 4. 发布身份与数据迁移

首次正式发布前固定安装证书 SHA-256 和清单验证公钥，纳入 App 与发布检查。保持同一安装签名身份，后续迁移必须使用 Android 支持的签名轮换机制且先做兼容验证。开发测试包/测试公钥与正式包分开，绝不把用户参考 APK 的身份用于自研 App。

原始安装签名私钥、发布签名私钥和 Cloudflare 凭证不进 Git、不内置 App。发布顺序为 APK 测试与签名 → 上传不可变对象 → 验证对象 → 生成并签署清单 → 最后更新 latest。回滚代码也发布更高 versionCode，不自动降级安装。

安装时保留应用数据；配置 schema 升级先迁移再启用新值，失败回退旧值并提示，不删除全部设置。App 更新失败与记牌采集生命周期解耦。Cloudflare 专用存储启用须符合用户免费要求，当前没有创建存储或发布 APK。
