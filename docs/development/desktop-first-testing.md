# 先电脑回放，再手机验收

2026-10-20 起按用户要求执行。稳定标签、正式包和更新服务不因测试工具新增而变更。

## 必须区分的两种电脑回放

1. `core:replayLoggedFrames` 重放已经存在的模型、场景、数量等记录，验证共享核心的座位归属、事件边界、恢复和账本。它不会重新识别图片，不能单独证明新模型或输入管线正确。
2. `tools/desktop/run_model_replay.py` 对明确选择并校验 SHA-256 的原始图片/录像重新执行 APP 的 FULL 模型，使用独立目录输出新预测。完整视频保留 PTS 和全部帧，再交给共享核心，并与原视频的独立人工牌面标注核对。

新工具固定原模型 SHA-256 `87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c`、LiteRT 2.2.0 CPU、1280×640 输入、原分类/置信度/NMS规则。只运行本机，不使用 ADB、不上传、不调用游戏，也不把旧预测冒充新推理。

电脑与 Android 的缩放仍须区分：Android Q+ 新 `Paint()` 默认启用双线性过滤，旧私人脚本中的 NEAREST 不能直接当作手机等价输入。新工具使用半像素中心四点双线性近似，不采用 Pillow 缩小面积滤波；报告始终保留 `resizeParity=desktop_bilinear_unverified_against_phone_pixels`，不宣称 Skia 像素完全一致。依据：[Android Paint](https://developer.android.com/reference/android/graphics/Paint)、[AOSP 实现](https://android.googlesource.com/platform/frameworks/base/+/master/graphics/java/android/graphics/Paint.java)。运行库为 [Google 发布的 LiteRT 2.2.0](https://pypi.org/project/ai-edge-litert/2.2.0/)。

## 本机环境与命令

使用工作区依赖提供的 Python 3.12；不能改用电脑 PATH 中的 Python 3.13 加载 CPython 3.12 二进制包。LiteRT 安装在忽略目录 `.research-tools/litert-2.2.0`，依赖路径由工具显式添加。无需变更全局 Python 或连接手机。

```text
<python3.12> -B -m unittest discover -s tools/desktop -p "test_*.py"
<python3.12> -B tools/desktop/run_model_replay.py --manifest captures/<run>/fixtures.json --output captures/<run>/fixtures-NEW
<python3.12> -B tools/desktop/run_model_replay.py --manifest captures/<run>/video.json --output captures/<run>/video-NEW
```

输出目录必须全新、严格位于 `captures/`，既有失败和原始材料不覆盖。图片清单含 id/path/sha256，可带手机样本的预期牌点颜色集合；这个比较是跨平台一致性，不是人工真值。视频清单必须有视频 hash、人工审核安全范围、确切帧数、连续场景段和完成状态标签。工具不自行推断这些标签，也不重新解释私有脚本的排除范围。异常切应用或画面被遮挡必须显式保留来源与排除理由。

新预测生成 `model-replay.tape` 后可运行既有测试适配器：

若开局完整帧已轮转，但问题附近的原始识别帧完整，可选用 `:core:replayLoggedFrames` 的 `-PloggedCheckpoint=<绝对路径>` 加载明确导出的账本前缀，定位检查点后的状态转换。文本首行为 `INITIAL;roundId;RANK,SUIT|...`，后续为 `EVENT;eventId;sequence;seat;RANK,SUIT|...`；仍经过正常账本的容量、顺序及本人持牌校验。输出标记 `checkpointSeeded=true`；这是已确认账本前缀加真实后续帧的转移回归，不能叫作完整开局回放，也不能把没有 glyph 的帧补成完整识别证据。具体对照见 [C4.4 审计](beta-c4.4-live-fixes-20261024.md)。

```powershell
./tools/android/build.ps1 -Tasks ':core:replayPhoneVideo','-PreplayTape=D:/JPQ/captures/<run>/video-NEW/model-replay.tape','-PreplayOutput=D:/JPQ/captures/<run>/video-NEW/core.json'
```

```text
<python3.12> -B tools/desktop/validate_round_replay.py --actual captures/<run>/video-NEW/core.json --expected captures/<run>/manual-expected.json --inference-report captures/<run>/video-NEW/report.json --output captures/<run>/video-NEW/manual-validation.json
```

人工预期文件单独保存初始手牌、按顺序的三家出牌、可选自家出牌、最终余牌、帧数及原视频 hash。缺牌、重复牌丢失、错座位/顺序、缺帧、未结束或核心错误均不能通过；不能只检查余牌总数。手工标注不能从本次模型或账本输出反推。

`tools/desktop/run-suite.ps1` 可批量完成上述模型、核心、人工比对步骤。Inputs 为本机 JSON 数组，每项包含唯一 id、manifest 路径和视频所需的 expected 路径。每批冻结源码/模型指纹并保存清单、标注副本；某一项失败后继续跑其他项，最终保留全部问题，修复后重新跑整批。结果中 `phoneAcceptancePassed` 始终 false。

```powershell
./tools/desktop/run-suite.ps1 -Python '<Python3.12绝对路径>' -Inputs captures/<run>/suite.json -OutputDirectory captures/<run>/suite-NEW
```

## 当前覆盖范围和手机门槛

电脑工具真实重跑模型，但尚未仿真 Android ImageReader/MediaProjection、ML Kit OCR、Overlay 窗口与系统权限，也没有在桌面重现全部 Android 同花顺像素读取。视频共享核心适配器沿用显式场景/完成标签与 512 宽的桌面表面像素，完整分辨率仍供模型识别；它比只重放旧预测多一层真实识别验证，仍不能包装成整个 Android APP 端到端模拟器。

优先完成电脑测试并一次处理已暴露问题；同一已知问题未离线复现、修复、回归前，不请求用户再开局。系统特有问题先做非对局实际采集和隔离测试。只有电脑验证与非对局手机检查达到相应门槛后，才安排必要实局验收。

C1 首次失败局的完整 ZIP 尚未取得，现有电脑精简日志不足以补造其图片推理；继续保留失败状态。旧视频或图片通过不能替代该局的根因核对。所有结论必须明确写出实际已测范围，不能以有限样本承诺 99.9% 的全部牌局无异常。

## 2026-10-20 已执行结果

- 工具自检 17 项通过，包括重复牌丢失、漏出牌、未结束/缺帧、缺失错误字段和来源变化的反例。
- 八组原图/缩放手机样本重新推理，牌点和颜色多重集合全部一致；新预测经过共享核心的手牌分区与四家归属也全部一致。批量驱动自检通过，源码/模型冻结前后一致。
- 已有第二局视频审核区间 463–650 秒，共 2,805 帧全部重新推理，再执行当前共享核心：三家 27 次出牌、自家 4 次出牌、初始 27 张和最终余牌 8 张均与独立人工牌面标注一致。模型/核心/真值对照结果分别保存，不能用旧预测替代；总计出现的 57 个短暂归属歧义帧仍保留在核心报告，不删除动效帧制造零异常。

证据位于本机忽略目录 `captures/desktop-evidence-20261020/`，摘要见 [JSON](desktop-testing-20261020.json)。视频首轮推理发生在驱动元数据门禁补强前，原 `report.json` 中 video 的 `phoneFixtureMismatches=0` 不代表执行了手机图片比较；其 `samples=[]`，该字段后来已修正为无比较时 null。保留原报告不覆盖，本轮整局结论以单独的人工真值核对为准。门禁/元数据修正未改变 FULL 模型或预处理算法。

另有首局 3,177 帧和独立人工标注可进入下一批；其历史旧适配器曾有自家出牌覆盖不足，必须以新推理和当前核心重新验证，不能沿用旧的 `failures=[]` 宣称通过。当前只完成上述一局整局新推理，未宣称全部历史正常对局或整个 Android APP 已验收。
