# 视频预览图与进度拖动性能优化

## 1. 背景与目标

PixScroll 的视频体验涉及两个高频路径：

1. **视频预览图链路**：媒体网格、搜索结果、文件夹封面等界面需要快速显示稳定的视频缩略图。
2. **视频进度拖动链路**：视频播放页需要在用户拖动 Slider 时保持 UI 响应，并在松手后快速 seek 到合理位置。

历史实现中，视频缩略图通常依赖 UI 可见后再触发抽帧；文件夹封面主要复用已有图片封面字段，缺少可复用的视频封面 metadata；进度条拖动若直接映射为高频 seek，容易造成播放器解码、缓冲和 UI 状态之间互相抢占。

本优化的目标是：

- **减少首屏缩略图空白与重复抽帧**：通过 L2 缩略图缓存与 metadata 回填，让媒体网格、Reader 预热和文件夹封面共享同一份视频缩略图结果。
- **明确视频缩略图 key 合约**：以 `path + dateModified + size` 表示同一文件版本，避免文件内容变化后误用旧封面。
- **降低文件夹视频封面生成成本**：为 `MediaFolder` 增加封面视频 metadata，使 ViewModel/ThumbnailManager 能直接复用 L2 缓存，而不是只能依赖 URI 或重复走 Coil 抽帧。
- **改善视频 seek 交互稳定性**：拖动中只更新 UI 进度，松手后只执行一次 seek；播放器使用更短缓冲与 `SeekParameters.CLOSEST_SYNC`，优先换取响应速度。
- **保留可回滚路径**：所有策略应能通过移除 metadata 回填、禁用预热或恢复播放器默认 seek/buffer 策略进行回滚。

本文是维护文档，不记录未验证的绝对性能数字。后续真机验证应使用本文末尾的模板记录设备、素材、前后对比和观测结果。

## 2. 旧链路与瓶颈

### 2.1 旧缩略图链路

旧链路可以抽象为：

```text
MediaStore / FileTreeWalk
  → MediaItem(uri, path, mimeType, size, dateModified, thumbnailPath?)
  → UI 可见
  → AsyncImage / Coil 视频抽帧
  → UI 显示缩略图
```

主要瓶颈：

- **按需抽帧偏晚**：只有条目进入可见区域或被 Compose/Coil 请求时，才开始视频帧解码；首屏大量视频时容易出现占位图停留。
- **缓存入口分散**：Coil 自身磁盘缓存、项目 L2 缩略图缓存、MediaStore metadata 之间职责不清时，容易重复抽帧。
- **缺少稳定封面 metadata**：文件夹列表如果只知道 `coverImageUri`，但不知道封面视频的 `path/dateModified/size`，就无法按 ThumbnailManager 的 L2 key 合约精准复用缓存。
- **Phase 2 合并带来的更新频率**：MediaStore 结果先到、FileTreeWalk fallback 后到时，列表可能多次合并更新；如果缩略图回填直接干扰滚动/索引状态，容易造成额外重组或位置跳动。

### 2.2 旧进度拖动链路

旧交互若将 Slider 的每次 `onValueChange` 都映射为播放器 seek，会形成：

```text
用户拖动 Slider
  → 每一帧产生一个进度值
  → 多次 seekTo()
  → ExoPlayer 反复寻找关键帧/解码/缓冲
  → 播放器 position 回调又反写 UI
```

主要瓶颈与风险：

- **seek 请求过密**：拖动一秒内可能产生多次 seek，播放器无法稳定完成前一个 seek 就被新请求打断。
- **解码压力不必要**：用户拖动过程中的中间位置通常只是预览意图，不需要每一帧都真实 seek。
- **状态反写冲突**：播放器当前进度、Slider 临时值、用户拖动状态如果没有锁，会出现 UI 回弹或跳动。
- **精确 seek 成本高**：若强制精确到非关键帧位置，播放器可能需要额外解码，响应速度下降。

## 3. 媒体网格缩略图链路

### 3.1 目标链路

媒体网格的视频缩略图采用两级策略：

```text
MediaItem(thumbnailPath?)
  ├─ 有 thumbnailPath
  │   └─ AsyncGridImage 优先加载本地 L2 文件
  │       └─ 直接显示，避免重新抽帧
  │
  └─ 无 thumbnailPath 且 mimeType 为 video/*
      └─ AsyncGridImage fallback 使用 Coil 抽帧
          └─ 成功后写入 ThumbnailManager L2
              └─ ReaderViewModel 后续生成/回填 thumbnailPath
```

约定：

- `ThumbnailManager` 的 L2 key 为：`path + dateModified + size`。
- L2 缓存目录为：`cacheDir/thumbnails`。
- `AsyncGridImage(MediaItem)` 必须优先使用 `MediaItem.thumbnailPath`。
- 当 `thumbnailPath` 缺失时，视频 fallback 可以走 Coil 抽帧，并将结果写入 L2。
- `ReaderViewModel` 负责在后台生成并回填缺失的视频 `thumbnailPath`，让同一列表后续重组、Reader 预热和再次进入页面时能复用结果。

### 3.2 责任边界

| 组件 | 职责 | 不应承担的职责 |
|------|------|----------------|
| `MediaRepository` | 提供媒体基础 metadata：URI、path、mimeType、size、dateModified、folder 等 | 不直接做大量 Bitmap 解码 |
| `ThumbnailManager` | 根据 key 合约生成、读取、写入 L2 缩略图 | 不持有 UI 状态，不直接驱动 Compose |
| `ReaderViewModel` | 调度缺失视频缩略图生成，回填 `thumbnailPath`，保持列表状态不可变更新 | 不把抽帧细节散落到多个 Composable |
| `AsyncGridImage(MediaItem)` | 按 `thumbnailPath → Coil fallback` 顺序显示缩略图 | 不自行定义另一套缓存 key |
| Coil | 作为 UI fallback 抽帧和图片加载管线 | 不替代项目 L2 key 合约 |

### 3.3 回填策略

回填应遵循以下原则：

1. **只回填缺失项**：已有 `thumbnailPath` 的 `MediaItem` 不重复生成。
2. **仅针对视频**：按 `mimeType` 或项目既有视频判断逻辑过滤。
3. **后台调度**：抽帧与文件写入不应阻塞主线程。
4. **不可变更新**：生成结果后通过复制列表元素回填，避免原地修改状态对象。
5. **按 URI/path 匹配**：Phase 2 合并后仍应尽量保持当前阅读位置，缩略图字段变化不应重置索引。
6. **失败可降级**：单个视频抽帧失败只影响该项缩略图，不应中断整个列表加载。

建议的状态更新语义：

```text
oldItems
  → 生成部分 thumbnailPath
  → newItems = oldItems.map { item -> item.copy(thumbnailPath = generatedPath) }
  → _state.value = _state.value.copy(mediaItems = newItems)
```

### 3.4 避免重复抽帧

重复抽帧的常见来源：

- `AsyncGridImage` 可见时走 Coil 抽帧；同时 ViewModel 后台预热也在生成同一视频。
- 文件夹封面与媒体网格各自为同一视频生成不同缓存文件。
- 文件 dateModified 或 size 变化后，仍复用旧 `thumbnailPath`。

防护策略：

- 使用统一 L2 key：`path + dateModified + size`。
- 写入前先查询 L2 是否已存在。
- 同一进程内可按 key 做轻量去重，避免并发生成同一文件。
- 文件夹封面必须带上足够 metadata，以便复用同一 key。
- 回填时只写入当前 key 对应的路径；如果媒体 metadata 已变化，丢弃旧任务结果。

## 4. 文件夹视频封面链路

### 4.1 为什么文件夹需要视频封面 metadata

文件夹列表通常只展示一个代表封面。如果封面来自图片，`coverImageUri` 足以交给图片加载器处理；但如果封面来自视频，仅有 URI 不足以复用 `ThumbnailManager` 的 L2 缓存，因为 L2 key 需要：

- 文件路径 `path`
- 修改时间 `dateModified`
- 文件大小 `size`

因此 `MediaFolder` 需要增加封面视频 metadata，至少能表达：

```text
coverVideoPath
coverVideoDateModified
coverVideoSize
coverVideoMimeType 或是否为视频的标记
```

字段命名以实际代码为准，但语义必须满足 L2 key 合约。

### 4.2 目标链路

```text
AndroidMediaRepository 查询文件夹
  → 选择封面媒体
  → MediaFolder 携带 coverImageUri + cover video metadata
  → FolderListViewModel / ThumbnailManager 查询 L2
      ├─ 命中：回填/展示 L2 thumbnailPath
      └─ 未命中：后台生成 L2，再回填文件夹封面
  → FolderListScreen 显示封面
```

### 4.3 封面选择原则

- 优先沿用项目已有的文件夹封面选择规则，避免改变用户对封面排序的预期。
- 如果封面媒体是视频，则必须记录足够的 L2 key metadata。
- 如果 metadata 不完整，允许退化为 Coil fallback，但不应写入不符合 key 合约的 L2 文件。
- 如果视频封面生成失败，可显示占位图或选择下一个可用媒体作为封面，具体策略以 UI 体验为准。

### 4.4 与 FolderCache 的兼容

`MediaFolder` 增加字段会影响 `FolderCache` 的 JSON 缓存格式。兼容策略：

- 新字段应可为空或有安全默认值，旧缓存读取后仍能显示基础文件夹列表。
- 读取旧缓存时，不要求立即具备视频封面 metadata；下次 repository 刷新后再补齐。
- 写入新缓存时包含新增字段；降级到旧版本应用时，旧版本如果忽略未知字段，应不影响基础读取。
- 不应因为缺少新增字段而清空整个文件夹缓存。

## 5. 进度条 seek 策略

### 5.1 交互原则

`VideoPlayerScreen` 的 Slider 分为两个状态：

1. **拖动中**：只更新 UI 临时进度，不调用播放器 seek。
2. **拖动结束**：根据最终位置调用一次 `seekTo()`。

目标链路：

```text
onValueChange(value)
  → isDragging = true
  → sliderValue = value
  → UI 显示临时进度
  → 不 seek

onValueChangeFinished()
  → targetMs = sliderValue 转换为播放时间
  → player.seekTo(targetMs)
  → isDragging = false
  → 恢复播放器 position 驱动 UI
```

### 5.2 状态锁

拖动期间应避免播放器 position 回调覆盖 Slider 临时值：

```text
if (isDragging) {
    UI 显示 sliderValue
    忽略或延后播放器 position 对 Slider 的反写
} else {
    UI 跟随 player.currentPosition
}
```

这样可以避免：

- 用户拖动到 80%，播放器 position 仍在 20%，UI 被反写回 20%。
- 每次 position tick 都触发新的 seek 或重组冲突。
- 松手前的中间值制造大量无意义 seek。

### 5.3 播放器策略

`VideoPlayerFactory` 将使用：

- **较短缓冲参数**：减少 seek 后等待大量缓冲完成的时间，使本地/短视频切换更轻。
- **`SeekParameters.CLOSEST_SYNC`**：seek 到最接近的同步帧，优先速度和交互响应，而不是逐帧精确。

这意味着：

- 松手后实际落点可能与 Slider 目标时间存在小幅偏差。
- 对视频预览/浏览场景，这种偏差通常比长时间等待精确 seek 更可接受。
- 如果未来增加逐帧剪辑、精确截图等功能，应为该场景单独提供精确 seek 策略，而不是复用浏览场景策略。

## 6. 关键参数

| 参数 / 合约 | 当前设计 | 维护注意事项 |
|-------------|----------|--------------|
| L2 key | `path + dateModified + size` | 三者任一变化都视为新文件版本 |
| L2 目录 | `cacheDir/thumbnails` | 只存项目生成的视频缩略图，不混用其他缓存语义 |
| 网格加载顺序 | `thumbnailPath → Coil video fallback` | `thumbnailPath` 优先级不能被 UI fallback 覆盖 |
| 回填责任 | `ReaderViewModel` 调度生成/回填缺失视频 `thumbnailPath` | 回填应不可变更新，不能重置当前索引 |
| 文件夹封面 | `MediaFolder` 增加封面视频 metadata | metadata 必须满足 L2 key 合约 |
| seek 触发 | Slider 松手后一次 seek | 拖动中不做每帧 seek |
| seek 精度 | `SeekParameters.CLOSEST_SYNC` | 速度优先；不适合逐帧编辑场景 |
| 缓冲策略 | `VideoPlayerFactory` 使用较短缓冲 | 需在不同码率/容器/设备上真机验证 |

## 7. 风险

### 7.1 缩略图链路风险

- **旧缓存兼容风险**：`MediaFolder` 新字段为空时，文件夹封面可能无法立即复用 L2。
- **文件版本误判风险**：如果某些文件系统或 MediaStore 返回的 `dateModified` 不稳定，可能导致缓存命中率下降。
- **并发抽帧风险**：UI fallback 与 ViewModel 预热同时请求同一视频，可能造成重复工作。
- **I/O 压力风险**：大量视频首次进入时同时生成 L2，会增加磁盘写入和解码压力。
- **状态更新风险**：缩略图回填导致列表引用变化，若 Composable 的 Effect key 选择过宽，可能干扰滚动或 Slider 状态。

### 7.2 播放器链路风险

- **seek 落点偏差**：`CLOSEST_SYNC` 不保证精确到目标毫秒。
- **短缓冲卡顿风险**：高码率、慢存储、网络 URI 或异常容器可能需要更长缓冲。
- **UI/播放器不同步**：如果拖动锁释放时机错误，松手后可能出现短暂跳动。
- **边界值风险**：duration 未知、0 时长、播放器未 ready 时，需要避免除零和非法 seek。

## 8. 安全注意事项

本优化涉及文件路径、URI、缓存文件和 MediaStore metadata，属于项目约定中的存储/文件敏感区域。维护时需注意：

- 不信任外部路径输入；所有 path 应来自 MediaStore 或受控 FileTreeWalk 结果。
- 不把原始 path 直接拼接为文件名；L2 文件名应由安全 hash/key 派生，避免路径穿越。
- 写入 `cacheDir/thumbnails` 时确保目标路径仍位于该目录内。
- 不在日志中输出完整用户文件路径，除非是本地调试且不会进入发布日志。
- 处理 `content://` URI 时使用 Android API 提供的访问方式，不假设可直接转成本地文件。
- 抽帧失败应捕获并降级，不能让单个损坏视频导致列表加载失败或播放器崩溃。
- 对缓存清理或回滚脚本保持保守，不递归删除非 `cacheDir/thumbnails` 的目录。

## 9. 回滚策略

### 9.1 缩略图回滚

如发现缩略图预热或回填引入严重问题，可按以下顺序回滚：

1. **禁用 ViewModel 后台生成/回填**：保留 `AsyncGridImage` 的 Coil fallback，避免功能不可用。
2. **忽略 `MediaFolder` 视频封面 metadata**：文件夹封面退回旧封面逻辑或占位图。
3. **保留 L2 读取但停止写入**：用于确认问题是否来自抽帧写入压力。
4. **清理 L2 缓存**：仅删除 `cacheDir/thumbnails` 下由项目生成的文件。
5. **恢复旧数据模型读取兼容**：确保旧 JSON 缓存不会因新增字段缺失而崩溃。

### 9.2 播放器回滚

如发现短缓冲或 `CLOSEST_SYNC` 在目标设备上体验变差，可按以下顺序回滚：

1. 恢复默认或较保守的 LoadControl 缓冲参数。
2. 将 seek 参数恢复为默认策略。
3. 保留“拖动中不 seek、松手 seek 一次”的 UI 策略；该策略通常是减少无意义工作的核心，不应作为第一回滚项。
4. 如必须临时恢复每次拖动 seek，应增加节流/防抖，并记录原因。

## 10. 测试/真机验证记录模板

不要在未验证前填写绝对性能数字。每次优化或回归验证建议复制以下模板到 issue、PR 描述或测试记录中。

### 10.1 环境

| 项目 | 记录 |
|------|------|
| 日期 |  |
| 测试人 |  |
| 设备型号 |  |
| Android 版本 / API |  |
| PixScroll 构建版本 / commit |  |
| 存储位置 | 内置存储 / SD 卡 / 其他 |
| 是否冷启动 | 是 / 否 |
| 是否清理 `cacheDir/thumbnails` | 是 / 否 |

### 10.2 素材集

| 项目 | 记录 |
|------|------|
| 文件夹数量 |  |
| 视频数量 |  |
| 图片数量 |  |
| 视频格式 | mp4 / mkv / webm / 其他 |
| 视频分辨率范围 |  |
| 文件大小范围 |  |
| 是否包含损坏/异常视频 | 是 / 否，说明： |
| 是否包含未被 MediaStore 索引文件 | 是 / 否 |

### 10.3 缩略图验证

| 指标 | 优化前 | 优化后 | 备注 |
|------|--------|--------|------|
| 媒体网格首屏缩略图全部稳定显示耗时 |  |  | 用同一设备和素材对比 |
| 首次进入文件夹的视频 L2 生成数量 |  |  | 记录日志或调试计数 |
| 再次进入文件夹的 L2 命中数量 |  |  | 需不清理缓存 |
| 文件夹视频封面是否复用 L2 |  |  | 是 / 否 / 部分 |
| 滚动网格时是否出现明显卡顿 |  |  | 主观 + profiler |
| 是否有抽帧失败但 UI 可降级 |  |  | 记录样本文件类型 |

### 10.4 进度拖动验证

| 指标 | 记录 |
|------|------|
| 拖动中是否只更新 UI、不触发连续 seek |  |
| 松手后 seek 次数 |  |
| 松手到画面稳定的主观体感 |  |
| 实际落点与目标位置是否可接受 |  |
| 高码率视频是否出现缓冲不足 |  |
| duration 未知/0 时长样本是否安全 |  |
| 横竖屏切换后 Slider 状态是否正常 |  |

### 10.5 回归范围

- [ ] 媒体网格图片缩略图仍正常显示。
- [ ] 媒体网格视频缩略图首次进入与二次进入均正常。
- [ ] 文件夹列表图片封面仍正常。
- [ ] 文件夹列表视频封面可显示或安全降级。
- [ ] 搜索结果中的视频缩略图正常。
- [ ] Reader 中 Phase 1/Phase 2 合并不重置当前阅读位置。
- [ ] 视频播放页拖动 Slider 不出现连续跳动。
- [ ] 返回、旋转、后台恢复后播放器状态正常。
- [ ] 损坏视频不会导致崩溃。

## 11. 相关 ADR

- `docs/adr/ADR-005-video-thumbnail-warmup-and-folder-cover.md`
- `docs/adr/ADR-006-video-seek-buffer-policy.md`
