# ADR-004: 进度条同步修复投后分析

## 状态

Resolved (2026-05-11)

## 1. 概述 (Summary)

ADR-003 实现后，用户报告进度条"偶尔还会出现多次跳动"。经分析，首次修复遗漏了两个残余窗口期。本文件记录第二次修正的根因分析。

## 2. 事件线 (Timeline)

| 时间 | 事件 |
|------|------|
| 2026-05-11 20:30 | ADR-003 首次实现并推送（`9d3617a`） |
| 2026-05-11 20:45 | 用户报告"还是偶尔出现多次跳动" |
| 2026-05-11 20:50 | 根因分析完成，锁定两个残余竞态 |
| 2026-05-11 21:00 | 二次修复并推送（`a0dc859`） |

## 3. 根因分析 (Root Cause Analysis)

### 3.1 Issue #1: derivedStateOf 缺 isScrolling 守卫

**文件：** `PagerReader.kt:66-74`

**表现：** 跳转完成后，滑块闪烁：目标值 → 旧值 → 目标值。

**序列图：**

```
用户松手
  │
  ├─ onValueChangeFinished
  │   ├─ isUserInteracting = true (同步)
  │   ├─ scope.launch { animateScrollToPage(target) }
  │   │   └─ 动画完成
  │   │       └─ finally { isUserInteracting = false }
  │   │
  │   └── isUserInteracting = false  →  重组触发
  │                                       │
  │                                       ├─ derivedStateOf 执行
  │                                       │   └─ 此时 currentPage 尚未更新 (PagerState 内延迟一帧)
  │                                       │   └─ effectiveSliderValue = OLD_PAGE ← 跳回旧值
  │                                       │
  │                                       └─ 下一帧 currentPage = target
  │                                           └─ effectiveSliderValue = target ← 跳回目标
```

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

### 3.2 Issue #2: LaunchedEffect 在缩略图分块更新时频繁重启（中心检测）

**文件：** `ContinuousScrollReader.kt:117-132`（首次修复版本）

**触发链路：**

1. ViewModel Phase 2 以每 5 项为 chunk 更新 `thumbnailPath`
2. 每个 chunk 写一次 `_state.value = _state.value.copy(mediaItems = updatedList)`
3. `mediaItems` 引用每次变更 → `LaunchedEffect(listState, mediaItems)` 重启
4. 新 snapshotFlow 首次发射通过 `distinctUntilChanged`（无前值可比较）
5. `visibleIndex` 被重复写入，触发三重锁反写和 `onIndexChange` 回调

**根因：** `mediaItems` 作为 key 粒度太粗。缩略图更新不改变列表大小或排序，但内容引用变化仍会导致 LaunchedEffect 重启。ADR-003 修复 Issue#2（过时 totalCount）时使用了 `mediaItems` 作为 key，解决了闭包问题但引入了过度重启。

**修复：** 用 `rememberUpdatedState` 替代 `mediaItems` key（已在 ADR-003 实施，但不足以彻底解决"多次跳动"）：

```kotlin
val currentTotalCount by rememberUpdatedState(totalCount)
val currentOnIndexChange by rememberUpdatedState(onIndexChange)

LaunchedEffect(listState) {  // 单一 key，不再因缩略图更新重启
    snapshotFlow { ... }
        .distinctUntilChanged()
        .collect { raw ->
            val idx = raw.coerceIn(0, (currentTotalCount - 1).coerceAtLeast(0))
            visibleIndex = idx
            // 交互锁保护：拖拽中/跳转动画中不写 ViewModel
            if (!isDragged && !isScrolling && !isUserInteracting) {
                currentOnIndexChange(idx)
            }
        }
}
```

`rememberUpdatedState` 保持闭包内引用最新，无需 LaunchedEffect 重启。

### 3.3 Issue #3: 热启动跳转 LaunchedEffect 在 Phase 2 缩略图更新时反复取消（根因） ⚡

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
- 用 `mediaItems.size` 作为 key：仅列表大小变化时重启

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

### 4.4 derivedStateOf 不是全无副作用的

`derivedStateOf` 的计算在每次组合时执行（当依赖变化时）。计算不应产生副作用（如写入另一个 State）。本案例中 `derivedStateOf` 仅用于下拉 `effectiveSliderValue`，无副作用——这是安全用法。

## 5. 最终状态 (Final State)

| 组件 | 同步策略 | 守卫条件 / Key | 稳定性 |
|------|---------|---------|--------|
| PagerReader | `derivedStateOf` | `isDraged \|\| isUserInteracting \|\| isScrolling` | ✓ |
| ContinuousScrollReader 滑块反写 | `LaunchedEffect` + `rememberUpdatedState` | `!isDraged && !isScrolling && !isUserInteracting` | ✓ |
| 中心检测 `onIndexChange` | `rememberUpdatedState` | `!isDraged && !isScrolling && !isUserInteracting` ← 新增 | ✓ |
| 热启动跳转 | `LaunchedEffect(initialIndex)` ← 原 `mediaItems` | `isDragged` 守卫 + `visibleIndex == target` 短路 | ✓ |

## 6. 相关提交

| 提交 | 说明 |
|------|------|
| `9d3617a` | ADR-003 首次实现：derivedStateOf + isUserInteracting + 闭包修复 |
| `a0dc859` | 残余跳动修复：rememberUpdatedState(isScrolling 修复不完整) |
| `HEAD` | Issue#3 修复：LaunchedEffect key `mediaItems` → `initialIndex`，isScrolling 守卫补全 |
