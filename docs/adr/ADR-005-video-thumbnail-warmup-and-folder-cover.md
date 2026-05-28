# ADR-005: 视频缩略图预热与文件夹视频封面复用

## 状态

Accepted (2026-05-28)

## 1. 概述 (Summary)

为减少 PixScroll 媒体网格与文件夹列表中的视频缩略图空白、重复抽帧和封面不一致问题，决定扩展 `MediaFolder` 的封面视频 metadata，并由 `ReaderViewModel` / `ThumbnailManager` 负责视频 L2 缩略图生成与 `thumbnailPath` 回填。

L2 缩略图 key 合约固定为：`path + dateModified + size`。缓存目录固定为：`cacheDir/thumbnails`。`AsyncGridImage(MediaItem)` 优先使用 `thumbnailPath`，缺失时再通过 Coil 视频抽帧 fallback，并将生成结果写入 L2。

## 2. 背景 (Context)

PixScroll 的视频缩略图会出现在多个界面：

- 媒体网格中的视频条目。
- 搜索结果中的视频条目。
- Reader / 媒体列表预热后的再次进入。
- 文件夹列表中的封面。

已有事实与约束：

- `ThumbnailManager` L2 key 是 `path + dateModified + size`。
- L2 缓存目录是 `cacheDir/thumbnails`。
- `AsyncGridImage(MediaItem)` 优先 `thumbnailPath`，否则视频 fallback 走 Coil 抽帧并写 L2。
- `ReaderViewModel` 将负责生成/回填缺失视频 `thumbnailPath`。
- `MediaFolder` 将增加封面视频 metadata 以复用 L2。

如果文件夹只保存 `coverImageUri`，当封面媒体是视频时，UI 无法构造 `ThumbnailManager` 所需的 L2 key，也就无法稳定复用已生成的视频缩略图。结果是文件夹封面、媒体网格和 Reader 预热可能各自触发抽帧。

## 3. 决策驱动 (Decision Drivers)

- **复用同一份缩略图**：媒体网格、文件夹封面和回填结果必须共享相同 L2 key 合约。
- **避免 UI 层散落抽帧逻辑**：Composable 只负责展示和 fallback，不应持有抽帧调度与缓存一致性策略。
- **支持文件变化失效**：文件路径相同但修改时间或大小变化时，必须视为新版本。
- **兼容旧缓存**：`MediaFolder` 增加字段后，旧 `FolderCache` JSON 仍应能读取并安全降级。
- **不干扰阅读位置**：视频缩略图回填会更新列表引用，但不应触发 Reader 索引重置或滚动跳动。

## 4. 决策 (Decision)

### 4.1 扩展 `MediaFolder` metadata

`MediaFolder` 增加封面视频 metadata，用于表达封面视频的 L2 key 输入。

字段命名以实现为准，但语义至少覆盖：

```text
coverVideoPath
coverVideoDateModified
coverVideoSize
coverVideoMimeType 或等价视频标记
```

当封面媒体是视频时，Repository / ViewModel 应填充这些字段；当封面是图片或 metadata 不可得时，这些字段允许为空。

### 4.2 L2 生成与回填由 ViewModel/ThumbnailManager 负责

采用以下责任划分：

| 层 | 职责 |
|----|------|
| Repository | 读取 MediaStore / FileTreeWalk metadata，构建 `MediaItem` 与 `MediaFolder` |
| `ThumbnailManager` | 根据 `path + dateModified + size` 查询、生成、写入 L2 缩略图 |
| `ReaderViewModel` | 调度缺失视频缩略图生成，并以不可变方式回填 `MediaItem.thumbnailPath` |
| Folder ViewModel | 使用 `MediaFolder` 封面视频 metadata 查询或生成 L2 文件夹封面 |
| UI / Composable | 优先展示 `thumbnailPath` 或文件夹封面路径，缺失时安全 fallback |

选择 ViewModel/ThumbnailManager，而不是 Composable 直接管理 L2，原因是：

1. **生命周期更适合**：ViewModel 能在列表状态层面统一调度，避免每个可见 item 各自发起重复任务。
2. **状态可回填**：生成成功后可以把 `thumbnailPath` 写回 `MediaItem`，后续 UI 重组直接复用。
3. **可测试性更高**：缓存 key、生成失败、回填策略可以在 ViewModel/工具层测试，而不是依赖 Compose 可见性。
4. **职责更清晰**：Composable 不需要理解 L2 key 合约，只需按优先级显示。
5. **减少重复抽帧**：同一 key 的生成可在工具层或 ViewModel 层做去重。

### 4.3 `AsyncGridImage(MediaItem)` 显示优先级

显示顺序固定为：

```text
MediaItem.thumbnailPath
  → Coil 普通图片加载
  → 视频 fallback 抽帧
  → 成功后写入 ThumbnailManager L2
  → 后续由 ViewModel 回填 thumbnailPath
```

UI fallback 是为了保证缺失 L2 时仍可显示，不是为了定义另一套缓存策略。

## 5. L2 key 合约

### 5.1 Key 输入

L2 key 的逻辑输入为：

```text
path + dateModified + size
```

含义：

- `path`：定位同一个媒体文件。
- `dateModified`：识别文件内容是否被修改。
- `size`：辅助识别替换、覆盖、截断等变化。

三者任一变化，都应视为不同缓存条目。

### 5.2 Key 使用规则

- 所有视频缩略图 L2 读写都必须使用同一合约。
- 不允许仅以 URI、文件名或 parentId 作为 L2 key。
- 生成任务完成后，回填前应确认当前媒体项 metadata 仍与任务 key 一致。
- L2 文件名应由安全 hash 派生，不直接拼接原始 path。
- 文件夹视频封面也必须使用同一 key，不能单独创建封面专用 key。

### 5.3 缓存目录

L2 缓存目录固定为：

```text
cacheDir/thumbnails
```

维护要求：

- 该目录只表达项目生成的视频缩略图 L2 缓存。
- 清理策略只能作用于该目录内文件。
- 不应与 Coil disk cache 目录混用。

## 6. 缓存兼容

### 6.1 `MediaItem.thumbnailPath` 兼容

- 旧数据没有 `thumbnailPath` 时，UI 继续走 Coil fallback。
- 后台生成成功后再回填，不要求首次加载时必须存在。
- 回填失败不影响媒体条目本身展示和播放。

### 6.2 `MediaFolder` 新字段兼容

`MediaFolder` 增加封面视频 metadata 后，`FolderCache` JSON 需要保持向后兼容：

- 新字段应可为空或有安全默认值。
- 读取旧缓存时，如果缺少新字段，文件夹列表仍应可显示。
- 缺少封面视频 metadata 时，只影响 L2 复用，不应导致崩溃。
- 下次 repository 刷新后可写入包含新字段的新缓存。

### 6.3 L2 旧文件兼容

如果 L2 key 合约不变，旧 L2 缓存可以继续命中。

如果未来必须调整 key 合约，应：

1. 增加版本前缀或独立目录。
2. 保留旧目录读取或允许自然淘汰。
3. 避免在启动时一次性全量迁移，防止 I/O 峰值。

## 7. 备选方案 (Alternatives Considered)

### 7.1 只依赖 Coil 缓存

优点：实现简单，UI 请求即可显示。

缺点：

- 文件夹封面、媒体网格、Reader 预热之间缺少项目级 key 合约。
- 不方便将结果回填到 `MediaItem.thumbnailPath`。
- 缓存命中与失效策略由图片加载管线主导，不适合作为业务 metadata。

结论：不采用。

### 7.2 Repository 直接生成所有视频缩略图

优点：数据返回时 metadata 最完整。

缺点：

- Repository 查询会被 Bitmap 解码和磁盘写入拖慢。
- 首次进入大文件夹时容易阻塞数据流。
- Repository 职责从数据发现扩展到重型媒体处理，边界变差。

结论：不采用；Repository 只提供 key 输入，生成由 ViewModel/ThumbnailManager 调度。

### 7.3 文件夹封面使用独立缓存 key

优点：可以为文件夹封面定制尺寸或策略。

缺点：

- 同一视频会产生媒体网格 L2 与文件夹封面 L2 两份缓存。
- 文件变化失效逻辑重复。
- 增加缓存清理和兼容复杂度。

结论：不采用；文件夹视频封面复用统一 L2 key。

## 8. 影响 (Consequences)

### 8.1 正向影响

- 媒体网格和文件夹封面可以复用同一视频缩略图文件。
- `thumbnailPath` 回填后，后续 UI 重组不必再次抽帧。
- 文件变化后可通过 `dateModified/size` 自动失效旧缩略图。
- 旧缓存缺字段时能安全降级。

### 8.2 负向影响 / 成本

- `MediaFolder` 数据模型和 JSON 缓存格式增加字段。
- ViewModel 需要管理后台任务、失败降级和状态回填。
- 首次进入大量视频文件夹时，后台预热仍可能带来 I/O 与解码压力，需要限流或去重。
- 回填导致列表引用变化，Compose Effect key 必须避免依赖整个 `mediaItems` 引用触发昂贵操作。

## 9. 实施注意事项

- 回填 `thumbnailPath` 时使用不可变 copy，不原地修改列表元素。
- 生成失败应局部吞吐并记录可调试信息，不中断整个列表加载。
- 对同一 L2 key 的并发任务做去重。
- 文件夹封面 metadata 缺失时走安全 fallback，不阻塞文件夹列表。
- 不在 release 日志中输出完整用户文件路径。
- 对涉及 `cacheDir/thumbnails` 的清理逻辑做路径边界检查。

## 10. 相关文档

- `docs/video-performance-optimization.md`
- `docs/adr/ADR-002-video-thumbnail-decode.md`
