# ADR-004: 进度条同步修复投后分析

## 状态

Resolved (2026-05-11)

## 1. 概述 (Summary)

连续两轮修复后，用户报告滑块仍存在"多次跳动"。根因分析发现，前两轮修复只覆盖了表层竞态（PagerReader `derivedStateOf` 缺 `isScrollInProgress` 守卫、ContinuousScrollReader 中心检测 `rememberUpdatedState`），遗漏了深层 bug：**热启动跳转 `LaunchedEffect` 以 `mediaItems` 为 key，被 Phase 2 缩略图分块更新反复取消**。本文件记录第三次修正的根因分析和修复。

## 2. 事件线 (Timeline)

| 时间 | 事件 |
|------|------|
| 2026-05-11 20:30 | ADR-003 首次实现并推送（`9d3617a`） |
| 2026-05-11 20:45 | 用户报告"还是偶尔出现多次跳动" |
| 2026-05-11 20:50 | 根因分析完成，锁定 PagerReader 滞后帧 + ContinuousScrollReader 闭包失效 |
| 2026-05-11 21:00 | 二次修复并推送（`a0dc859`） |
| 2026-05-11 21:15 | 用户再次报告"你没解决多次跳动问题" |
| 2026-05-11 21:30 | 第三次根因分析：锁定热启动跳转 LaunchedEffect 被 Phase 2 分块更新反复取消 |

## 3. 根因分析 (Root Cause Analysis)

### 3.1 Issue #1: PagerReader derivedStateOf 缺 isScrolling 守卫

**文件：** `PagerReader.kt:66-74`

**表现：** 跳转完成后，滑块闪烁：目标值 → 旧值 → 目标值。

**根因：** `derivedStateOf` 条件 `isDragged || isUserInteracting` 没有覆盖 `isScrollInProgress`。`animateScrollToPage` 协程完成后，PagerState 需要额外一帧将 `currentPage` 更新到目标值。这一帧间隙中 `isUserInteracting=false` 但 `currentPage` 还是旧值，导致滑块跳回旧位置。

**修复：** 加入 `pagerState.isScrollInProgress`：

```kotlin
derivedStateOf {
    if (isDragged || isUserInteracting || pagerState.isScrollInProgress) {
        rawSliderValue
    } else {
        pagerState.currentPage.toFloat()
    }
}
```

### 3.2 Issue #2: ContinuousScrollReader 中心检测 LaunchedEffect 闭包捕获过时值

**文件：** `ContinuousScrollReader.kt:117-132`

**表现：** 中心检测 fired 时，`totalCount` 是过时的 Phase 1 值，`onIndexChange` 是旧引用。

**根因：** `LaunchedEffect(listState)` 闭包直接捕获了 `totalCount` 和 `onIndexChange`，Phase 2 数据合并后这些值不会更新。

**修复：** 用 `rememberUpdatedState` 保持最新引用：

```kotlin
val currentTotalCount by rememberUpdatedState(totalCount)
val currentOnIndexChange by rememberUpdatedState(onIndexChange)

LaunchedEffect(listState) {
    snapshotFlow { ... }
        .distinctUntilChanged()
        .collect { raw ->
            val idx = raw.coerceIn(0, (currentTotalCount - 1).coerceAtLeast(0))
            visibleIndex = idx
            if (!isDragged && !isScrolling && !isUserInteracting) {
                currentOnIndexChange(idx)
            }
        }
}
```

### 3.3 Issue #3: 热启动跳转 LaunchedEffect 被 Phase 2 缩略图更新反复取消（根因） ⚡

**文件：** `ContinuousScrollReader.kt:148-174`

**这是导致"多次跳动"的根本原因。**

**触发链路：**

1. ViewModel Phase 2 分块更新 `mediaItems`（每 5 项一个 chunk，约 10 个 chunk）
2. 每个 chunk 写一次 `_state.value.copy(mediaItems = updatedList)` → `mediaItems` 引用变化
3. `LaunchedEffect(mediaItems)` 以 `mediaItems` 为 key，每次引用变化**取消并重启**整个协程
4. 取消时，正在执行的 `animateScrollToItem(target)` 被**强制中断**
5. 重启后，由于 `visibleIndex == target`（中心检测已在 Phase 1 滚动到位），跳过新滚动
6. 但前一个 chunk 触发的滚动动画已被取消 → 列表停留在半路
7. 下一个 chunk 到来 → 重复取消 → 重复中断

**关键路径时序：**

```
Phase 1 完成 → initialIndex = N, visibleIndex = N
  │
  ├─ Phase 2 chunk 1 (items 0-4): mediaItems 引用改变
  │   └─ LaunchedEffect(mediaItems) 重启
  │       ├─ animateScrollToItem(N) ← 实际上不需要滚动，但因为 new effect → 执行一次
  │       │
  ├─ Phase 2 chunk 2 (items 5-9): mediaItems 引用改变
  │   └─ LaunchedEffect 取消 → animateScrollToItem 中断 ← 第一次跳动
  │       └─ 重启 → visibleIndex == target → 直接返回
  │
  ├─ Phase 2 chunk 3 (items 10-14): mediaItems 引用改变
  │   └─ LaunchedEffect 取消 → 再次中断 ← 第二次跳动
  │       └─ 重启 → visibleIndex == target → 直接返回
  │
  └─ ... 重复约 10 次 → 10 次跳动
```

**根因：** `LaunchedEffect` 的 key 应为语义变化的粒度。`mediaItems` 作为 key 会在每次引用变化时重启，而 Phase 2 中引用变化仅代表缩略图就绪，不改变用户应处位置。重启导致的 `animateScrollToItem` 取消是表象，key 粒度错误才是本质。

**修复：** 将 key 从 `mediaItems` 改为 `initialIndex`：

```kotlin
// 以 initialIndex 为 key：只有 URI 调和后索引修正时重启
LaunchedEffect(initialIndex) {
    try {
        if (isDragged) return@LaunchedEffect
        val count = snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        val target = initialIndex.coerceIn(0, count - 1)
        if (visibleIndex == target) return@LaunchedEffect
        // 执行滚动 ...
    }
}
```

`initialIndex` 仅在 ViewModel URI 调和后真正需要修正位置时变化，Phase 2 缩略图更新不会触发。

## 4. 教训 (Lessons Learned)

### 4.1 Compose 状态的帧延迟

`derivedStateOf` 或 `LaunchedEffect` 读取的状态在写-读周期中可能滞后一帧。特别注意：
- `PagerState.currentPage` 在 `animateScrollToPage` 完成后延迟更新
- `LazyListState.layoutInfo.totalItemsCount` 在列表变化后延迟更新

应对策略：在派生状态或闭包中加入**空闲检测守卫**（`isScrollInProgress`、`isDragged`），确保只在系统完全稳定时才做值传递。

### 4.2 LaunchedEffect key 的选择要匹配变化语义

- 用 `mediaItems` 作为 key：任何引用变化都会重启（含缩略图更新）
- 用 `initialIndex` 作为 key：仅当需要修正滚动位置时重启 ✓
- 用 `rememberUpdatedState`：不重启，始终读取最新值

选择合适的粒度：key 应只对影响结果的变化敏感。**不是每个数据变化都值得重启一个 Effect。** 在 Compose 中，LaunchedEffect 的取消是即时且不可恢复的——任何在 `animate*` 中的协程都会硬中断。所以 key 的选择本质上是"重启的成本 vs 不重启的风险"之间的权衡。对代价高的操作（滚动动画），key 应尽可能窄。

### 4.3 守卫条件的完备性

`onIndexChange` 回调的守卫条件应匹配三重状态锁：

```kotlin
// 正确：覆盖所有非空闲状态
if (!isDragged && !isScrolling && !isUserInteracting) {
    currentOnIndexChange(idx)
}
```

遗漏 `isScrolling` 会在程序化滚动（热启动跳转、滑块跳转）期间产生不必要的 `onIndexChange` 回调，可能触发 ViewModel 的额外状态更新。

### 4.4 `distinctUntilChanged` 不是万能的

当 LaunchedEffect 重启时，新的 `snapshotFlow` 首次发射没有"前值"可比较，`distinctUntilChanged` 总会通过。这意味着即使中心检测的值没有变化，也会执行一次 `collect` 块。因此仅仅靠 `distinctUntilChanged` 是不够的——还需要守卫条件和精准的 key 选择。

## 5. 最终状态 (Final State)

| 组件 | 同步策略 | 守卫条件 / Key | 稳定性 |
|------|---------|---------|--------|
| PagerReader | `derivedStateOf` | `isDraged \|\| isUserInteracting \|\| isScrolling` | ✓ |
| ContinuousScrollReader 滑块反写 | `LaunchedEffect` + `rememberUpdatedState` | `!isDraged && !isScrolling && !isUserInteracting` | ✓ |
| 中心检测 `onIndexChange` | `rememberUpdatedState` | `!isDraged && !isScrolling && !isUserInteracting` | ✓ |
| 热启动跳转 | `LaunchedEffect(initialIndex)` | `isDragged` 守卫 + `visibleIndex == target` 短路 | ✓ |

## 6. 相关提交

| 提交 | 说明 |
|------|------|
| `9d3617a` | ADR-003 首次实现：derivedStateOf + isUserInteracting + 闭包修复 |
| `a0dc859` | 二次修复：rememberUpdatedState（isScrolling 修复不完整） |
| `HEAD` | Issue#3 修复：LaunchedEffect key `mediaItems` → `initialIndex`，isScrolling 守卫补全 |
