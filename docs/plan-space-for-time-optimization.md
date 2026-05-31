# 用空间换时间：视频预览图 + 搜索性能优化

## 背景

当前项目存在两个性能瓶颈：

### 1. 视频预览图加载慢

- 视频进入网格时，每张预览图**按需生成**，滚动到哪张才生成哪张
- `CustomVideoFrameDecoder` 在 Coil 加载时解码一次，`ThumbnailManager.onSuccess` 又解码一次保存，同一视频加载路径上有**两次重复解码**
- `ThumbnailManager` 的 `limitedParallelism(2)` 在大量视频滚动时形成瓶颈
- 首屏加载时没有批量预热机制

### 2. 搜索慢

- 每次按键都取消前一次并重新查询，**无 debounce**
- `searchFolders()` 每次调用都执行完整 `getAllFolders()` — 全量 MediaStore 扫描 + PARENT 分组 + cover 选择，O(N) 全表遍历
- `searchMedia()` 和 `searchFolders()` 顺序执行，没有并行
- 搜索结果**不做缓存**，离开页面再回来重新查询
- 搜索不走 FileTreeWalk 回退，未索引文件搜不到

## 目标

用磁盘空间换运行时速度，具体指标：

1. **视频预览图**：进入文件夹时，后台批量预生成缩略图存储到磁盘；网格展示时直接加载已缓存的 JPG 文件，避免实时 MediaMetadataRetriever 解码
2. **搜索**：建立搜索索引缓存（文件夹索引 + 文件名倒排索引），搜索时先查缓存再查 MediaStore；加入 debounce 减少无效查询

---

## 实现方案

### 一、视频缩略图批量预生成 (`ThumbnailBackfillService`)

**思路**：进入文件夹时，启动一个后台协程批量生成当前文件夹所有视频的缩略图并写入 `cacheDir/thumbnails/`。网格渲染时 `AsyncGridImage` 直接命中 `thumbnailPath`，跳过 `MediaMetadataRetriever` 解码。

```
进入文件夹 → 读取当前文件夹所有视频列表
         → 启动后台协程遍历视频列表
              → 对每个尚未缓存的视频，调用 ThumbnailManager.generateThumbnailSync()
              → 写入 cacheDir/thumbnails/{md5}.jpg
              → 更新 MediaItem.thumbnailPath
         → (并行) 网格渲染，已有 thumbnailPath 的项直接加载 JPG
         → 后台批次完成后，Phase 3 emission 更新所有 MediaItem.thumbnailPath
```

**关键设计决策**：

1. **触发时机**：`ReaderViewModel.loadMedia()` 的 Phase 1（MediaStore）→ Phase 2（FileTreeWalk）之后，增加 Phase 3：检查所有视频项的 `thumbnailPath`，对缺失的批量生成。生成完成后重新 emit 列表。
2. **并发控制**：`limitedParallelism(4)`（比当前 2 稍高），使用 `Channel` 或 `Flow` 做有序处理，避免 I/O 打满。
3. **去重**：利用现有 MD5 key 机制，已存在的 `.jpg` 跳过。
4. **优先级**：可见项优先（`LazyGrid`/`LazyColumn` 中当前 visible 的 item 先解码），后台项延后。可以使用 `snapshotFlow { listState.layoutInfo.visibleItemsInfo }` 获取可见范围。
5. **生命周期**：ViewModelScope 启动，页面销毁时自动取消。`ThumbnailManager` 本身已有 `coroutineContext` 可绑定。
6. **限速**：每次最多处理 N 个视频（如 50），避免一次性占用大量 CPU/I/O。超出的在用户浏览时按需生成（已有逻辑）。

**涉及文件**：
- 新建：`util/ThumbnailBackfillManager.kt`
- 修改：`ui/reader/ReaderViewModel.kt`（在 loadMedia() 中增加 Phase 3）
- 修改：`util/ThumbnailManager.kt`（可能调整并发度或暴露批量接口）
- 修改：`ui/common/AsyncGridImage.kt`（保持现有缓存命中路径不变）

### 二、消除视频缩略图重复解码

**问题**：`AsyncGridImage` 的 `onSuccess` 中调用 `thumbnailManager.save()`，而 `CustomVideoFrameDecoder.decode()` 刚刚解码了一帧。同一视频同一帧解码了两次。

**修复方案**：让 Coil 解码完成后直接将 Bitmap 写入缓存，不再二次解码。

**选项 A（推荐）**：在 `CustomVideoFrameDecoder.Factory.create()` 中，解码完成后检查是否需要写入 ThumbnailManager 缓存，直接在当前 decode 路径内完成写入，避免 `onSuccess` 回调触发第二个 `MediaMetadataRetriever`。

**选项 B（简化）**：在 `AsyncGridImage` 的 `onSuccess` 中，从 `ImageRequest.Result` 拿到的 `drawable` 如果是 `BitmapDrawable`，直接 `thumbnailManager.save(bitmap, file)`，不经过第二次 `generateThumbnail`。只需修改 `AsyncGridImage` 的 onSuccess 回调。

**涉及文件**：
- 修改：`ui/common/AsyncGridImage.kt`
- 或修改：`util/CustomVideoFrameDecoder.kt`

### 三、搜索加入 Debounce

**问题**：每次按键都触发查询，连续输入 "camera" 触发 6 次查询。

**修复**：在 `SearchViewModel.onQueryChange()` 中，用 `delay(300)` 实现 debounce，而不是立即启动搜索。按键在 300ms 内重复触发则前一个 job 被取消，debounce 会重置计时器。

```kotlin
fun onQueryChange(query: String) {
    searchJob.cancel()
    searchJob = viewModelScope.launch {
        delay(300) // debounce
        // ... existing search logic
    }
}
```

**涉及文件**：
- 修改：`ui/search/SearchViewModel.kt`

### 四、搜索文件夹用缓存替代全量扫描

**问题**：`searchFolders()` 每次都跑一遍完整 `getAllFolders()`（全 MediaStore 扫描 + PARENT 分组 + cover 选择）。

**修复方案——增量更新 + 内存缓存**：

1. 在 `MediaRepository` 中维护一个**内存缓存** `allFoldersCache: List<MediaFolder>`。
2. 缓存初始化：首次调用时全量扫描（同现有逻辑），存入缓存。
3. 缓存刷新策略（**关键设计决策**，选一种）：
   - **策略 A（推荐——ContentObserver 监听）**：注册 `ContentObserver` 监听 `MediaStore.Files.EXTERNAL_CONTENT_URI`，收到变更通知后标记缓存为 dirty，下次搜索时自动刷新。
   - **策略 B（简单——定时刷新 + 写回 FolderCache）**：每次搜索先查缓存，缓存为空或超过 TTL（如 30s）才重新扫描。同时将缓存序列化到 FolderCache JSON。
   - **策略 C（惰性——只在返回时刷新）**：从搜索/文件夹列表页面返回时触发刷新（通过 `LaunchedEffect` 或 `DisposableEffect`）。
4. 搜索结果过滤直接作用在缓存上：`cache.filter { it.folderName.contains(query, true) }`，这是 O(1) 操作。

**优化 `searchMedia()`**：建立文件名索引缓存。

1. 在 `MediaRepository` 中维护一个**文件名索引**：`fileNameIndex: Map<Long, List<String>>` keyed 按 parentId，value 是文件名列表。
2. 搜索时先查 `fileNameIndex.values.flatten().filter { it.contains(query, true) }` 命中文件名，然后再去 MediaStore 拿完整 cursor 数据。
3. 索引的构建可以与 `getAllFolders()` 或 `getMediaByFolder()` 的 Phase 1 合并，不需要额外全量扫描。

**涉及文件**：
- 修改：`data/repository/MediaRepository.kt`（增加缓存字段、ContentObserver、索引）
- 修改：`util/FolderCache.kt`（可能扩展以支持缓存序列化）
- 修改：`ui/search/SearchViewModel.kt`（无需改动，只依赖 repository 接口）

### 五、搜索并行化

**问题**：`searchFolders()` + `searchMedia()` 顺序执行。

**修复**：使用 `async/await` 并行执行两个 Repository 调用。

```kotlin
val foldersDeferred = async { repository.searchFolders(query).first() }
val mediaDeferred = async { repository.searchMedia(query).first() }
val folders = foldersDeferred.await()
val media = mediaDeferred.await()
```

**涉及文件**：
- 修改：`ui/search/SearchViewModel.kt`

### 六、搜索结果缓存（可选增强）

**问题**：离开搜索页面再回来，之前的结果丢失。

**修复**：在 `SearchViewModel` 中维护一个 `query -> SearchState` 的 LRU 缓存（可用 `LinkedHashMap` 实现，最大容量 20 条）。每次搜索前先查缓存，命中则直接显示，后台刷新。

**涉及文件**：
- 修改：`ui/search/SearchViewModel.kt`

---

## 实施步骤（推荐顺序）

### Step 1: 搜索 Debounce（低风险，立竿见影）
修改 `SearchViewModel.onQueryChange()`，加入 `delay(300)`。

### Step 2: 搜索并行化（低风险）
`searchFolders` 和 `searchMedia` 用 `async/await` 并行。

### Step 3: 文件夹搜索结果缓存（中等风险，效果最大）
在 `MediaRepository` 中实现 `allFoldersCache` + ContentObserver 监听 + `searchFolders` 改为查缓存。

### Step 4: 文件名索引缓存（中等风险）
在 `MediaRepository` 中维护文件名索引，`searchMedia` 先查索引。

### Step 5: 消除重复解码（低风险）
在 `AsyncGridImage.onSuccess` 或 `CustomVideoFrameDecoder.decode` 中写入缓存，避免二次解码。

### Step 6: 视频缩略图批量预生成（高风险，改动最大）
新建 `ThumbnailBackfillManager`，在 `ReaderViewModel.loadMedia()` 的 Phase 1+2 之后增加 Phase 3 批量生成。

### Step 7: 搜索结果 LRU 缓存（低风险，锦上添花）

---

## 风险与权衡

| 方案 | 额外磁盘占用 | 启动延迟增加 | 内存占用增加 | 复杂度 |
|------|-------------|-------------|-------------|--------|
| 视频缩略图预生成 | 每个视频 ~20-50KB | 进入文件夹时有短暂后台 CPU 峰值 | 无 | 中 |
| 搜索结果缓存 | 无 | 无 | 文件夹列表 ~几百 KB | 低 |
| 文件名索引 | 无 | 首次构建需遍历一次 | 文件名列表 ~几十 KB | 低 |
| 搜索 LRU 缓存 | 无 | 无 | ~几十 KB | 低 |
| 视频重复解码修复 | 无 | 无 | 无 | 低 |

**总体磁盘占用预估**：1000 个视频 × 30KB ≈ 30MB，在 `cacheDir` 内，系统可自动清理。

**总体收益预估**：
- 视频预览图：从 ~500ms/张（MediaMetadataRetriever 解码 + JPG 写入）降为 ~50ms/张（磁盘 JPG 直接加载），快 ~10x
- 文件夹搜索：从 ~500ms-2s（全量 MediaStore 扫描）降为 ~5ms（内存缓存过滤），快 ~100-400x
- 文件名搜索：从 ~200ms（LIKE 查询）降为 ~10ms（索引过滤），快 ~20x

---

## 验收标准

- [ ] 进入含 100+ 视频的文件夹，网格中视频预览图在 1s 内显示（无需滚动等待解码）
- [ ] 搜索 "camera" 连续输入，只触发最后一次查询（debounce 生效）
- [ ] 搜索文件夹结果在 100ms 内返回（缓存命中）
- [ ] 搜索文件结果在 200ms 内返回（索引命中）
- [ ] 搜索和文件夹列表在 MediaStore 内容变更后（如新拍照）能正确反映变化
- [ ] 所有单元测试通过，新方案覆盖率达到 80%+
- [ ] 不需要额外权限
