# 视频播放器高分辨率播放优化

文档版本：3.0
日期：2026-06-19
状态：迭代 2 完成
关联分支：feature/mediaplayer-fallback
历史文档：[archive/adaptive-resolution-experiments.md](archive/adaptive-resolution-experiments.md) · [archive/proposals/video-player-fallback-proposals.md](archive/proposals/video-player-fallback-proposals.md) · [archive/proposals/video-player-fix-proposals.md](archive/proposals/video-player-fix-proposals.md)

---

## 1. 背景与问题

核心痛点：部分高分辨率视频（长边 > 1080px）在 ExoPlayer 渲染管线下出现明显卡顿，而系统播放器播放同一视频时流畅。

当前方案：通过 `PlayerRouter` 智能路由，将设备硬件解码器不支持的视频路由至 MediaPlayer 路径。

现状问题：MediaPlayer 备用路径存在功能缺失和体验缺陷（视频拉伸变形、播放控制不一致、生命周期不完善等）。

---

## 2. 目标

- **短期（迭代 1，已完成）**：补齐 MediaPlayer 备用路径的基础播放体验，与 ExoPlayer 路径在视觉和交互层面完全对齐。
- **中期（迭代 2）**：智能路由模型优化，根据实际播放性能动态调整路由策略。
- **长期（迭代 3-4）**：收敛至可配置的稳定方案，建立特性开关与灰度机制。

---

## 3. 功能需求与实现状态

### 3.1 视频画面比例保持 ✅ 已完成

**方案**：使用 `MediaPlayer.setVideoScalingMode(VIDEO_SCALING_MODE_SCALE_TO_FIT)`，由系统层自动处理 letterbox 黑边。

**关键实现细节**：
- `setVideoScalingMode` 在 `setDataSource` **之前**调用（而非 `onPrepared` 中），让渲染管线在配置阶段就知道输出模式
- 渲染层使用 `TextureView`（非 SurfaceView），`Modifier.fillMaxSize()` 撑满屏幕
- 旋转元数据通过 `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION` 探测，在路由决策中交换宽高

**历史方案与放弃原因**：
- ~~BoxWithConstraints + 手动计算宽高比~~：`videoAspectRatio` 变化触发 recomposition → SurfaceView 重建 → 黑屏
- ~~SurfaceView + setVideoScalingMode~~：Surface 生命周期与 Window 焦点绑定，`surfaceDestroyed` 在 pause 时触发导致 player 被释放

### 3.2 播放控制栏统一 ✅ 已完成

两个播放器路径（ExoPlayer / MediaPlayer）均支持：
- 播放/暂停按钮
- 进度条（与播放器 currentPosition 同步，200ms 轮询）
- 当前时间 / 总时长显示
- 双击左半屏快退 10s / 右半屏快进 10s
- 长按 3x 倍速（松手恢复 1x）
- 点击切换控制栏显隐，3s 自动隐藏

**未实现（用户明确不要）**：
- ~~左右滑动快进/快退~~
- ~~左侧上下滑动亮度~~
- ~~右侧上下滑动音量~~

### 3.3 生命周期完整性 ✅ 已完成

**TextureView 方案的生命周期优势**：
- `ON_PAUSE`：暂停播放，记录 `wasPlaying`
- `ON_RESUME`：若 player 存活则直接恢复；若 player 被释放但 Surface 存活则重建 player；若 Surface 也不可用则等 `onSurfaceTextureAvailable` 自动触发
- `onDispose`：释放 player + Surface
- `BackHandler`：释放 player + 调用 `onBack()`
- `onSurfaceTextureDestroyed`：释放 player + Surface，返回 `true` 让系统释放 TextureView
- 旋转时 SurfaceTexture 自动保留，player 无需重建（TextureView 核心优势）

**竞态防护**：
- `playerGeneration` 计数器，每次 `createPlayer` 递增
- 所有异步回调（`onPrepared`/`onError`/`onCompletion`/`onInfo`/`onVideoSizeChanged`）比对 generation，已释放 player 的回调直接忽略
- 消除了 SurfaceView 方案中的 ABA 竞态（`onPrepared` 回调覆盖新 player 引用）

### 3.4 智能路由决策 ✅ 已完成

**实现**：`PlayerRouter.kt`

- 替代原来的 `> 1080px` 硬阈值
- 通过 `MediaCodecList` 检测设备是否有支持该编码格式+分辨率+帧率的**硬件解码器**
- 低分辨率（≤ 1080px）直接走 ExoPlayer
- 高分辨率先查 `CodecCapabilities.supportedWidths/supportedHeights/supportedFrameRates`
- 排除软件解码器（`OMX.google.*`、`c2.android.*`；API 29+ 使用 `isHardwareAccelerated`）
- `LruCache<String, Boolean>(64)` 缓存路由结果，同一视频多次打开不重复计算

**路由决策流程**：
```
probeVideoDimensions → 获取宽高+旋转
  → 旋转校正后 longestEdge ≤ 1080 → ExoPlayer
  → longestEdge > 1080 → PlayerRouter.shouldUseExoPlayer()
    → 查 MediaCodecList 有匹配硬件解码器 → ExoPlayer
    → 无匹配 → MediaPlayer
```

**ExoPlayer 解码器失败兜底**：
- `onPlayerError` 中检测 `ERROR_CODE_DECODER_INIT_FAILED` / `ERROR_CODE_DECODING_FAILED` / `ERROR_CODE_DRM_SYSTEM_ERROR`
- 自动降级到 `MediaPlayerScreen`（`shouldFallbackToMediaPlayer = true`）

### 3.5 特性开关 ✅ 已完成

**实现**：Settings → video player，三种模式 Radio 选择。

- `AUTO`（默认）：智能检测（MediaCodecList + 历史性能反馈）
- `EXOPLAYER`：强制 ExoPlayer，跳过路由判断
- `MEDIAPLAYER`：强制 MediaPlayer（调试用）

存储：`DataStore` 的 `VIDEO_PLAYER_MODE` key（String），`AppSettings` 统一管理。
`PlayerRouter.shouldUseExoPlayer(context, uri, mode)` 接受 `PlayerRouterMode` 枚举，AUTO 时走完整智能检测流程，其他模式直接返回。

**不做的事**：
- ~~Firebase Remote Config 远程开关~~：个人项目无需远程配置
- ~~白名单设备/用户~~：无灰度需求

### 3.7 动态路由优化 ✅ 已完成

**问题**：MediaCodecList 只能检测"硬件声称支持"，无法反映"实际播放是否流畅"。某些设备硬件解码器声明支持 4K H.265，但实际播放时严重丢帧。

**方案**：`PlayerRouter` 新增 `recordPlaybackOutcome(uri, success, dropRate)` 接口，播放器路径在以下时机反馈：
- ExoPlayer 丢帧监控：5s 轮询中 dropRate > 5% 时记录
- ExoPlayer 播放结束：记录 success
- ExoPlayer 解码器错误：记录 failure + dropRate=100%
- MediaPlayer onPrepared：记录 success
- MediaPlayer onError：记录 failure

**路由决策流程（更新后）**：
```
probeVideoDimensions → 获取宽高+旋转
  → 旋转校正后 longestEdge ≤ 1080 → ExoPlayer
  → longestEdge > 1080 → PlayerRouter.shouldUseExoPlayer()
    → 查 MediaCodecList 有匹配硬件解码器
      → 查 playbackHistory：历史丢帧率 > 15% → MediaPlayer
      → 历史正常或无历史 → ExoPlayer
    → 无匹配 → MediaPlayer
```

存储：`LruCache<String, PlaybackOutcome>(32)`，仅内存，App 重启清空。

### 3.6 异常降级与兜底 ✅ 已完成

- ExoPlayer 解码器失败 → 自动降级 MediaPlayer
- MediaPlayer 初始化/播放失败 → 错误页面 + 重试按钮 + 外部播放器按钮
- `MediaPlayer.MEDIA_ERROR_SERVER_DIED` / `MEDIA_ERROR_UNKNOWN` 分类提示

---

## 4. 技术方案对比

### 渲染层选型：SurfaceView vs TextureView

| 维度 | SurfaceView | TextureView |
|------|------------|-------------|
| Surface 生命周期 | 随 Window 焦点，pause 时可能销毁 | 随 View 生命周期，pause 时不销毁 |
| 旋转处理 | Surface 销毁→player 释放→重建→黑屏 | SurfaceTexture 自动保留，无缝过渡 |
| Compose 集成 | `AndroidView` + `SurfaceHolder.Callback` 异步 | `AndroidView` + `TextureView.SurfaceTextureListener` |
| 内存 | 独立 Surface 层，不消耗 View 层内存 | 合成到 View 层，消耗一份纹理内存 |
| DRM | 支持 Secure Surface | 不支持 Secure Surface（非 DRM 场景无影响） |
| 竞态风险 | 高（surfaceCreated/Destroyed 异步回调） | 低（回调更可控） |

**选择 TextureView**：本场景无 DRM 需求，TextureView 的生命周期优势解决了黑屏核心问题。

### 播放器路由：硬阈值 vs 智能检测

| 维度 | 硬阈值（>1080px） | 智能检测（MediaCodecList） |
|------|------------------|--------------------------|
| 准确性 | 低（设备差异大） | 高（查询实际解码能力） |
| 性能 | O(1) | O(N)，N = codec 数量（通常 < 20） |
| 缓存 | 无需 | LruCache(64) |
| 维护 | 需手动调阈值 | 自动适配设备能力 |

---

## 5. 文件变更清单

| 文件 | 变更 |
|------|------|
| `ui/player/MediaPlayerScreen.kt` | SurfaceView → TextureView，generation counter，setVideoScalingMode 时序修正，生命周期竞态修复，播放结果反馈 |
| `ui/player/VideoPlayerScreen.kt` | 集成 PlayerRouter 智能路由，probeVideoDimensions 返回旋转角度，丢帧监控反馈，设置模式读取 |
| `util/PlayerRouter.kt` | **新增**，MediaCodecList 硬件解码器能力检测 + LruCache 路由缓存 + 动态路由历史 + PlayerRouterMode 枚举 |
| `util/AppSettings.kt` | 新增 `VIDEO_PLAYER_MODE` key + `saveVideoPlayerMode()` |
| `ui/settings/SettingsViewModel.kt` | 新增 `videoPlayerMode` state + `setVideoPlayerMode()` |
| `ui/settings/SettingsScreen.kt` | 新增 "video player" section，三种模式 Radio 选择 |

---

## 6. 验收标准

| 编号 | 场景 | 预期 |
|------|------|------|
| A1 | 高分辨率视频（2K/4K）播放 | 画面无拉伸，letterbox 黑边正确 |
| A2 | 旋转视频（90°/270°） | 宽高比正确，无变形 |
| A3 | 播控一致性 | ExoPlayer / MediaPlayer 路径控件、手势、倍速完全一致 |
| A4 | 后台→前台切换 | 暂停→恢复，无黑屏、无崩溃 |
| A5 | 旋转屏幕 | 画面平滑过渡，不重建 player |
| A6 | ExoPlayer 解码器失败 | 自动降级 MediaPlayer，无中断感 |
| A7 | 低分辨率视频 | 走 ExoPlayer，不走 MediaPlayer |
| A8 | 无硬件解码器的高分辨率 | 走 MediaPlayer，画面正常 |

---

## 7. 已知限制与后续迭代

- **MediaPlayer 无丢帧监控 API**：无法像 ExoPlayer 那样监控实际渲染帧率，只能依赖 `MediaInfo` 缓冲事件
- **MediaPlayer 无预加载**：ExoPlayer 路径有 `PlayerPreloader` 三态 Take，MediaPlayer 路径每次冷启动（迭代 3 计划）
- **TextureView 不支持 DRM**：如后续需要播放受保护内容，需回退到 SurfaceView
- **动态路由历史仅内存**：App 重启后历史清空，需重新收集

---

## 8. 时间线

| 迭代 | 内容 | 状态 |
|------|------|------|
| 迭代 1 | 比例修复、播控统一、生命周期、智能路由、异常降级 | ✅ 完成 |
| 迭代 2 | 特性开关（DataStore 三模式）、动态路由优化（播放历史反馈） | ✅ 完成 |
| 迭代 3 | MediaPlayer 预加载、灰度放量、埋点收集 | ⏳ 待定 |
| 迭代 4 | 全量发布、退出机制（ExoPlayer 修复卡顿后收敛） | ⏳ 待定 |
