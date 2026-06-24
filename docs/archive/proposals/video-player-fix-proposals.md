# 视频播放器缺陷修复方案（修订版）

> 本文档经 agent 审查后修订。原始分析中问题 1（缩略图遮挡）为误判，当前代码中不存在该 bug。

---

## 已确认需修复的问题

### P0-1：AudioAttributes 缺失（MediaPlayerScreen）

**现状**：ExoPlayer 路径设置了 AudioAttributes，MediaPlayer 路径完全缺失。
**修复**：添加 AudioAttributes 设置。minSdk 26，无需版本检查。
**改动**：~6 行

### P0-2：SideEffect 强转 Activity 无防护（两处）

**现状**：`VideoPlayerScreen.kt:352` 和 `MediaPlayerScreen.kt:227` 都有 `view.context as Activity`。
**修复**：改为 `as? Activity ?: return@SideEffect`。
**改动**：各 1 行

### P1-1：BackHandler 未释放 Surface（MediaPlayerScreen）

**现状**：BackHandler 只调用 `onBack()`，未释放 MediaPlayer。高分辨率视频返回动画期间仍解码。
**修复**：在 BackHandler 中调用 `releasePlayer()`。
**改动**：~2 行

### P1-2：seekTo Int 溢出（MediaPlayerScreen）

**现状**：`mp.seekTo(target.toInt())` 对超长视频溢出。minSdk 26 的 `seekTo(long)` 接受 Long。
**修复**：改为 `mp.seekTo(target)`。
**改动**：1 行

### P2-1：Slider Float 精度（两处）

**现状**：Slider 用 Float 表示毫秒，>4.6h 视频有 2-4ms 抖动（用户无感知但技术上不精确）。
**修复**：归一化 0f..1f，用 Double 做中间计算。两处都需要。
**改动**：各 ~3 行

### P3-1：formatTime 重复代码

**现状**：两个文件中完全重复。
**修复**：提取到公共位置。
**改动**：~10 行

---

## 不修复的问题

### ~~问题 1：缩略图永久遮挡~~（误判）

当前代码使用 `isFirstFrameRendered`，在 `onPrepared` 中设为 `true`，暂停/恢复时不重置。Bug 不存在。

### 问题 2：ON_PAUSE 未释放资源

标准 Android 行为，高分辨率视频重新 prepare 代价太高。

### 问题 4：无性能监控（MediaPlayer）

MediaPlayer 无丢帧 API，仅添加温控检测。
