# 第三方来源说明

项目自身源码沿用根目录 MIT LICENSE。依赖与工具按各自许可使用，不能将本项目许可套用到它们。

| 内容 | 来源与说明 |
| --- | --- |
| Kotlin | [JetBrains/kotlin](https://github.com/JetBrains/kotlin)，Apache-2.0；通过固定构建依赖获取 |
| AndroidX Activity 与传递依赖 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，Apache-2.0 等各组件许可；保留依赖中的元数据 |
| Gradle wrapper | [Gradle](https://github.com/gradle/gradle)，Apache-2.0；由 Gradle 8.13 生成，发布包校验值固定 |
| Android SDK / AGP | [Google Android 工具](https://developer.android.com/studio)，各自 SDK 条款和开源组件声明；SDK 留在本地忽略目录 |
| Temurin JDK | [Adoptium](https://adoptium.net/)，随工具附带许可；不作为 JPQ 源码资产发布 |
| 悬浮字体 | [钉钉官方字体](https://page.dingtalk.com/wow/dingtalk/default/dingtalk/y-W5aF3_ZJwzulU0nceIl)，按官方法律声明处理；二进制不进入 Git，个人调试包与公开分发区别见 app/README.md |
| 记牌器图标 | 本项目生成的自有设计，用户选定 1 号黑金；保留原始候选和提示词 |

用户参考 APK、解密后的参考业务脚本、native 库、原模型和私人游戏画面均未纳入源码仓库。本轮不发布原软件代码或模型资产。
