# ExoPlayer 失败自动降级 MediaPlayer 方案

## 问题

≤1080p 视频走 ExoPlayer 路径，如果硬件解码器 configure 失败（与 >1080p 是同类问题），
用户只能看到"播放失败"错误页面，没有自动降级到 MediaPlayer。

当前路由逻辑：
```
probeVideoDimensions → > 1080p → MediaPlayerScreen
                     → ≤ 1080p → ExoPlayer（失败后停在这里）
```

---

## 方案 A：onPlayerError 中检测解码器失败，自动切换 MediaPlayerScreen

在 `VideoPlayerScreen` 的 `onPlayerError` 回调中，检测错误类型是否为解码器相关，
如果是，设置一个状态标志 `shouldFallbackToMediaPlayer = true`，
让 Composable 重新组合并渲染 `MediaPlayerScreen` 替代当前 UI。

```kotlin
// 新增状态
var shouldFallbackToMediaPlayer by remember { mutableStateOf(false) }

// onPlayerError 中
override fun onPlayerError(error: PlaybackException) {
    if (isDecoderError(error)) {
        shouldFallbackToMediaPlayer = true
    } else {
        hasError = true
        // ... 现有错误处理
    }
}

// Composable 顶层
if (shouldFallbackToMediaPlayer) {
    MediaPlayerScreen(videoUri = videoUri, thumbnailPath = thumbnailPath, onBack = onBack)
    return
}
```

需要先释放 ExoPlayer 再切换，避免资源泄漏。

- 优点：复用现有 MediaPlayerScreen，改动集中在 VideoPlayerScreen 一处
- 缺点：切换瞬间有短暂黑屏（ExoPlayer 释放 + MediaPlayer prepare）
- 风险：中——需要确保 ExoPlayer 资源完全释放后再创建 MediaPlayer

---

## 方案 B：VideoPlayerScreen 内嵌 MediaPlayer 降级，不切换 Screen

不跳转到独立的 MediaPlayerScreen，而是在 VideoPlayerScreen 内部创建 MediaPlayer
并替换 ExoPlayer 的渲染层（把 AndroidView 从 PlayerView 换成 SurfaceView）。

```kotlin
var fallbackPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
var useFallback by remember { mutableStateOf(false) }

// onPlayerError 中检测到解码器错误时
if (isDecoderError(error)) {
    exoPlayer.release()
    useFallback = true
    fallbackPlayer = createMediaPlayer(context, videoUri)
}

// UI 中
if (useFallback) {
    // SurfaceView + MediaPlayer 渲染
} else {
    // 原有 PlayerView + ExoPlayer
}
```

- 优点：不依赖外部 Screen，所有状态（进度、控制栏）在同一位置管理
- 缺点：VideoPlayerScreen 代码量大幅增加（+200 行），与 MediaPlayerScreen 大量重复
- 风险：高——状态管理复杂度翻倍，两个播放器路径的生命周期、手势、进度条需要统一处理

---

## 方案 C：probeVideoDimensions 阶段检测解码器可用性，提前路由

在 `probeVideoDimensions` 之后、创建 ExoPlayer 之前，额外检测设备是否能解码该视频。
如果检测到解码器不可用，直接路由到 MediaPlayerScreen。

```kotlin
val canDecode = remember(videoUri) {
    checkDecoderAvailability(context, videoUri)
}
if (!canDecode || longestEdge > HIGH_RES_THRESHOLD) {
    MediaPlayerScreen(...)
    return
}
```

检测方式：`MediaCodecList` 查找匹配的解码器 → `MediaCodec.configure()` 试探 → 立即 release。

- 优点：在创建 ExoPlayer 之前就决定路由，避免浪费资源
- 缺点：`configure()` 试探可能有副作用（某些设备上 configure 失败会影响后续 codec 创建）；增加 ~50-100ms 启动延迟
- 风险：高——configure 试探在不同设备上行为不一致，可能引入新的兼容性问题

---

## 方案 D：放宽分辨率阈值，> 720p 都走 MediaPlayer

把 `HIGH_RES_THRESHOLD` 从 1080 降到 720，让更多视频走 MediaPlayer 路径。
MediaPlayer 是系统原生播放器，兼容性最好。

```kotlin
private const val HIGH_RES_THRESHOLD = 720  // 原来是 1080
```

- 优点：一行改动，彻底避免 ExoPlayer 解码器问题
- 缺点：720p 视频本应走 ExoPlayer（有更好的缓冲、温控、预加载），降级后丢失这些能力
- 风险：低——但牺牲了功能完整性

---

## 方案 E：不处理，保留错误页面 + 外部播放器

当前错误页面已有"重试"和"用外部播放器打开"两个按钮。
对于极少数 ≤1080p 但 ExoPlayer 无法播放的视频，用户可以手动用外部播放器打开。

- 优点：零改动
- 缺点：用户体验不够流畅
- 风险：无

---

## 推荐

**方案 A** 最平衡。理由：
1. 改动集中在一个文件，不增加架构复杂度
2. 复用已验证的 MediaPlayerScreen，不引入新的播放器管理代码
3. 只在解码器相关错误时降级，网络错误、文件不存在等仍走原有错误页面
4. 切换黑屏时间短（MediaPlayer 的 `prepareAsync` 通常 <1 秒）

关键细节：
- `isDecoderError()` 判断：`ERROR_CODE_DECODER_INIT_FAILED`、`ERROR_CODE_DECODING_FAILED`
- 释放顺序：先 `exoPlayer.stop()` + `exoPlayer.release()`，再设置标志位
- MediaPlayerScreen 接管后，BackHandler 需要调用 `onBack`（已有）
- 缩略图参数 `thumbnailPath` 需要透传（方案 A 已覆盖）

## 优先级

| 优先级 | 问题 | 方案 |
|--------|------|------|
| P0 | ExoPlayer ≤1080p 失败无降级 | 方案 A |
| P1 | Log.e 误用 | 直接改 Log.i |
| P1 | activeRenderer 释放时置 null | 一行改动 |
| P2 | 4K 缓冲区偏高 | 可选调整 |
| P2 | probeVideoDimensions 主线程 I/O | 可接受，不处理 |
