# ADR-001: 高性能视频预加载与资源调度策略

## 状态

Accepted (2026-05-11)

## 1. 背景 (Context)

在本地媒体阅读器场景中，视频首帧的"秒开"体验是衡量产品质量的核心指标。原有的实现方式存在以下瓶颈：

- **硬件竞争**: 快速滑动时，解码器频繁释放与重建（震荡），导致低端设备温控降频或系统卡顿。
- **所有权迷雾**: Preloader 与 UI 组件在 `take()` 过程中存在竞态，可能导致已移交的播放器被误杀。
- **反馈缺失**: UI 无法感知预加载进度，导致在预热中途进入播放页时，用户面临黑屏或双击冲突。

## 2. 决策驱动 (Decision Drivers)

- **硬件确定性**: 严格限制解码器实例数量。
- **生命周期隔离**: 播放页退出动画与新视频预加载互不干扰。
- **自适应能力**: 算法需能感知 SoC 负载并动态调整压制策略。
- **UI 流畅度**: 消除因异步加载导致的 Jitter（跳变）和 Loading 状态不连贯。

## 3. 架构方案 (Proposed Architecture)

### 3.1 核心原语：双槽流水线模型 (Active-Hot Dual-Slot)

废弃单变量引用，采用职责分离的双槽架构：

- **Active Slot**: 托管当前正在 UI 层显示的播放器。由 UI 所有权锁定（Consumed 状态），严禁被预加载逻辑抢占。
- **Hot Slot**: 托管预取的"下一帧"播放器。属于高度不稳定的抢占式槽位，随滑动实时更新。

### 3.2 资源状态机 (Slot State Machine)

使用密封类（Sealed Class）对槽位所有权进行显式建模，确保操作的原子性：

- `Empty`: 初始状态。
- `Preparing(sessionId, player)`: 生产者正在后台初始化硬件。
- `Consumed(sessionId)`: 所有权已移交给 UI，生产者失去改写权。

### 3.3 交互契约：三态获取模型 (Three-State Take)

UI 调用 `take()` 时，不再返回单一对象，而是返回状态映射：

| 状态 (TakeResult) | 场景 | UI 表现 |
|---|---|---|
| `Ready` | 预热已完成 | 瞬间切换，无感播放 |
| `InProgress` | 正在热机但未就绪 | 承接现有实例，显示缓冲动画 |
| `Cold` | 预热未命中或已过期 | 走兜底逻辑，全量初始化 |

## 4. 动态背压算法 (Dynamic Backpressure)

### 4.1 非对称 EMA 逻辑

为了应对解码器重构延迟的重尾分布，采用非对称指数移动平均（EMA）动态调整 debounce 阈值：

$$EMA_t = \alpha \cdot L_t + (1 - \alpha) \cdot EMA_{t-1}$$

其中：

- $\alpha_{Attack}$ (0.5): 延迟激增时快速建立防御。
- $\alpha_{Decay}$ (0.05): 延迟回归时缓慢降低戒备。

### 4.2 墙钟时间衰减通道 (Wall-clock Decay)

引入物理时间维度，解决采样噪声导致的假阳性恢复：

$$W_{idle} = \frac{T_{idle} - 1000}{29000}$$

算法在检测到系统空闲超过 30s 后，强制回归基线（Baseline），模拟硬件冷却过程。

## 5. 优化层级 (Optimization Layers)

### Layer 1: 播放器实例复用

在 Hot Slot 内部，同格式切换时通过 `stop() + setMediaItem()` 替代 `release() + build()`。利用 ExoPlayer 内部的 MediaCodec 刷新机制，避免 HAL 层的重新握手。

### Layer 2: 设备能力退化

根据 `ActivityManager.isLowRamDevice` 动态裁撤 Hot Slot。在极低端设备上回归"生存主义"，确保单实例运行。

## 6. 后果 (Consequences)

### 优点 (Pros)

- **物理稳定性**: 彻底消除解码器震荡导致的系统级死锁。
- **极致体验**: 实现了在复杂滑动场景下的首帧秒开及平滑降级。
- **架构清晰**: 所有权移交路径明确，解决了多线程下的 `release()` 泄露问题。

### 缺点 (Cons)

- **实现复杂度**: 需要处理非对称 EMA 和墙钟衰减的边界参数。
- **资源占用**: 在高端设备上常驻两个播放器对象（虽然仅一个在 active），对内存有微量额外消耗。

## 7. 附录：关键常量定义

| 常量 | 值 | 说明 |
|---|---|---|
| Baseline Debounce | 100ms | 初始 debounce 阈值 |
| Max Debounce Threshold | 2000ms | 动态背压上限 |
| Cooling Threshold | 30s | 墙钟衰减通道触发空闲时长 |
