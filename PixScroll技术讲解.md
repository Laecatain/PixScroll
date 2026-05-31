# PixScroll 技术架构详解

> 一份面向开发者的完整技术讲解，覆盖从底层数据引擎到 UI 渲染管线的所有关键设计。

---

## 目录

1. [概览](#1-概览)
2. [构建与依赖](#2-构建与依赖)
3. [架构设计](#3-架构设计)
4. [数据层](#4-数据层)
5. [导航系统](#5-导航系统)
6. [ViewModel 层](#6-viewmodel-层)
7. [UI 层 — 阅读器](#7-ui-层--阅读器)
8. [UI 层 — 其他界面](#8-ui-层--其他界面)
9. [主题系统](#9-主题系统)
10. [偏好存储](#10-偏好存储)
11. [图片加载与缓存策略](#11-图片加载与缓存策略)
12. [视频缩略图引擎](#12-视频缩略图引擎)
13. [测试策略](#13-测试策略)
14. [性能关键路径](#14-性能关键路径)

---

## 1. 概览

PixScroll 是一款纯本地的图片/视频媒体浏览器，运行于 Android 8.0+（API 26+），完全离线，不依赖任何网络请求。

| 属性 | 值 |
|------|-----|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 架构 | MVVM，无 DI 框架（手动构造） |
| 图片加载 | Coil 2.6.0 |
| 视频播放 | ExoPlayer (Media3 1.3.1) |
| 偏好存储 | DataStore Preferences |
| 编译 SDK | 34 |
| 最低 SDK | 26 |
| JVM 目标 | 17 (JDK 17 Temurin) |

---

## 2. 构建与依赖

### 2.1 核心依赖

```kotlin
// Compose BOM — 统一管理所有 Compose 版本
platform("androidx.compose:compose-bom:2025.03.00")

// Material 3
androidx.compose.material3:material3

// Navigation
androidx.navigation:navigation-compose:2.7.7

// 图片加载
io.coil-kt:coil-compose:2.6.0
io.coil-kt:coil-video:2.6.0        // VideoFrameDecoder — 视频首帧作为缩略图

// 视频播放
androidx.media3:media3-exoplayer:1.3.1
androidx.media3:media3-ui:1.3.1

// 偏好存储
androidx.datastore:datastore-preferences:1.1.1

// 权限
com.google.accompanist:accompanist-permissions:0.34.0
```

### 2.2 测试依赖

```kotlin
junit:junit:4.13.2
app.cash.turbine:turbine:1.1.0                     // StateFlow 测试
org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1  // runTest / TestDispatcher
```

### 2.3 Coil ImageLoader 全局配置

在 `ReaderApp.kt` 中实现 `ImageLoaderFactory`，全局配置 Coil：

```kotlin
override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)           // 25% 可用内存
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(100L * 1024 * 1024) // 固定 100MB
        }
        .components { add(CustomVideoFrameDecoder.Factory()) }
        .crossfade(true)                        // 全局 150ms 淡入
        .bitmapConfig(Bitmap.Config.RGB_565)    // 无 alpha 通道，省 50% 内存
        .build()
}
```

**设计要点：**
- `RGB_565` — 缩略图场景不需要透明度通道，相比 ARGB_8888 节省一半像素内存
- 磁盘缓存固定 100MB，避免按百分比计算导致的设备差异
- `CustomVideoFrameDecoder` 注册为 Coil 组件，在图片加载管线中拦截视频 URI 并解码首帧

---

## 3. 架构设计

### 3.1 整体分层

```
┌─────────────────────────────────────────────────┐
│                  UI Layer (Compose)               │
│  FolderListScreen  MediaGridScreen  ReaderScreen  │
│  SearchScreen   SettingsScreen   VideoPlayerScreen │
├─────────────────────────────────────────────────┤
│               ViewModel Layer                     │
│  FolderListVM   ReaderVM   SearchVM   SettingsVM  │
│  (StateFlow<UiState> → Compose collectAsState)    │
├─────────────────────────────────────────────────┤
│               Repository Layer                    │
│        AndroidMediaRepository : MediaRepository   │
├─────────────────────────────────────────────────┤
│         Android 系统 API                          │
│  MediaStore.Files  │  FileTreeWalk  │  DataStore  │
└─────────────────────────────────────────────────┘
```

### 3.2 数据流向

```
ViewModel 调用 Repository 方法
        ↓
Repository 通过 Flow 发射数据（在 Dispatchers.IO 上执行）
        ↓
ViewModel 更新 MutableStateFlow<State>
        ↓
Compose 通过 collectAsState() 订阅 StateFlow
        ↓
UI 重组渲染
```

**单向数据流**：UI 事件 → ViewModel 方法调用 → State 更新 → UI 重组。UI 层永远不直接修改状态。

### 3.3 ViewModel 构造模式

项目不依赖 Hilt/Koin 等 DI 框架，ViewModel 通过内部 `Factory` 类手动构造：

```kotlin
class ReaderViewModel(
    private val repository: MediaRepository,
    private val parentId: Long,
    private val initialIndex: Int = 0,
    private val application: Application? = null,
    private val mediaType: Int? = null
) : ViewModel() {

    class Factory(
        private val application: Application,
        private val parentId: Long,
        private val initialIndex: Int = 0,
        private val mediaType: Int? = null
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                AndroidMediaRepository(application.contentResolver),
                parentId, initialIndex, application, mediaType
            ) as T
        }
    }
}
```

`application = null` 设计用于测试环境——跳过 DataStore 初始化，避免 JVM 单测依赖 Android 框架。

---

## 4. 数据层

### 4.1 核心数据模型

```kotlin
@Immutable
data class MediaItem(
    val uri: Uri?,                    // 可为 null（测试便利）
    val name: String,                 // 文件名
    val mimeType: String,             // MIME 类型
    val size: Long,                   // 字节
    val dateModified: Long,           // Unix 时间戳（秒）
    val folderPath: String,           // 完整路径（来自 MediaStore.DATA 或 File.absolutePath）
    val parentId: Long = 0,           // MediaStore PARENT 列
    val orientation: Int = 0,         // 旋转角度（0/90/180/270）
    val mediaType: Int = 0,           // MEDIA_TYPE_IMAGE(1) / MEDIA_TYPE_VIDEO(3)
    val width: Int = 0,               // 宽（像素），0 表示未知
    val height: Int = 0,              // 高（像素），0 表示未知
    val thumbnailPath: String? = null // 视频缩略图的 L2 磁盘缓存路径（仅视频有效）
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val aspectRatio: Float get() =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
}

data class MediaFolder(
    val id: Long,                     // MediaStore PARENT 列的值
    val folderName: String,           // BUCKET_DISPLAY_NAME
    val folderPath: String,           // 文件夹绝对路径
    val coverImageUri: Uri?,          // 封面 URI
    val mediaCount: Int,
    val hasImages: Boolean,
    val hasVideos: Boolean
)
```

### 4.2 Repository 接口

```kotlin
interface MediaRepository {
    fun getAllFolders(
        sortMode: SortMode = SortMode.DATE,
        sortOrder: SortOrder = SortOrder.DESC,
        includeHidden: Boolean = false
    ): Flow<List<MediaFolder>>

    fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode = SortMode.DATE,
        sortOrder: SortOrder = SortOrder.DESC,
        mediaType: Int? = null       // null=全部, IMAGE=1, VIDEO=3
    ): Flow<List<MediaItem>>

    fun searchMedia(query: String): Flow<List<MediaItem>>
}

enum class SortMode { NAME, DATE, SIZE }
enum class SortOrder { ASC, DESC }
```

### 4.3 Hybrid Media Engine（混合媒体引擎）

这是系统最核心的创新——**双阶段扫描策略**，兼顾 MediaStore 的速度和 FileTreeWalk 的完整性。

#### 阶段 1：MediaStore 快速查询

```kotlin
private fun queryMediaStoreItems(
    parentId: Long, sortMode: SortMode, sortOrder: SortOrder, mediaType: Int? = null
): Pair<List<MediaItem>, String>
```

- 通过 `ContentResolver.query(MediaStore.Files.getContentUri("external"))` 查询
- API 29+ 过滤 `IS_PENDING = 0`，排除正在写入的文件
- 使用统一的 `fileProjection`（13 列）：_ID、DISPLAY_NAME、MIME_TYPE、SIZE、DATE_MODIFIED、DATE_TAKEN、PARENT、DATA、MEDIA_TYPE、ORIENTATION、BUCKET_DISPLAY_NAME、WIDTH、HEIGHT
- 返回结果**立即发射**给 UI (`emit(filledItems)`)

#### 阶段 2：FileTreeWalk 补偿扫描

```kotlin
private suspend fun findUnindexedFiles(
    rootPath: String,
    knownFilePaths: Set<String>,    // Phase 1 已有文件路径集合，用于去重
    cacheDir: File?,
    mediaType: Int? = null
): List<MediaItem>
```

- `File.walkTopDown().maxDepth(4)` — 最多递归 4 层
- **.nomedia 检测**：`onEnter` 中检查目录是否包含 `.nomedia` 文件，若存在则跳过
- **排除目录**：跳过 `.` 开头、`Android`、`cache`、`tmp`、`temp`、`data` 目录
- **去重**：用 Phase 1 的 `knownFilePaths` 集合（基于绝对路径）去重，避免重复添加已索引文件
- **协程中断**：每次遍历文件时调用 `currentCoroutineContext().ensureActive()`，支持 ViewModel `onCleared` 时协程取消
- **权限容错**：`SecurityException` 静默跳过（某些系统文件夹无法读取）

#### 阶段合并

```kotlin
// Phase 1 发射 MediaStore 数据
emit(filledItems)

// Phase 2 发现未索引文件后，合并 + 重排序 + 重新发射
val merged = (filledItems + unindexed)
    .sortedWith(mediaComparator(sortMode, sortOrder))
emit(merged)
```

#### 索引修正（Index Reconciliation）

Phase 2 合并发射会创建全新的 `List<MediaItem>` 引用。为防止当前浏览位置丢失，ReaderViewModel 在接收 Flow 数据时执行 URI 匹配：

```kotlin
// 第一次发射（Phase 1）：用启动时的 URI 修正索引
val reconciledIndex = if (isFirstEmission) {
    val found = prevUri?.let { uri ->
        items.indexOfFirst { it.uri?.toString() == uri }.takeIf { it >= 0 }
    }
    found ?: prevState.currentIndex
} else {
    // 后续发射（Phase 2 合并）：用当前 URI 修正索引
    val curUri = _state.value.mediaItems.getOrNull(curIndex)?.uri?.toString()
    val found = curUri?.let { uri ->
        items.indexOfFirst { it.uri?.toString() == uri }.takeIf { it >= 0 }
    }
    found ?: curIndex
}
```

### 4.4 尺寸补偿

部分文件（尤其是 FileTreeWalk 发现的和某些 MediaStore 记录）在 MediaStore 中缺失宽高信息。系统通过两层机制补偿：

1. **MediaDimensionsCache**：JSON 文件，持久化 URI → (width, height) 映射
2. **BitmapFactory.decodeBounds**：使用 `inJustDecodeBounds = true` 不解码像素，只读 EXIF 尺寸头

```kotlin
private fun decodeBounds(uriStr: String): DimensionRecord? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // content:// URI → openInputStream
    // file:// URI   → decodeFile
    if (opts.outWidth > 0 && opts.outHeight > 0)
        return DimensionRecord(opts.outWidth, opts.outHeight)
    return null
}
```

### 4.5 .nomedia 检测机制

三步缓存架构：

1. **内存缓存** (`cachedHiddenParents`)：首次扫描后持有，避免重复遍历
2. **JSON 缓存** (`FolderCache.loadHiddenParents`)：跨进程生命周期持久化
3. **文件系统扫描** (`findNomediaFolders`)：深度优先遍历存储根目录，最大深度 12 层

`getHiddenFolderParentIds()` 按优先级尝试三层：内存 → JSON → 文件系统。命中任一即短路返回。

### 4.6 搜索

```kotlin
override fun searchMedia(query: String): Flow<List<MediaItem>> = flow {
    val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
    val selectionArgs = arrayOf("%$query%")
    // 直接在 ContentResolver 层做 LIKE 过滤，不加载全部数据
}
```

### 4.7 存储根扫描

```kotlin
private fun getStorageRoots(): List<File> {
    val roots = mutableListOf<File>()
    roots.add(File("/storage/emulated/0"))          // 主存储
    // 扫描 /storage 下的外部 SD 卡
    storageDir.listFiles()?.forEach { file ->
        if (file.isDirectory && file.name != "emulated" && file.name != "self")
            roots.add(file)
    }
    return roots
}
```

支持多存储设备（内置存储 + 外部 SD 卡）。

---

## 5. 导航系统

### 5.1 路由表

7 个路由，定义在 `Routes` 对象中：

| 路由 | 参数 | 说明 |
|------|------|------|
| `folder_list` | 无 | 首页，文件夹列表 |
| `media_grid/{parentId}?type={type}` | Long parentId, Int type | 文件夹内媒体网格 |
| `reader/{parentId}/{initialIndex}?type={type}` | Long parentId, Int initialIndex, Int type | 阅读器 |
| `video_player/{videoUri}?thumbnailPath={thumbnailPath}` | String videoUri, String thumbnailPath | 视频播放器 |
| `settings` | 无 | 设置 |
| `search` | 无 | 搜索 |
| `about` | 无 | 关于 |

### 5.2 导航图

```
folder_list ──────────────────────────────────────────┐
    │                                                   │
    ├──→ media_grid/{parentId} ──→ reader/{parentId}/{initialIndex}
    │         │                         │
    │         └──→ video_player/{uri} ←─┘
    │
    ├──→ search ──→ reader/{parentId}/0
    │         │
    │         └──→ video_player/{uri}
    │
    └──→ settings ──→ about
```

### 5.3 关键导航模式

**视频 URI 编码**：视频 URI 包含特殊字符，通过 `Uri.encode()` 编码后传入路由，解码回 `Uri.parse()`。

**popUpTo 清理**：从文件夹列表进入媒体网格时，使用 `popUpTo(Routes.FOLDER_LIST)` 清理返回栈，防止旧 Reader 的 ViewModel 残留。

**safePopBackStack**：返回前检查 `previousBackStackEntry != null`，防止空栈导致的双击返回崩溃。

```kotlin
private fun NavHostController.safePopBackStack() {
    if (previousBackStackEntry != null) {
        popBackStack()
    }
}
```

---

## 6. ViewModel 层

### 6.1 ReaderViewModel

#### 状态定义

```kotlin
data class ReaderState(
    val mediaItems: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val currentMode: ReaderMode = ReaderMode.ContinuousScroll,
    val isLoading: Boolean = true,
    val error: String? = null,
    val folderName: String = "",
    val sortMode: SortMode = SortMode.DATE,
    val sortOrder: SortOrder = SortOrder.DESC
)

enum class ReaderMode { ContinuousScroll, Pager }
```

#### 双协程初始化

```
init {
    ① 读取排序偏好 → loadMedia()
    ② 监听外部排序变更（settingsFlow.drop(1)）→ 重载
}
```

两路独立的 `viewModelScope.launch`，互不阻塞。

#### 代际 ID 票据系统 (Generation ID Ticket System)

防止旧协程在新请求到达后继续修改状态：

```kotlin
private var currentGeneration = 0
@Volatile private var isFrozen = false

private fun loadMedia() {
    mediaLoadJob?.cancel()          // 取消前一个加载任务
    isFrozen = false
    val myGeneration = ++currentGeneration  // 递增代际

    mediaLoadJob = viewModelScope.launch {
        // Phase 1: 收集 Flow
        repository.getMediaByFolder(...).collect { ... }

        // Phase 2: 缩略图批量更新
        withContext(Dispatchers.IO) {
            // 在分块循环中检查代际
            if (myGeneration != currentGeneration || isFrozen) return@withContext
        }
    }
}
```

**`stopAllWork()`**：离开界面时调用，取消加载任务并冻结状态，防止协程在界面销毁后仍在后台运行。

#### 排序响应

排序变更（来自 Slider Action Bar 或外部 Settings 页面）→ 保存到 DataStore → 触发 `loadMedia()` 重新查询。排序偏好独立于阅读模式，跨模式保持。

### 6.2 FolderListViewModel

#### Cache-First 策略

```kotlin
init {
    // 同步读取缓存作为 StateFlow 初始值
    val cached = FolderCache.loadFolders(application.cacheDir)
    if (cached != null && cached.isNotEmpty()) {
        skipNextLoading = true
        _state = MutableStateFlow(FolderUiState.Success(cached))
    } else {
        _state = MutableStateFlow(FolderUiState.Loading)
    }
}
```

**核心设计**：用缓存的同步读取结果作为 `StateFlow` 初始值，避免启动时出现 Loading 闪烁。`skipNextLoading` 标志防止第一次 `loadFolders()` 将已展示的缓存成功态覆盖为 Loading。

#### 派生 StateFlow

```kotlin
val imageFolders: StateFlow<List<MediaFolder>> = _state.map { s ->
    if (s is FolderUiState.Success) s.folders.filter { it.hasImages } else emptyList()
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val videoFolders: StateFlow<List<MediaFolder>> = _state.map { s ->
    if (s is FolderUiState.Success) s.folders.filter { it.hasVideos } else emptyList()
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

`imageFolders` / `videoFolders` 从单一 `_state` 派生，无需额外查询。`WhileSubscribed(5000)` 在订阅者离开 5 秒后停止上游收集，节省资源。

### 6.3 其他 ViewModel

| ViewModel | 职责 |
|-----------|------|
| `SearchViewModel` | 500ms 去抖动搜索，`MediaRepository.searchMedia()` |
| `SettingsViewModel` | 主题/排序/网格列数持久化读写，`DataStore.edit()` |

---

## 7. UI 层 — 阅读器

### 7.1 两种阅读模式

| 模式 | 组件 | 适用场景 |
|------|------|----------|
| ContinuousScrollReader | `LazyColumn` | 条漫、长图、图片流式浏览 |
| PagerReader | `HorizontalPager` | 单页图片、左右翻页 |

两种模式共享同一 `ReaderViewModel`，通过 `ReaderState.currentMode` 切换。

### 7.2 缩放系统

两种模式使用相同的缩放机制，差异仅在缩放上限：

| 模式 | 缩放范围 |
|------|----------|
| ContinuousScroll | 1x – 3x |
| Pager | 1x – 5x |

**手势处理**：

```kotlin
.pointerInput(Unit) {
    detectTapGestures(
        onTap = { showToolbar = !showToolbar },
        onDoubleTap = {
            if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f }
            else { scale = 2f }
        }
    )
}
.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val zoomChange = event.zoomChange()    // 双指距离变化比率
            val panChange = event.panChange()       // 双指位移
            if (zoomChange != 1f || (isZoomed && panChange != Offset.Zero)) {
                scale = (scale * zoomChange).coerceIn(1f, maxScale)
                if (scale > 1f) {
                    offsetX += panChange.x; offsetY += panChange.y
                } else {
                    offsetX = 0f; offsetY = 0f
                }
                event.changes.forEach { if (it.pressed) it.consume() }
            }
        }
    }
}
```

**容器级缩放**：`graphicsLayer { scaleX/scaleY; translationX/translationY }` 应用在 `LazyColumn` / `HorizontalPager` 整体，而非单张图片。`userScrollEnabled = !isZoomed` — 缩放时禁用原生滑动，避免手势冲突。

**自定义 PointerEvent 扩展**：

```kotlin
private fun PointerEvent.zoomChange(): Float {
    val changes = changes
    if (changes.size < 2) return 1f
    val (p0, p1) = changes[0] to changes[1]
    val prevDist = sqrt(
        (p0.previousPosition.x - p1.previousPosition.x).pow2() +
        (p0.previousPosition.y - p1.previousPosition.y).pow2()
    )
    val currDist = sqrt(
        (p0.position.x - p1.position.x).pow2() +
        (p0.position.y - p1.position.y).pow2()
    )
    return if (prevDist < 1f) 1f else currDist / prevDist
}
```

不依赖系统 `transformable` 修饰符，手动计算两点距离比，完全控制手势消费逻辑。

### 7.3 ContinuousScrollReader

#### 7.3.1 冷启动定位

```kotlin
val coldCenteringOffset = remember(safeInitial, mediaItems.getOrNull(safeInitial)) {
    calculateCenteringOffset(mediaItems, safeInitial, screenHeightPx, screenWidthPx, screenHeightPx)
}
val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = safeInitial,
    initialFirstVisibleItemScrollOffset = coldCenteringOffset
)
```

使用 `initialFirstVisibleItemScrollOffset` 在首帧就实现垂直居中，零帧目标位置。

#### 7.3.2 居中计算 (Centering Offset)

```kotlin
private fun calculateCenteringOffset(
    mediaItems: List<MediaItem>,
    targetIndex: Int,
    viewportPx: Float,           // 视口高度
    screenWidthPx: Float,
    contentPaddingPx: Float      // 内容内边距（等于 screenHeightPx）
): Int {
    val ratio = mediaItems.getOrNull(targetIndex)?.aspectRatio
        ?.takeIf { it > 0f } ?: DEFAULT_ASPECT_RATIO // 16:9
    val itemHeightPx = screenWidthPx / ratio   // FillWidth 下的实际渲染高度
    val gap = (viewportPx - itemHeightPx) / 2f
    val targetTop = if (gap > 0) gap else 0f   // 短线居中，长图顶对齐
    return (contentPaddingPx - targetTop).toInt()
}
```

- **短图片**（itemHeight < viewport）：gap > 0，计算负偏移使图片视觉居中
- **长图片**（itemHeight ≥ viewport）：gap ≤ 0，偏移为 padding，图片顶对齐

#### 7.3.3 AspectRatio 占位

```kotlin
@Composable
private fun ImageWithAspectPlaceholder(item: MediaItem, screenWidthPx: Float) {
    val ratio = if (item.aspectRatio > 0f) item.aspectRatio else DEFAULT_ASPECT_RATIO
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(ratio)) {
        Surface(color = surfaceVariant) { /* placeholder */ }
        AsyncImage(model = model, contentScale = ContentScale.FillWidth, ...)
    }
}
```

**关键作用**：在图片加载前通过 `Modifier.aspectRatio(ratio)` 预留正确高度，保证 `scrollToItem` 瞬间到达正确位置。无此占位，图片加载前后高度突变会导致滚动位置偏移。

#### 7.3.4 Content Padding

```kotlin
contentPadding = PaddingValues(vertical = screenHeightDp)
```

LazyColumn 的上下各有 1 屏高度的内边距，确保第一张和最后一张短图也能滚到视口中央垂直居中。

#### 7.3.5 视口中心检测

```kotlin
LaunchedEffect(listState) {
    snapshotFlow {
        val layoutInfo = listState.layoutInfo
        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
        layoutInfo.visibleItemsInfo
            .minByOrNull { abs((it.offset + it.size / 2) - viewportCenter) }
            ?.index ?: listState.firstVisibleItemIndex
    }
        .distinctUntilChanged()
        .collect { raw ->
            visibleIndex = raw.coerceIn(0, (currentTotalCount - 1).coerceAtLeast(0))
            if (!isDragged && !isScrolling && !isUserInteracting) {
                currentOnIndexChange(raw)   // 写入 ViewModel
            }
        }
}
```

**不是**简单地使用 `firstVisibleItemIndex`，而是找到可视区域中中心点最接近视口中心的 item —— 这才是用户"正在看"的那张。

#### 7.3.6 三重状态锁 (Triple State Lock)

| 锁 | 来源 | 含义 |
|----|------|------|
| `isDragged` | `sliderInteractionSource.collectIsDraggedAsState()` | Slider 正在被拖拽 |
| `isScrolling` | `listState.isScrollInProgress` | 滚动动画执行中 |
| `isUserInteracting` | 手动 `mutableStateOf` | 用户通过 Slider 触发跳转 |

三个条件中**任一**为 true 时，Slider 值不被 `visibleIndex` 覆写，反之亦然。彻底切断双向绑定冲突——当用户拖动 Slider 时不会因为 LazyColumn 内容变化导致的中心检测更新而把 Slider 弹回原位。

#### 7.3.7 混合跳转 (Hybrid Jump)

```kotlin
if (abs(target - visibleIndex) > LONG_JUMP_THRESHOLD) {        // 10
    val midTarget = if (target > visibleIndex)
        (target - LONG_JUMP_OFFSET).coerceAtLeast(0)           // 3
    else
        (target + LONG_JUMP_OFFSET).coerceAtMost(count - 1)
    listState.scrollToItem(midTarget)     // 瞬移到中间锚点
}
listState.animateScrollToItem(target, scrollOffset = offset)    // 补间到目标
```

- **短跳**（≤10 项）：直接 `animateScrollToItem`，动画平滑
- **长跳**（>10 项）：`scrollToItem(midTarget ± 3)` 瞬移到目标附近 → `animateScrollToItem(target)` 补间到达

**原理**：`animateScrollToItem` 的长距离动画会经过中间所有 item，触发大量 Composition 和图片加载，造成卡顿。瞬移跳过中间 item 后仅剩短距离补间，用户体验更好。

#### 7.3.8 dataSetKey

```kotlin
val dataSetKey = mediaItems.firstOrNull()?.parentId ?: -1L
var sliderValue by remember(dataSetKey) { mutableFloatStateOf(safeInitial.toFloat()) }
var visibleIndex by remember(dataSetKey) { mutableStateOf(safeInitial) }
```

以 `parentId` 作为 `remember` 的 key，而非 `mediaItems` 引用。由于 Phase 1→Phase 2 会创建全新的 List 引用，如果用 `mediaItems` 作为 key，每次 Phase 2 合并发射时 sliderValue 和 visibleIndex 都会重置，导致滑块抽搐。

#### 7.3.9 热启动跳转守卫

```kotlin
LaunchedEffect(initialIndex) {   // key 是 initialIndex，不是 mediaItems
    if (isDragged) return@LaunchedEffect
    val count = snapshotFlow { listState.layoutInfo.totalItemsCount }
        .first { it > 0 }
    val target = initialIndex.coerceIn(0, count - 1)
    if (visibleIndex == target) return@LaunchedEffect   // 已经在目标位置
    // ...执行跳转
}
```

以 `initialIndex` 而非 `mediaItems` 为 LaunchedEffect 的 key，防止 Phase 2 缩略图分块更新（每次更新 5 项）反复触发滚动动画。`visibleIndex == target` 守卫防止非必要的重复滚动。

### 7.4 PagerReader

#### 7.4.1 核心机制

```kotlin
val pagerState = rememberPagerState(initialPage = safeInitial) { mediaItems.size }

HorizontalPager(
    state = pagerState,
    userScrollEnabled = !isZoomed
) { page ->
    // ContentScale.Fit — 全页显示，保持宽高比
    AsyncImage(model = model, contentScale = ContentScale.Fit)
}
```

`ContentScale.Fit` vs ContinuousScroll 的 `ContentScale.FillWidth` —— 翻页模式更适合 Fit（整页可见），滚动模式更适合 FillWidth（宽度撑满，上下滚动查看）。

#### 7.4.2 Slider 状态同步

```kotlin
val effectiveSliderValue by remember {
    derivedStateOf {
        if (isDragged || isUserInteracting || pagerState.isScrollInProgress) {
            rawSliderValue        // 交互中：保持滑块跟随手指
        } else {
            pagerState.currentPage.toFloat()  // 交互后：同步到实际页面
        }
    }
}
```

`derivedStateOf` 声明式推导：拖拽/跳转中锁定在 `rawSliderValue`，其余时刻从 `pagerState.currentPage` 推导。与 ContinuousScroll 的三重锁逻辑等价但实现更简洁（水平翻页天然是离散的，不需要中心检测）。

### 7.5 Slider 组件

```kotlin
fun clampSliderTarget(value: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return value.roundToInt().coerceIn(0, itemCount - 1)
}
```

- `valueRange = 0f..(totalCount - 1).toFloat().coerceAtLeast(0f)`
- `onValueChangeFinished` → `clampSliderTarget` 取整 + 边界钳制 → 触发混合跳转
- `interactionSource = sliderInteractionSource` → `collectIsDraggedAsState()` 驱动状态锁

---

## 8. UI 层 — 其他界面

### 8.1 FolderListScreen

- **TabRow 横向标签**：全部 / 图片 / 视频，通过 `imageFolders` / `videoFolders` 两个派生 StateFlow 驱动
- **LazyColumn** 文件夹列表，每项显示封面缩略图 + 文件夹名 + 媒体数量
- **排序**：顶部 AppBar 下拉菜单，支持按名称/日期/数量排序，正序/倒序切换
- **缓存优先**：同步读取 JSON 缓存作为初始 UI 状态

### 8.2 MediaGridScreen

- 复用 `ReaderViewModel` — 传入 `mediaType` 过滤图片/视频
- `LazyVerticalGrid`，列数由 DataStore 配置（默认 3 列，可选 2–5 列）
- `ContentScale.Crop` — 网格缩略图填充
- 点击图片 → 进入 Reader；点击视频 → 进入 VideoPlayer
- **FastScroller**：右侧快速滚动条

### 8.3 FastScroller

右侧 24dp 宽的触控区，包含 6dp×50dp 的圆角矩形滑块。

**动画策略**：
- 拖拽/滚动中：alpha 立即 snapTo(0.8)
- 停止 1.5s 后：alpha animateTo(0f)，300ms 渐隐

**页码气泡**：拖拽时在滑块左侧显示半透明气泡 `"当前索引 / 总数"`。

**滚动计算**：
```kotlin
fun targetIndexAt(y: Float, height: Int) =
    ((y / height).coerceIn(0f, 1f) * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
```

`itemCount ≤ 1` 时不渲染。

### 8.4 SearchScreen

- 500ms 去抖动的 keystroke 搜索
- `SearchViewModel` 调用 `MediaRepository.searchMedia()`
- 搜索结果以 `LazyVerticalGrid` 展示
- 点击搜索结果进入 Reader（`initialIndex = 0`）或 VideoPlayer

### 8.5 VideoPlayerScreen

- ExoPlayer (Media3) 全屏视频播放
- `DisposableEffect` 管理播放器生命周期：进入播放，离开释放
- 支持从 Reader 传入 `thumbnailPath` 作为初始缩略图

### 8.6 SettingsScreen

- 主题切换：浅色 / 深色 / AMOLED 纯黑
- 排序偏好：媒体列表排序、文件夹列表排序
- 网格列数：2–5 列
- 关于页面

---

## 9. 主题系统

### 9.1 ThemeState 单例

```kotlin
object ThemeState {
    enum class ThemeMode(val value: Int) {
        LIGHT(0), DARK(1), AMOLED_BLACK(2)
    }

    var themeMode by mutableStateOf(ThemeMode.DARK)
        private set

    var onModeChanged: (ThemeMode) -> Unit = {}

    fun init(mode: ThemeMode) { themeMode = mode }

    fun cycle() {
        themeMode = when (themeMode) {
            LIGHT → DARK → AMOLED_BLACK → LIGHT
        }
        onModeChanged(themeMode)
    }
}
```

- **初始化**：`ReaderApp.onCreate()` 中通过 `runBlocking` 同步读取 DataStore 主题偏好，`ThemeState.init(savedMode)`
- **持久化**：`onModeChanged` 回调写入 DataStore（CoroutineScope(SupervisorJob() + Dispatchers.IO)）
- **三主题循环**：浅色 → 深色 → AMOLED 纯黑 → 浅色
- 主题变更通过 `mutableStateOf` 触发全局 Compose 重组

### 9.2 Material 3 主题

三个主题方案均使用 Material 3 的 `darkColorScheme()` / `lightColorScheme()`。AMOLED 模式使用纯黑 (`#000000`) 作为 background 和 surface。

---

## 10. 偏好存储

### 10.1 DataStore 配置

```kotlin
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

object PreferenceKeys {
    val THEME_MODE = intPreferencesKey("theme_mode")
    val SORT_MODE = stringPreferencesKey("sort_mode")
    val SORT_ORDER = stringPreferencesKey("sort_order")
    val FOLDER_SORT_MODE = stringPreferencesKey("folder_sort_mode")
    val FOLDER_SORT_ORDER = stringPreferencesKey("folder_sort_order")
    val GRID_COLUMNS = intPreferencesKey("grid_columns")
}
```

### 10.2 偏好分离

媒体列表和文件夹列表的排序偏好独立存储，互不干扰：
- `SORT_MODE` / `SORT_ORDER` — Reader/MediaGrid
- `FOLDER_SORT_MODE` / `FOLDER_SORT_ORDER` — FolderList

### 10.3 Flow 响应模式

```kotlin
fun Context.settingsFlow(): Flow<AppSettingsData> = dataStore.data.map { prefs ->
    AppSettingsData(
        themeMode = prefs[PreferenceKeys.THEME_MODE] ?: 1,
        sortMode = prefs[PreferenceKeys.SORT_MODE] ?: "DATE",
        // ...
    )
}
```

ViewModel 通过 `settingsFlow().drop(1)` 监听外部变更（如 Settings 页面修改了排序偏好），自动同步到当前界面。

---

## 11. 图片加载与缓存策略

### 11.1 Coil 三级缓存

| 级别 | 位置 | 配置 |
|------|------|------|
| L1 (Memory) | 内存 | 25% 可用内存，LRU |
| L2 (Disk) | `cacheDir/image_cache/` | 固定 100MB |
| L3 (Network) | 不使用 | 纯本地应用 |

### 11.2 ImageRequest 配置

```kotlin
// 滚动模式
ImageRequest.Builder(context)
    .data(item.uri)
    .size(screenWidthPx.toInt())     // 限制解码尺寸为屏幕宽度
    .crossfade(150)                   // 150ms 淡入
    .memoryCachePolicy(CachePolicy.ENABLED)
    .diskCachePolicy(CachePolicy.ENABLED)
    .build()

// 翻页模式
ImageRequest.Builder(context)
    .data(item.uri)
    .size(Size(screenWidthPx.toInt(), screenHeightPx.toInt())) // 全屏尺寸
    .crossfade(150)
    .build()
```

**尺寸限制 (`size()`) 是性能关键**：Coil 会在解码时降采样到目标尺寸，避免将 4000×3000 的原图加载到内存。`ContentScale.FillWidth` 模式下只需宽度约束；`ContentScale.Fit` 模式下需要宽高约束。

### 11.3 VidoFrameDecoder

Coil `VideoFrameDecoder` 拦截视频 URI 的加载请求，解码视频首帧作为缩略图。`CustomVideoFrameDecoder` 继承自 `VideoFrameDecoder`，在 Coil 的 `ImageLoader` 构建时注册为组件。

### 11.4 解码尺寸上限

```kotlin
// ImageRequest.Builder 中使用 size() 限制读取分辨率
.size(screenWidthPx.toInt())
```

这个配置不仅限制输出 Bitmap 尺寸，还会在 Coil 内部设置 `BitmapFactory.Options.inSampleSize` 和 `inTargetDensity`，从源头避免全分辨率解码。

---

## 12. 视频缩略图引擎

### 12.1 ThumbnailManager

L2 磁盘缓存管理器，为视频文件生成和缓存缩略图 JPG。

**缓存键**：`MD5(path + lastModified + size)`，三者共同唯一标识一个文件版本。

**线程隔离**：`Dispatchers.IO.limitedParallelism(2)` — 缩略图 IO 限定 2 并发，避免和 Coil 的磁盘缓存线程竞争。

**抽帧引擎**：
- API 27+：`MediaMetadataRetriever.getScaledFrameAtTime(1s, CLOSEST_SYNC, targetW, targetH)` — Native 层缩放，避免分配全尺寸 Bitmap
- API 26：`getFrameAtTime(1s, CLOSEST_SYNC)` + 手动 `Bitmap.createScaledBitmap` 缩放到 300px 最长边

**关键帧选择**：`OPTION_CLOSEST_SYNC` 选择最近的关键帧（I 帧），O(1) 无需解码整个 GOP。

**自适应尺寸**：

```kotlin
private fun computeTargetSize(origW: Int, origH: Int, maxDimension: Int): Pair<Int, Int> {
    val longestEdge = maxOf(origW, origH)
    if (longestEdge <= maxDimension) return Pair(origW, origH)
    val ratio = maxDimension.toFloat() / longestEdge
    return Pair(
        (origW * ratio).toInt().coerceAtLeast(1),
        (origH * ratio).toInt().coerceAtLeast(1)
    )
}
```

保持原始宽高比，最长边不超过 300px。生成的 JPG 文件约 20-50KB。

### 12.2 批量更新机制

ReaderViewModel 在 Phase 1 加载完成后，异步批量更新视频的 `thumbnailPath`：

```kotlin
// Phase 2: 异步批量更新 thumbnailPath
withContext(Dispatchers.IO) {
    currentItems.withIndex().chunked(5).forEach { chunk ->
        if (myGeneration != currentGeneration || isFrozen) return@withContext
        var changed = false
        val updatedList = _state.value.mediaItems.toMutableList()
        for ((originalIndex, item) in chunk) {
            if (item.isVideo && mgr.exists(...)) {
                updatedList[originalIndex] = item.copy(thumbnailPath = thumbFile.absolutePath)
                changed = true
            }
        }
        if (changed) _state.value = _state.value.copy(mediaItems = updatedList)
    }
}
```

- **分块 (Chunked)**：每次 5 项一组，避免一次性大量 `.exists()` 调用阻塞 IO 线程
- **代际检查**：每组处理前检查代际 ID，允许新请求中断旧批处理
- **索引直接更新**：用 `withIndex()` 保留原始索引，避免脆弱的 URI/下标匹配

---

## 13. 测试策略

### 13.1 测试基础设施

```kotlin
// Fake Repository — 可注入错误场景
class FakeMediaRepository : MediaRepository {
    private val items = mutableListOf<MediaItem>()
    var foldersError: Throwable? = null
    var mediaError: Throwable? = null

    override fun getMediaByFolder(...): Flow<List<MediaItem>> = flow {
        mediaError?.let { throw it }
        emit(items.toList())
    }
}
```

- `Dispatchers.Unconfined` — 同步执行协程，无需等待调度
- `testOptions { unitTests.isReturnDefaultValues = true }` — Android SDK stub
- `Turbine` — `state.test { awaitItem() }` 模式测试 StateFlow
- ViewModel 构造 `application = null` — 跳过 DataStore

### 13.2 测试文件

| 文件 | 数量 | 覆盖范围 |
|------|------|----------|
| `ReaderViewModelTest` | 17 | initialIndex 边界、模式切换保持索引、排序 |
| `FolderListViewModelTest` | 5 | `is FolderUiState.Success` 模式匹配 |
| `SliderUtilsTest` | 9 | `clampSliderTarget` 边界：空列表、单项、边界、正常 |

---

## 14. 性能关键路径

### 14.1 文件扫描优化

| 优化 | 位置 |
|------|------|
| Phase 1 立即发射，UI 不等待 Phase 2 | `getMediaByFolder` |
| FileTreeWalk `maxDepth(4)` 限制深度 | `findUnindexedFiles` |
| 已知文件路径去重，避免重复扫描 | `knownFilePaths` Set |
| `.nomedia` 目录短路跳过 | `onEnter` |
| 协程中断检查 (`ensureActive`) | `forEach` 内部 |
| 目录名过滤 (`EXCLUDED_DIRS`) | `onEnter` |

### 14.2 UI 渲染优化

| 优化 | 位置 |
|------|------|
| `dataSetKey` (parentId) 稳定滑块状态 | ContinuousScrollReader |
| `initialIndex` 而非 `mediaItems` 作为 LaunchedEffect key | 热启动跳转 |
| `derivedStateOf` 减少不必要的重组 | PagerReader Slider |
| `contentType` 区分 video/image，高效复用 | LazyColumn `itemsIndexed` |
| `@Immutable` 注解，跳过重组 | MediaItem, ReaderState |
| `contentPadding` 保证首尾 item 居中，减少无效区域 | LazyColumn |
| `snapshotFlow.distinctUntilChanged()` 过滤重复发射 | CenterDetection |
| `aspectRatio()` 占位，防止加载时高度跳变 | ImageWithAspectPlaceholder |

### 14.3 内存优化

| 优化 | 位置 |
|------|------|
| Coil `RGB_565` 位图配置 | ReaderApp.newImageLoader |
| Coil `size()` 限制解码分辨率 | ImageRequest.Builder |
| `inJustDecodeBounds = true` 只读尺寸头 | decodeBounds |
| 磁盘缓存固定上限 100MB | ReaderApp.newImageLoader |
| 视频缩略图 `limitedParallelism(2)` | ThumbnailManager |
| `WhileSubscribed(5000)` 自动停止收集 | FolderListVM 派生 Flow |
| ViewModel `stopAllWork()` + 代际 ID | ReaderViewModel |
| `Navigation.popUpTo` 清理返回栈 | NavGraph |

### 14.4 线程模型

```
Dispatchers.Main         — Compose UI、ViewModel State 更新
Dispatchers.IO           — ContentResolver 查询、FileTreeWalk、BitmapFactory
Dispatchers.IO (LP=2)    — 视频缩略图 IO（ThumbnailManager）
Dispatchers.Unconfined   — 测试（同步执行）
```

所有 Repository Flow 通过 `.flowOn(Dispatchers.IO)` 将工作分发到 IO 线程，ViewModel 通过 `collect` 在主线程接收结果。

---

> 本文档仅用于学习和技术交流。PixScroll 为个人非商业用途软件。
