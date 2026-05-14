# ADR-003: 读者进度条同步与交互锁策略

## 状态

Accepted (2026-05-11)

## 参与者

陈俊翰，Gemini

## 1. 背景 (Context)

在基于 Jetpack Compose 开发的阅读器中，滑块（Slider）位置与底层容器（Pager 或 LazyColumn）位置之间存在双向同步需求。由于 Compose 副作用（Side-effects）的异步特性，容易出现以下问题：

- **写入冲突（Race Condition）**: 动画未完成时，位置上报反向覆盖用户操作值，导致滑块"回弹"。
- **闭包过时（Stale Closure）**: Phase 2 数据合并导致的 totalCount 变化未及时反映在采样流中。
- **交互冲突**: 后台数据加载触发的跳转逻辑与用户手动拖拽抢夺控制权。

## 2. 决策 (Decisions)

### 2.1 三态锁模式 (Triple-State Lock)

为了彻底仲裁滑块值的控制权，引入显式的状态锁逻辑：

- **isDragged**: 监听 `InteractionSource`，标记用户手指是否在滑块上。
- **isScrolling**: 监听容器的 `isScrollInProgress`，标记物理滚动状态。
- **isUserInteracting**: 自定义布尔状态，跨越协程作用域。必须在发起跳转动画前同步设为 `true`，在 `finally` 块中设为 `false`。

### 2.2 异构同步策略 (Heterogeneous Sync)

拒绝为了统一而统一，根据数据源性质选择同步方案：

- **PagerReader**: 采用**声明式推导** (`derivedStateOf`)。原因：Page 到 Slider 是 1:1 的确定映射，声明式推导天然消除同步窗口期。
- **ContinuousScrollReader**: 采用**响应式采样** (`LaunchedEffect`)。原因：中心索引计算依赖 `layoutInfo` 采样，逻辑复杂，副作用模式性能更优且代码更清晰。

### 2.3 "外科手术式"修复与零抽象 (AHA Principle)

- **拒绝 Scaffold 封装**: 坚持 Rule of Three（三次法则）。目前的两个 Reader 属于"两次"范畴，抽象会引入过度参数化（如类型擦除、复杂的策略注入）。
- **显式重复优于隐藏耦合**: 将 10 行左右的锁逻辑直接维护在各自的 Reader 组件内，确保副作用时序的可见性。

### 2.4 Snapback（单级锚点后悔药）

- **语义限定**: 仅定义为 Undo（撤销），而非 Navigation（导航）。
- **触发条件**: 仅在滑块跳转跨度超过阈值（如 `min(10%, 50 pages)`）时记录 `previousIndex`。
- **生命周期**: 一次性消耗。用户点击回退或自然翻页 2-3 次后，锚点自动销毁。
- **UI 策略**: 在滑块上方显示含精确页码（如"回到第 42 页"）的悬浮按钮，提供最高确定性。

### 2.5 数据合并干扰 (Phase 2 Convergence)

- **不进行"视觉锁定"**: Phase 2 带来的 Range 变化导致的单帧跳变属于自愈性扰动，修复成本（归一化坐标系转换）远高于收益。
- **稳定性保证**: 通过 URI 匹配确保数据合并后 `currentIndex` 重新校准，保证滑块终态正确。

## 3. 后果 (Consequences)

### 正面影响

- **稳定性**: 彻底消灭了滑块回弹和数据加载导致的跳转冲突。
- **性能**: 通过 `settledPage` 和 `distinctUntilChanged()` 降低了重组频率。
- **可维护性**: 逻辑平铺，注释清晰，没有任何"魔法"抽象，新手接入成本低。

### 负面影响

- **代码冗余**: 两个 Reader 之间存在约 10 行高度相似的状态锁代码。
- **手动管理**: 需要开发者手动维护 `try-finally` 结构以确保锁释放。

## 4. 验证 (Verification)

- **逻辑测试**: 在 `SliderUtilsTest` 中验证索引钳位（Clamping）对最新 `itemCount` 的敏感性。
- **冒烟测试**: 验证冷启动下直接加载 Phase 2 数据时，`initialIndex` 的越界保护逻辑（`safeInitial`）。
- **时序守卫**: 在 `finally` 块中使用 `yield()` 或 `awaitFrame()` 确保 UI 帧对齐。

## 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-05-11 | 初始版本，确立三态锁与异构同步原则 |
