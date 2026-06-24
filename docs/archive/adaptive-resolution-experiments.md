# 自适应分辨率实验记录

日期：2026-06-15

## 问题背景

播放 2560×1440 @ 60fps（QHD）视频时，丢帧率持续 85-90%，实际渲染 fps 仅 6-14fps（目标 59fps）。vivo 原装播放器能流畅播放同一视频。

设备：vivo V2111A，SoC Qualcomm，解码器 `c2.qti.avc.decoder`（硬件加速）

## 实验 1：setFixedSize 改 Surface buffer 尺寸

**方法**：在 SurfaceView 的 SurfaceHolder 上调用 `setFixedSize(1280, 720)`，试图让解码器输出到更小的 Surface buffer。

**结果**：❌ 无效

**日志**：
```
resolution_downscale: 2560x1440 → 1280x720 (drop_rate=86%)
# 降分辨率后：
frame_drop: 263/298 frames dropped in 5s window (88%) | avg_fps=12 target_fps=59
frame_drop: 263/300 frames dropped in 5s window (87%) | avg_fps=11 target_fps=59
```

**原因**：`setFixedSize` 只改变 Surface 的 buffer 分配尺寸，但 MediaCodec 在 `configure()` 时已按原始分辨率（2560×1440）初始化了内部缓冲区。Surface 后续的尺寸变化不会让解码器重新配置。解码器仍然以原始分辨率解码，输出帧再由 SurfaceFlinger 缩放到 Surface buffer 尺寸——解码负载不变。

## 实验 2：MediaFormat KEY_WIDTH/KEY_HEIGHT 降分辨率

**方法**：在 `MediaCodecAdapter.Factory.createAdapter()` 中，修改 `MediaFormat` 的 `KEY_WIDTH` 和 `KEY_HEIGHT` 为目标分辨率（1280×720），试图让 codec 在 `configure()` 时就以低分辨率初始化。

**代码位置**：`VideoPlayerFactory.kt` codec adapter factory

**结果**：❌ 无效

**日志**：
```
CONFIGURE: codec=c2.qti.avc.decoder output_resolution=1280x720
operating-rate: original=0.0, safe=60.0
# configure 成功，没有异常，但：
decoder_analytics: codec=avc1.4D4032 resolution=2560x1440 ...  ← 解码器仍输出原始分辨率
```

**原因**：Qualcomm Codec2 HAL（`c2.qti.avc.decoder`）在 `configure()` 时接受 `KEY_WIDTH`/`KEY_HEIGHT`，但**忽略**这些值用于输出分辨率控制。解码器根据视频流的 SPS/PPS 确定实际解码分辨率，MediaFormat 中的 width/height 仅用于分配 buffer 的参考，不影响实际解码行为。`configure()` 不报错，但输出分辨率不变。

## 实验 3：seek 触发 codec reconfigure

**方法**：设置新的目标分辨率后，通过 `exoPlayer.seekTo(currentPosition)` 触发 codec flush + reconfigure，试图让新分辨率在 reconfigure 时生效。

**结果**：❌ 无效（依赖实验 2 的 MediaFormat 修改，而实验 2 本身无效）

## 实验 4：KEY_OUTPUT_RESOLUTION（API 34+）

**方法**：尝试使用 `MediaFormat.KEY_OUTPUT_RESOLUTION`（API 34 新增的 `android._output-resolution` key），传入 `Size(1280, 720)`。

**结果**：❌ 编译失败，`MediaFormat.setParcelable()` 方法在 compileSdk 34 下不可用。且该 key 为 vendor 扩展，不一定在所有设备上支持。

## 核心结论

**MediaCodec 硬件解码器不支持通过标准 API 降分辨率解码。**

- `MediaFormat.KEY_WIDTH`/`KEY_HEIGHT`：codec 接受但忽略，不影响输出分辨率
- `SurfaceHolder.setFixedSize()`：只影响 Surface buffer 分配，codec 已初始化后无效
- `KEY_OUTPUT_RESOLUTION`：API 34+ vendor 扩展，不可靠
- 没有 `MediaCodec.setDecodeResolution()` 这样的 API

硬件解码器根据视频流的 SPS/PPS NAL 单元确定解码分辨率，这是 codec 固件层面的行为，应用层无法干预。

## 可行的替代方案

### 方案 A：性能自适应跳帧（已实现）

不改分辨率，而是检测到持续高丢帧时，通过 `processOutputBuffer()` 主动跳过渲染帧。
- 优点：零侵入，不改 codec 配置，音画同步
- 缺点：画面帧率降低（但比被动丢帧更可控）

### 方案 B：FFmpeg 软解 + 缩放

用 FFmpeg（libavcodec）软件解码，可以在解码阶段直接输出低分辨率。
- 优点：完全控制解码分辨率
- 缺点：增加 FFmpeg 依赖（~20MB），软解功耗高，需要 JNI 集成

### 方案 C：ExoPlayer track selection（仅限多轨视频）

`DefaultTrackSelector.setMaxVideoSize(1280, 720)` 可以选择低分辨率轨道。
- 优点：标准 API，零额外依赖
- 缺点：仅对包含多个分辨率轨道的视频（DASH/HLS）有效，本地单轨视频无效

### 方案 D：预处理降采样

播放前用 `MediaCodec` + `ImageReader` 对视频进行离线降采样，生成低分辨率副本。
- 优点：解码时零额外开销
- 缺点：需要额外存储空间，首次播放有延迟

## 建议

当前最实际的方案是**方案 A（性能自适应跳帧）**，配合已有的温控跳帧机制。当检测到持续高丢帧时，主动降低渲染帧率，保持音画同步，体验远好于被动丢帧导致的卡顿。
