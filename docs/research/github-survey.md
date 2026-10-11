# GitHub 类似项目与技术来源

调查日期：2026-10-11。本次以项目作者 README、仓库文件和 Android/OpenCV 官方文档为主要来源。没有采用下载站关于“全平台准确率”的宣传作验证依据。

## 1. 直接相关项目

| 项目 | 核实到的内容 | 对 JPQ 的用途与限制 |
| --- | --- | --- |
| [yangdayangda/cardRecorder](https://github.com/yangdayangda/cardRecorder) | 安卓欢乐斗地主记牌器；悬浮窗/录屏授权；初始扣自己手牌；OpenCV 模板匹配；默认每约 1.5 秒检查；作者说明部分尺寸可能出错 | 最直接的操作和统计流程参考；不是腾讯大掼蛋适配；相同字符比较去重需要改进。已查看根目录，未见明确许可证文件，不直接复制源码 |
| [budaLi/fast_dou_zero](https://github.com/budaLi/fast_dou_zero/blob/main/README.md) | 斗地主牌面识别，YOLOv5；README 记录 Android NCNN 推理；记牌器仍列在预计开发功能中 | 检测路线参考；不能视为现成可用的掼蛋 APK；复用任何代码/权重前需检查对应文件来源 |
| [AltmanD/Guandan](https://github.com/AltmanD/Guandan) | 掼蛋规则/模拟引擎，包含贡牌等阶段接口；未提供腾讯手机屏幕识别流程 | 规则用例和状态分层参考，不能直接替代游戏适配。README 写 MIT，但实际 [LICENSE](https://github.com/AltmanD/Guandan/blob/main/LICENSE) 为 Apache-2.0；规则说明需再对照腾讯实际玩法 |
| [nihui/ncnn-android-nanodet](https://github.com/nihui/ncnn-android-nanodet) | Android NCNN 轻量目标检测示例 | 本地推理集成参考；示例模型不是扑克牌模型。示例自身许可需在复用文件前核实 |

本次未确认一个可以直接用于用户这两部手机、当前腾讯大掼蛋版本、并满足全部验收要求的现成开源工程。项目数量与 Star 数不能替代整局测试。

## 2. 候选基础组件

- [Tencent/ncnn](https://github.com/Tencent/ncnn)：移动端本地推理，支持 CPU/Vulkan；实际 [LICENSE](https://github.com/Tencent/ncnn/blob/master/LICENSE.txt) 为 BSD-3-Clause，并列出第三方组件。拟作为轻量检测/分类模型候选运行时。
- [OpenCV 模板匹配官方文档](https://docs.opencv.org/4.x/d4/dc6/tutorial_py_template_matching.html)：固定牌面和已归一化区域的匹配候选；旋转、缩放、皮肤和重叠变化需要验证。当前 4.x [LICENSE](https://github.com/opencv/opencv/blob/4.x/LICENSE) 为 Apache-2.0。
- [NanoDet](https://github.com/RangiLyu/nanodet)：轻量检测候选，实际 [LICENSE](https://github.com/RangiLyu/nanodet/blob/main/LICENSE) 为 Apache-2.0；框架许可与训练数据/模型权重来源分别核查。

候选组件尚未加入依赖或被构建。进入开发后固定版本/提交，保留实际使用的第三方声明；源码仓库现有 MIT LICENSE 保持不变。

## 3. Android 官方约束

依据 [Media projection 官方文档](https://developer.android.com/media/grow/media-projection)：每个新的采集会话需要用户授权；Android 14+ 不可复用同一授权 Intent 或重复创建同一 token 的虚拟显示；尺寸变化使用回调调整；采集结束必须释放资源。文档也明确 Android 15 QPR1+ 的锁屏自动停止行为。

依据 [前台服务类型文档](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection)：采集应声明 mediaProjection 类型及相关权限，并按平台顺序启动。

依据 [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE)：Android 12+ 对穿过悬浮窗口的触摸有透明度和窗口归属限制。“不可触摸”标记不能单独保证游戏底层收到触摸，因此悬浮窗边界与透明度需要真机验收。

以上来源用于实现与兼容设计，不保证澎湃系统的后台菜单路径或游戏当前支持屏幕采集，这些必须实际测试。
