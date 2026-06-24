# 阅读器进度条性能踩坑记录

> 日期：2026-05-14
> 关联文件：ContinuousScrollReader.kt, PagerReader.kt

## 问题概述

阅读器中拖动进度条快速跳转图片时，出现卡顿、白屏、滚动延迟等问题。

## 时间线

### V1：`scrollToItem` + `LaunchedEffect` 反馈循环

**实现**：滑块松手 → `listState.scrollToItem(target)` 跳转

**现象**：近处图片能显示，远处图片（如跳到第 50 张）需要滚动数秒才出现。

**原因**：LazyColumn 的 `scrollToItem(target)` 需要依次测量 0..target 之间所有项来计算正确偏移量。对于不同高度的图片（`ContentScale.FillWidth`，每张图宽高比不同），LazyColumn 无法预估位置，必须逐项布局。同时 `LaunchedEffect(currentIndex)` 在跳转过程中反复更新 `sliderValue`，造成滑块抖动和额外 recomposition。

**结论**：Compose 的 LazyColumn 没有 RecyclerView 的 `extraLayoutSpace` 机制（Compose 1.7.0+ 才有 `beyondBoundsItemCount`，当前 BOM 2024.06.00 不支持）。

### V2：`key(jumpTarget)` 重建 LazyListState

**实现**：滑块松手 → 设 `jumpTarget = target` → `key(jumpTarget)` 触发 LazyColumn 重建，`rememberLazyListState(initialFirstVisibleItemIndex = jumpTarget)` 直接从目标索引开始。

**现象**：跳转延迟有所改善，但 LazyColumn 重建期间出现短暂白屏（旧 LazyColumn 销毁、新 LazyColumn 布局之间的间隔）。偶尔滑动失败——LazyListState 重建后触摸事件未正确绑定。

**根因**：`key()` 组件重建会销毁并重新创建整个 LazyColumn 子树，期间手势检测器和滚动状态短暂不可用。

### V3：`LaunchedEffect(jumpTarget)` + `scrollToItem`（当前方案，待修复）

**当前代码**：
```kotlin
var jumpTarget by remember { mutableStateOf(0) }
// ...
onValueChangeFinished = {
    val target = sliderValue.roundToInt().coerceIn(0, totalCount - 1)
    jumpTarget = target
    isDragging = false
}
// LazyColumn inside key(jumpTarget) to recreate state
```

**已知问题**：
1. `key(jumpTarget)` 重建整个 LazyColumn 子树，包括 AsyncImage 和手势处理器
2. 大跨度跳转时白屏/黑屏明显
3. 偶尔滑动手势失效（旧手势处理器已销毁，新的尚未就绪）

## Aniyomi 的做法（参考）

```
RecyclerView
  └── WebtoonLayoutManager
        └── getExtraLayoutSpace() = screenHeight * 3/4
```

- `extraLayoutSpace` 让 RecyclerView 在视口外多布局 75% 屏高的内容
- 配合 `RecyclerView` 的 `scrollToPosition()`，目标位置附近的内容已预布局
- **无需重建视图树**，直接在同一 RecyclerView 内跳转
- 这是 View-based 架构的原生优势

## 可行的后续方案

| 方案 | 难度 | 效果 | 风险 |
|------|------|------|------|
| A. 升级 Compose BOM 到支持 `beyondBoundsItemCount` 的版本 | 低 | 中 | API 兼容性 |
| B. 放弃 LazyColumn，用 `Column + verticalScroll`（全部预加载） | 低 | 中 | 内存（百张以上大图可能OOM） |
| C. 用 `AndroidView` 嵌入 RecyclerView（模仿 Aniyomi） | 高 | 高 | 与 Compose 混合架构 |
| D. 滑块松手后 pop 回网格，重新 navigate 到 Reader 目标位置 | 低 | 中 | 导航栈管理 |
| E. `animateScrollToItem` 代替 `scrollToItem`，快速动画（200ms） | 低 | 低 | 动画期间仍可能有黑屏 |

## 建议

方案 D 最简单可靠：滑块松手 → `navController.popBackStack()` → `navController.navigate(Routes.reader(parentId, targetIndex))`，完全重建 ReaderScreen。代价是丢失缩放状态和阅读模式，但跳转体验稳定。

方案 A 最理想（升级 Compose 加 `beyondBoundsItemCount=5`），但需验证 BOM 兼容性。
