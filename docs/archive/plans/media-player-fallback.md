# MediaPlayer 高分辨率视频降级方案

## 背景

ExoPlayer 在某些设备上播放高分辨率视频（如 2560x1440 H.264 High Profile Level 5.2）时，硬件解码器 configure 失败（EINVAL），内部 fallback 到软件解码器后 Surface 损坏，导致视频无法播放且不触发任何回调。系统播放器（底层使用 MediaPlayer）可以正常播放同类视频。

## 方案

在 `VideoPlayerScreen` 中，先用 `MediaMetadataRetriever` 探测视频分辨率。如果最长边 > 1080p，使用 `MediaPlayer` + `SurfaceView` 替代 ExoPlayer + PlayerView。UI 层（手势、控制栏、缩略图、错误页面）保持不变。

## 改动

### 1. 新建 `app/src/main/java/com/example/reader/ui/player/MediaPlayerScreen.kt`

独立的 Composable，使用 `MediaPlayer` 播放视频：

- **Surface 管理**：`AndroidView` 包裹 `SurfaceView`，通过 `SurfaceHolder.Callback` 管理 Surface 生命周期
- **MediaPlayer 生命周期**：
  - `surfaceCreated` → `setDataSource` + `prepareAsync`
  - `surfaceDestroyed` → `release()`
  - `onPrepared` → 获取 duration，开始播放
  - `onCompletion` → 重置状态
  - `onError` → 显示错误页面
- **进度轮询**：`LaunchedEffect(isPlaying)` 每 200ms 从 `mediaPlayer.currentPosition` 读取
- **UI 复用**：复制 VideoPlayerScreen 的 UI 层（缩略图、缓冲指示器、错误覆盖层、手势、控制栏），去掉 ExoPlayer 特有的部分（性能监控、温控、刷新率适配、倍速长按）
- **生命周期**：`DisposableEffect` 管理 `ON_PAUSE`/`ON_RESUME`，`BackHandler` 管理返回

### 2. 修改 `VideoPlayerScreen.kt`

- 删除所有 ExoPlayer 相关的 fallback 逻辑（`swFallbackAttempted`、`playerViewKey`、`createSoftwareOnly`、5 秒超时）
- 删除 `currentPlayer` 可变状态，恢复为简单的 `val exoPlayer = session.player`
- 在 composable 开头探测视频分辨率，如果 > 1080p，调用 `MediaPlayerScreen` 并 return
- 保留 ExoPlayer 版本的完整逻辑（用于 ≤ 1080p 的视频）

### 3. 修改 `VideoPlayerFactory.kt`

- 删除 `createSoftwareOnly()` 方法和 `softwareOnlySelector()` 函数（不再需要）
- 保留 `create()` 和双 Renderer 逻辑（用于正常分辨率视频）

## 不改动

- `NavGraph.kt` — 路由不变，分辨率检测在 VideoPlayerScreen 内部完成
- `PlayerPreloader.kt` — 预加载仅用于 ExoPlayer 路径
- 手势、控制栏 UI 逻辑 — 在 MediaPlayerScreen 中复制，保持一致

## 降级策略

```
视频打开
  → probeVideoDimensions()
  → 最长边 > 1080p?
      → YES: MediaPlayerScreen (SurfaceView + MediaPlayer)
      → NO:  VideoPlayerScreen (PlayerView + ExoPlayer)
```

## MediaPlayer 局限

- 无温控跳帧（MediaPlayer 不暴露 renderer 级别的帧控制）
- 无性能监控（无 `videoDecoderCounters`）
- 无预加载（MediaPlayer 不支持预热）
- 长按倍速使用 `PlaybackParams.setSpeed()`（API 23+）

这些局限可接受，因为 MediaPlayer 路径仅用于 ExoPlayer 无法播放的高分辨率视频——能播比优化更重要。
