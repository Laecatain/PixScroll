# 视频播放器踩坑记录

> 日期：2026-06-24
> 关联文件：VideoPlayerScreen.kt, MediaPlayerScreen.kt, VideoPlayerFactory.kt

## 问题现象

视频播放时出现：比例被拉伸、只有音频播放画面不动、点击进度条能跳帧但不连续播放。

## 坑 1：自定义 processOutputBuffer 绕过父类状态管理

**文件**: `VideoPlayerFactory.kt` — `ThermalAwareVideoRenderer`

子类化 `MediaCodecVideoRenderer` 覆写 `processOutputBuffer()`，在温控跳帧时直接调用
`codec.releaseOutputBuffer(bufferIndex, false)` 释放帧缓冲区。这绕过了父类
`MediaCodecVideoRenderer.processOutputBuffer()` 内部的位置追踪、帧调度和 EOS 处理逻辑，
导致视频渲染器内部状态机卡死——音频继续解码输出，但视频渲染器不再推进。

**教训**：
- 覆写 `processOutputBuffer` 时，跳帧必须调用父类的 `skipOutputBuffer()` 而非直接
  `releaseOutputBuffer()`，因为父类在 skip 路径中也会更新内部状态。
- 更安全的做法：不覆写 `processOutputBuffer`，温控策略仅控制播放速度即可。
- **子类化 Media3 渲染器是高风险操作**，内部状态管理复杂且文档稀少。

## 坑 2：setViewportSizeToPhysicalDisplaySize 导致比例错误

**文件**: `VideoPlayerFactory.kt` — `create()`

这个设置让 ExoPlayer 按物理屏幕分辨率选轨，导致非标准比例视频（如 21:9、4:3）
被裁剪或拉伸以匹配屏幕。

**教训**：不要干预 ExoPlayer 的默认轨道选择，除非有明确的分辨率降级需求。
ExoPlayer 默认会选择最匹配的视频轨。

## 坑 3：PlayerView 在 Compose AndroidView 中使用 SurfaceView

**文件**: `VideoPlayerScreen.kt`

`PlayerView` 默认内部使用 `SurfaceView`，它创建独立的 compositor 窗口层。
在 Compose 的 `AndroidView` 中，这个独立窗口与 Compose 渲染管线不同步，
导致视频帧无法正确渲染。

**教训**：
- Compose `AndroidView` 中播放视频，**必须用 `TextureView`**（在 View 层级内渲染）。
- 这与 `MediaPlayerScreen` 从 SurfaceView 改 TextureView 的 commit `b66edb4` 是同一类问题。
- media3-ui 1.3.1 的 `PlayerView` 没有 `setUseTextureView()` 方法，
  只能用 XML 属性 `app:surface_type="texture_view"` 或直接使用原始 `TextureView`。

## 坑 4：playWhenReady 设置时机

**文件**: `VideoPlayerScreen.kt`

`playWhenReady = true` 在 Surface 创建之前设置，ExoPlayer 立即开始播放音频，
但视频渲染器没有可用的输出 Surface → 音画不同步，视频冻结。

**教训**：
- `playWhenReady = true` 必须在 `onSurfaceTextureAvailable` 回调中设置，
  确保视频渲染器有可用的输出 Surface 后才开始播放。
- 顺序必须是：`setVideoSurface()` → `playWhenReady = true`。

## 坑 5：setVideoScalingMode 对 TextureView 无效

**文件**: `MediaPlayerScreen.kt`

`setVideoScalingMode()` 只对 `SurfaceView` 的 `SurfaceHolder` 生效，
对 `TextureView` 完全无效。TextureView 的视频缩放必须通过
`setTransform(Matrix)` 手动计算宽高比变换矩阵。

**教训**：
- TextureView 上保持宽高比的唯一方式是 `setTransform(Matrix)`。
- 计算逻辑：比较 videoAspect 和 viewAspect，取较小的缩放比，以中心点为锚点缩放。

## 坑 6：PowerShell .Replace() 破坏 UTF-8 BOM

调试过程中多次用 PowerShell 的 `[string].Replace()` 修改 .kt 文件后写回，
每次都会导致文件头部的 UTF-8 BOM (`EF BB BF`) 被重复追加，
Kotlin 编译器报 `Syntax error: Expecting a top level declaration`。

**教训**：
- PowerShell 的字符串操作 + `Set-Content`/`WriteAllText` 组合容易破坏 BOM。
- 改用 Python (`open(path, 'r', encoding='utf-8-sig')`) 更可靠。
- 或者用 `apply_patch` 工具直接编辑文件。
