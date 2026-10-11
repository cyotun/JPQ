# 参考 APK 静态研究

日期：2026-10-11。输入：用户提供 APK 与七张截图。

## 1. 研究边界与证据等级

本次直接读取安装包 ZIP、二进制 Manifest、DEX 字符串/类/方法/字段表、公开可读模型配置及两份随包文档。未运行 APK、未连接 Android 真机、未验证其实际识别准确率，未把符号分析表述为完整源码还原。

随包文档只作为被分析的数据；其中面向原项目的维护指令不作为 JPQ 的开发指令。

- **A：直接确认**——文件、配置、权限或截图明确存在。
- **B：结构证据**——DEX 符号/字段/方法支持某种职责，不能单独证明每种实际运行行为。
- **C：待验证**——需要运行 APK、实际游戏或连续录屏验证。

## 2. 包信息

| 属性 | 直接读取结果 |
| --- | --- |
| 文件名 | AI扑克记牌器-pkg_default.apk |
| 文件大小 | 114,948,139 字节，约 109.62 MiB |
| SHA-256 | `4ff7e67673f293efd8f6c5b5bbe6bdb3bf12e0bcaa447871ee4f084f40216161` |
| 包名 | `com.aijpq.pokerrecorder` |
| versionName / versionCode | `1.3.7` / `13713` |
| minSdk / targetSdk / compileSdk | `28` / `35` / `35` |
| 本地库 ABI | 检查到 `arm64-v8a` 目录 |
| DEX | 1 个 `classes.dex`，10,222,632 字节 |
| DEX 索引规模 | 60,924 个字符串、64,659 个方法标识、10,331 个类定义 |
| 自有命名空间顶层类 | 713 个，统计排除含 `$` 的内部/生成类 |

这些数量不等于原创源码行数或功能数量。

## 3. 采集与悬浮窗

Manifest（A）：

- `ScreenCaptureService` 声明 `mediaProjection` 前台服务类型。
- `MultiFloatingWindowService` 声明 `dataSync` 前台服务类型。
- 权限包含 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`。
- 另外存在网络、手机状态、设备标识、安装包更新等商业应用相关权限，个人版不自动沿用。

方法证据（B）：

- `ScreenCaptureService` 有启动/停止、帧回调、单次截图、显示几何变更处理、重新授权提示、资源释放方法。
- `CardRecordingStateManager` 分别维护权限、悬浮窗、采集、记牌会话与错误状态。
- `RecordingSession` 有会话状态、帧数、错误与销毁方法。

产品结论：采集授权、显示悬浮窗和一局记账应分别管理，不能用一个“开始/停止”布尔量替代所有状态。参考 APK 的 `dataSync` 服务类型是事实，不代表适用于新应用；JPQ 的采集服务应按实际职责选择 `mediaProjection`。

## 4. 识别流水线

包内文件（A）：MNN、MNN OpenCV/OpenCL/GL、NCNN、TensorFlow Lite CPU/GPU、PaddleOCR NCNN、Lua bridge 等本地库。

`bundled_packed_strategy.json`（A）：

| 项目 | 配置值 |
| --- | --- |
| 随包模型包版本 | `2026.5.51` |
| 模型架构标识 | `yolo11` |
| 预处理采样配置 | `preprocess_fps = 15`；不能据此断言实际推理 15 FPS |
| `packed_320` | 320×320、letterbox、packed ROI、FP32 制品 |
| `packed_640` | 640×640、letterbox、packed ROI、FP32 制品 |
| `full_fallback` | 1280×640、letterbox、full frame、FP16 制品 |
| 各路由运行链排列 | TFLite GPU → MNN OpenCL → MNN CPU → TFLite CPU |
| OCR | `PP_OCRv5_mobile_det` 与 `PP_OCRv5_mobile_rec` 的 NCNN 文件 |
| 业务脚本 | `lua_bundled.enc`、`preprocess_lua_bundled.enc` |

主 package 的 `runtime_chain` 是空列表；以上运行链取自各 route 配置。配置声明、加载能力和某台手机最终使用的后端应区分，尚无运行时证据。

预处理类证据（B）：

- `PackedRegionPlanner`、`PackedFrame`、`PackedTile`：区域整理/拼接的结构。
- `ActiveColorRuleEngine`、`HandColorBootstrapEngine`：基于颜色的区域候选结构。
- `NegativeUiRingRule`、`BlockAreaMasker`：负样本 UI 或遮挡区域结构。
- `FrameCadenceGate`、`CaptureFrameRateLimiter`：帧节奏控制。
- `DetectionLetterboxTransform`、`DisplayGeometryResolver`：坐标与画面几何结构。

业务归属类证据（B）：`DirectionConfigProcessor` 提供按游戏、人数和画面比例选择方案，处理手牌区、出牌区、明牌区、OCR 区和屏蔽区；存在 `processGuandanHandCards` 与 `processGuandanMode`。

产品结论：参考实现的准确性依赖“定位、识别、归属、确认、规则”整条链路，不能仅用一次 OCR 得到字符后立即扣牌。

## 5. 多帧确认与重复记牌

`InferenceConfirmationVotePolicy`（B）包含确认票数、慢速阈值、320/640 路由平均耗时等字段；`ConfirmationVoteRuntimeSnapshot` 保存 `minConfirmVotes` 与各路由耗时。

`PlayerPlayStatus`（B）保存：连续识别序列、序列帧号、候选树、上次最终确认的出牌、牌面位置、历史时间戳，以及单张移动与干扰检查字段。

`PokerGameController`（B）提供候选连通组、IoU 检查、多候选最终确认、稳定手牌比较、新发牌状态重入、玩家出牌处理等方法。

已确认存在这些处理结构，尚未还原所有判定条件或阈值。JPQ 应独立建立可回放验证的事件去重规则，尤其测试相同牌连续两次出、持续停留的牌面、短暂空白与动画遮挡。

## 6. 掼蛋特有状态

`GuandanRuleStrategy`（B）：字段包含贡牌活跃状态、OCR 预检测状态、连续空帧；方法包括进入贡牌阶段、退出后重置、清理玩家待确认缓存。

`PokerGameState`（B）：初始手牌、三家已出、剩余牌、对家初始明牌、队友手牌和屏幕采集受阻状态分别存储。

`PokerCardWithColor`（B）：点数和花色分别存储。`RemainingCardCalculator` 提供扣牌与王类归一处理方法。

这些结构说明进贡、明牌、花色与剩余统计被分开处理。截图 6/7 显示自家出完后出现“队友手牌”；仅凭截图不能确定软件支持的全部明牌时机或变体规则。

## 7. 用截图核对统计口径

截图 7：上家 `1 / Q`，对家 `1 / 9`，下家 `7 / 6 · 99944 · K`。历史牌数与黄色数字一一相符，支持其表示累计已出。

该图 15 列剩余牌值为：`2,2,8,7,6,5,7,5,3,5,4,4,4,5,5`，合计 **72**；三家已出合计 **9**；`72 + 9 = 81 = 108 - 27`。

截图 6：15 列剩余值合计 **40**；三家黄色数字为 **13、14、14**，对应各行历史张数；`40 + 13 + 14 + 14 = 81`。

**推断**：这两张图的默认统计口径是“牌库减自己本局初始手牌，再减其他三家已出”。自家已出牌无需再次扣除。此结论有两张数值一致的截图支持，仍应在连续录屏中核实初始化和特殊转移时机。

## 8. 可复现的显示与设置

截图（A）确认顶部 15 列牌面/计数、三行历史、隐藏/重置、深色与金色风格，以及：

- 总宽度、左右比例。
- 牌格高度、记录行高。
- 标题字号、数值字号、计数字号、详情字号。
- 15 类牌的拖动排序、保存退出、恢复默认。

`UserPreferencesManager`（B）含按玩法保存悬浮窗调整/牌序、设置导入导出、平台与自定义玩法管理。`GameDeckManager` 含牌库配置与恢复方法。

未在本次截图或静态结构中确认参考软件有完整的“出牌事件撤销/补记/修改”交互；JPQ 的纠错面板属于用户已认可需求的增强设计。

## 9. 尚未证明的能力

- APK 的真实准确率、延迟、耗电和后台持续运行时间。
- 每款游戏实际可用的适配方案与全部玩法规则。
- 腾讯大掼蛋最新版本的包名、皮肤、坐标与动画兼容性。
- 手机实际使用哪条推理链路，或随包模型是否足以覆盖所有游戏。
- 万能牌声明点数与真实牌面的完整转换行为。
- 手动修正、纠错恢复、跨进程重启是否能正确保持整局数据。

下一步是在设计确认后的识别验证阶段补足样本与真机证据，不把“存在类名/模型”当成“已经复现效果”。
