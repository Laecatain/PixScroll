# Plan: 文件扫描三大改进

## Context

当前文件扫描存在三个核心问题：
1. `getAllFolders()` 不跑 FileTreeWalk → 未索引文件（如 .vdat）不出现在文件夹列表
2. 没有 ContentObserver → 新增/删除文件后需手动刷新
3. SearchIndex 每次全量重建 → 浪费

## 改动概览

### 改动 1: getAllFolders() 增加 FileTreeWalk 补漏

**文件**: `app/src/main/java/com/example/reader/data/repository/MediaRepository.kt`

在 `getAllFolders()` 的 cursor 遍历完成后，对每个已发现的文件夹执行一次轻量 FileTreeWalk（复用 `findUnindexedFiles()` 逻辑），将未索引文件计入 `mediaCount` 并更新封面选择。

```kotlin
// 在 searchIndex.build() 之后、emit 之前
for ((parentId, acc) in folderMap) {
    if (acc.folderPath.isEmpty()) continue
    val unindexed = findUnindexedFilesLight(acc.folderPath, mediaType = null)
    if (unindexed.isNotEmpty()) {
        acc.mediaCount += unindexed.size
        // 更新封面：优先选图片
        val bestCover = unindexed.firstOrNull { !it.isVideo } ?: unindexed.first()
        if (acc.coverMimeType.startsWith("video/") && !bestCover.isVideo) {
            acc.coverId = bestCover.id  // 用 Uri.fromFile 的 path hash 做临时 ID
            acc.coverMimeType = bestCover.mime
            acc.coverPath = bestCover.path
        }
    }
}
```

新增 `findUnindexedFilesLight()` —— 只返回 `(path, mime, isVideo)` 精简结构，不 decode 宽高，不做 Uri 构建，纯粹为了计数和封面选择。复用现有的 `maxDepth(4)`、`.nomedia` 跳过、`EXCLUDED_DIRS` 排除逻辑。

**注意**: FileTreeWalk 结果也需要加入 SearchIndex。在 `getAllFolders()` 的 cursor 遍历后，对 unindexed 文件调用 `searchBuilder.add()` 补入索引。

### 改动 2: ContentObserver 监听 MediaStore 变化

**文件**: `app/src/main/java/com/example/reader/data/repository/MediaRepository.kt`

在 `AndroidMediaRepository` 中注册 ContentObserver，当 MediaStore 有变化时通过 `MutableSharedFlow` 通知 ViewModel。

```kotlin
// MediaRepository 接口新增
val mediaStoreChanges: SharedFlow<Unit>

// AndroidMediaRepository 实现
private val _mediaStoreChanges = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
override val mediaStoreChanges: SharedFlow<Unit> = _mediaStoreChanges

init {
    // ... 现有缓存加载 ...
    registerMediaObserver()
}

private fun registerMediaObserver() {
    val uri = MediaStore.Files.getContentUri("external")
    contentResolver.registerContentObserver(uri, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            _mediaStoreChanges.tryEmit(Unit)
        }
    })
}
```

**防抖设计**: ContentObserver 可能在短时间内触发多次（批量操作）。在 ViewModel 端用 `debounce(1000)` 合并信号，避免频繁全量刷新。

**文件**: `app/src/main/java/com/example/reader/ui/folderlist/FolderListViewModel.kt`

```kotlin
// init 中新增第三个 coroutine
viewModelScope.launch {
    repository.mediaStoreChanges
        .debounce(1000)
        .collect { loadFolders() }
}
```

**文件**: `app/src/main/java/com/example/reader/ui/reader/ReaderViewModel.kt`

同理，在 init 中监听 `mediaStoreChanges`，收到信号后调用 `loadMedia()`。

### 改动 3: SearchIndex 增量更新

**文件**: `app/src/main/java/com/example/reader/util/SearchIndex.kt`

新增 `upsert()` 和 `removeByIds()` 方法，支持增量更新而非全量重建。

```kotlin
/** 按 ID 原子替换或插入条目 */
fun upsert(newEntries: List<SearchableMediaEntry>) {
    if (newEntries.isEmpty()) return
    val map = entries.associateBy { it.id }.toMutableMap()
    newEntries.forEach { map[it.id] = it }
    entries = map.values.toList()
}

/** 按 ID 集合删除条目 */
fun removeByIds(ids: Set<Long>) {
    if (ids.isEmpty()) return
    entries = entries.filter { it.id !in ids }
}
```

**在 `getAllFolders()` 中的应用**:

当前每次全量重建：`searchIndex.build(searchBuilder.build())`

改为：先保存旧索引的 ID 集合，cursor 遍历构建新索引后，diff 出新增/删除/变更的条目，用 `upsert()` + `removeByIds()` 增量更新。

```kotlin
// 旧方案: searchIndex.build(searchBuilder.build())
// 新方案:
val oldIds = searchIndex.snapshot().map { it.id }.toSet()
val newEntries = searchBuilder.build()
val newIds = newEntries.map { it.id }.toSet()
val removedIds = oldIds - newIds
searchIndex.removeByIds(removedIds)
searchIndex.upsert(newEntries)
cacheDir?.let { searchIndex.saveToDisk(it) }
```

需要给 SearchIndex 新增 `snapshot()` 方法返回当前条目列表的只读视图。

## 涉及文件

| 文件 | 改动类型 |
|------|----------|
| `data/repository/MediaRepository.kt` | 修改: FileTreeWalk 补漏 + ContentObserver + SearchIndex 增量更新 |
| `util/SearchIndex.kt` | 修改: 新增 `upsert()` / `removeByIds()` / `snapshot()` |
| `ui/folderlist/FolderListViewModel.kt` | 修改: 监听 `mediaStoreChanges` 自动刷新 |
| `ui/reader/ReaderViewModel.kt` | 修改: 监听 `mediaStoreChanges` 自动刷新 |

## 验证

1. `./gradlew testDebugUnitTest` — 现有 31 个测试通过
2. 手动验证: 在文件管理器中删除/新增一个 .vdat 文件，回到 PixScroll 文件夹列表，确认计数和封面自动更新
3. 手动验证: 拍一张新照片，返回 PixScroll 文件夹列表，确认自动出现（无需手动刷新）
