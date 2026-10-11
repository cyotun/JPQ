# 记牌器 · 应用图标

2026-10-11。使用内置 imagegen 图片生成工具制作四个独立候选；用户已选定 **1 号黑金牌面**。应用名称为「记牌器」，图标内无需再放完整名称。

选定图稿保存为 [launcher-selected.png](launcher-selected.png)。基础开发版已将原图作为 Android 自适应图标的前景来源，使用代码定义的边距和背景，并添加单色图标；没有修改生成源图。资源已构建进开发 APK，启动器不同遮罩的实际效果仍需真机检查，见 [开发记录](../../development/foundation.md)。

![选定黑金图标](launcher-selected.png)

| 候选 | 图稿 | 方向 |
| --- | --- | --- |
| 1（已选择） | [黑金牌面](candidate-1-black-gold.png) | 两张金牌、黑桃和计数条；与黑金悬浮窗统一 |
| 2 | [米白红黑](candidate-2-ivory.png) | 红心、黑桃和计数短线，浅色底 |
| 3 | [墨绿金牌](candidate-3-emerald.png) | 扇形牌面、金色点与墨绿底 |
| 4 | [金色记字](candidate-4-ji.png) | 「记」字、牌框与黑桃 |

完整生成提示词见 [prompts.json](prompts.json)。所有候选均保存原始生成文件，本轮未裁切参考软件图标、未覆盖生成源文件。
