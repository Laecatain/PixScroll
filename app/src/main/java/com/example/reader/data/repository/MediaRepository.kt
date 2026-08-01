package com.example.reader.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Log
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.util.DimensionRecord
import com.example.reader.util.FolderCache
import com.example.reader.util.MediaDimensionsCache
import com.example.reader.util.SearchIndex
import com.example.reader.util.SearchableMediaEntry
import com.example.reader.util.FolderCoverStrategy
import com.example.reader.util.VideoCoverStrategy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class SortMode { NAME, DATE, SIZE }
enum class SortOrder { ASC, DESC }

interface MediaRepository {
    fun getAllFolders(
        sortMode: SortMode = SortMode.DATE,
        sortOrder: SortOrder = SortOrder.DESC,
        includeHidden: Boolean = false,
        folderCoverStrategy: FolderCoverStrategy = FolderCoverStrategy.LATEST,
        videoCoverStrategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S,
        coverSortMode: SortMode = SortMode.DATE,
        coverSortOrder: SortOrder = SortOrder.DESC
    ): Flow<List<MediaFolder>>

    fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode = SortMode.DATE,
        sortOrder: SortOrder = SortOrder.DESC,
        mediaType: Int? = null
    ): Flow<List<MediaItem>>

    fun searchMedia(query: String): Flow<List<MediaItem>>

    /** Emits when MediaStore content changes (new/deleted/modified files). */
    val mediaStoreChanges: kotlinx.coroutines.flow.SharedFlow<Unit>

    fun searchFolders(query: String): Flow<List<MediaFolder>>
}

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif"
)
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "3gp")
private val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS

class AndroidMediaRepository(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val cacheDir: File? = null
) : MediaRepository {

    private val unifiedUri = MediaStore.Files.getContentUri("external")
    private var cachedHiddenParents: Set<Long>? = null
    @Volatile
    private var allFoldersCache: List<MediaFolder>? = null
    val searchIndex = SearchIndex()

    private val _mediaStoreChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val mediaStoreChanges: SharedFlow<Unit> = _mediaStoreChanges

    init {
        cacheDir?.let { searchIndex.loadFromDisk(it) }
        cachedHiddenParents = cacheDir?.let { FolderCache.loadHiddenParents(it) }?.takeIf { it.isNotEmpty() }
        allFoldersCache = cacheDir?.let { FolderCache.loadFolders(it) }?.takeIf { it.isNotEmpty() }
        Log.d(TAG, "init: indexLoaded=${searchIndex.isBuilt} hiddenCached=${cachedHiddenParents != null} foldersCached=${allFoldersCache != null}")
        registerMediaObserver()
    }

    private fun registerMediaObserver() {
        contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    _mediaStoreChanges.tryEmit(Unit)
                }
            }
        )
    }

    private val fileProjection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.PARENT,
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.ORIENTATION,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT
    )

    // ─── 文件列表 ───

    override fun getAllFolders(
        sortMode: SortMode,
        sortOrder: SortOrder,
        includeHidden: Boolean,
        folderCoverStrategy: FolderCoverStrategy,
        videoCoverStrategy: VideoCoverStrategy,
        coverSortMode: SortMode,
        coverSortOrder: SortOrder
    ): Flow<List<MediaFolder>> = flow {
        val selection = StringBuilder(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        )
        val selectionArgs = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection.append(" AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0")
            selection.append(" AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0")
        }

        if (!includeHidden) {
            val hiddenParents = getHiddenFolderParentIds()
            if (hiddenParents.isNotEmpty()) {
                val placeholders = hiddenParents.joinToString(",") { "?" }
                selection.append(" AND ${MediaStore.Files.FileColumns.PARENT} NOT IN ($placeholders)")
                selectionArgs.addAll(hiddenParents.map { it.toString() })
            }
        }

        val sortColumn = when (sortMode) {
            SortMode.NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
            SortMode.DATE -> MediaStore.Files.FileColumns.DATE_TAKEN
            SortMode.SIZE -> MediaStore.Files.FileColumns.SIZE
        }
        val direction = if (sortOrder == SortOrder.DESC) "DESC" else "ASC"
        val fullSort = "$sortColumn $direction"

        val cursor = contentResolver.query(
            unifiedUri,
            fileProjection,
            selection.toString(),
            selectionArgs.toTypedArray(),
            fullSort
        )

        val folderMap = linkedMapOf<Long, FolderAccumulator>()
        val searchBuilder = SearchIndex.Builder()
        val knownFilePaths = mutableSetOf<String>()

        cursor?.use {
            val idCol = it.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameCol = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val parentCol = it.getColumnIndex(MediaStore.Files.FileColumns.PARENT)
            val bucketCol = it.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            // maxDate 用 DATE_TAKEN（拍摄时间）而非 DATE_MODIFIED，文件夹按"最近拍摄"排序更有意义
            val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
            val mimeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val mediaTypeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sizeCol = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateModifiedCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val orientCol = it.getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
            val widthCol = it.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = it.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

            while (it.moveToNext()) {
                val parent = if (parentCol >= 0) it.getLong(parentCol) else continue
                val data = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                if (data.isNotEmpty()) knownFilePaths.add(data)
                val fileId = if (idCol >= 0) it.getLong(idCol) else continue
                val rawMime = if (mimeCol >= 0) it.getString(mimeCol) ?: "" else ""
                val mediaType = if (mediaTypeCol >= 0) it.getInt(mediaTypeCol) else 0
                val mime = normalizeMimeType(rawMime, mediaType)
                val isImage = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || mime.startsWith("image/")
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || mime.startsWith("video/")
                val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                val dateModified = if (dateModifiedCol >= 0) it.getLong(dateModifiedCol) else 0L
                val name = if (nameCol >= 0) it.getString(nameCol) ?: "" else ""
                val orientation = if (orientCol >= 0) it.getInt(orientCol) else 0
                val w = if (widthCol >= 0) it.getInt(widthCol) else 0
                val h = if (heightCol >= 0) it.getInt(heightCol) else 0

                val acc = folderMap.getOrPut(parent) {
                    val bucket = if (bucketCol >= 0) it.getString(bucketCol) ?: "Unknown" else "Unknown"
                    val folderPath = if (data.isNotEmpty()) data.substringBeforeLast("/") else ""
                    FolderAccumulator(
                        parent = parent,
                        folderName = bucket,
                        folderPath = folderPath,
                        coverId = fileId,
                        coverMimeType = mime,
                        coverPath = data,
                        coverDateModified = dateModified,
                        coverSize = size,
                        mediaCount = 0,
                        maxDate = 0L
                    )
                }

                acc.mediaCount++
                if (isImage) acc.hasImage = true
                if (isVideo) acc.hasVideo = true
                val dateTaken = if (dateCol >= 0) it.getLong(dateCol) else 0L
                val effectiveDate = if (dateTaken > 0) dateTaken else dateModified
                if (effectiveDate > acc.maxDate) acc.maxDate = effectiveDate

                // Track earliest/latest/random candidates for cover strategy
                if (isImage) {
                    if (effectiveDate < acc.earliestCoverDate) {
                        acc.earliestCoverDate = effectiveDate
                        acc.earliestCoverId = fileId
                        acc.earliestCoverMime = mime
                        acc.earliestCoverPath = data
                        acc.earliestCoverSize = size
                    }
                    if (effectiveDate > acc.latestCoverDate) {
                        acc.latestCoverDate = effectiveDate
                        acc.latestCoverId = fileId
                        acc.latestCoverMime = mime
                        acc.latestCoverPath = data
                        acc.latestCoverSize = size
                    }
                    acc.randomCount++
                    if (kotlin.random.Random.nextInt(acc.randomCount) == 0) {
                        acc.randomCoverId = fileId
                        acc.randomCoverMime = mime
                        acc.randomCoverPath = data
                        acc.randomCoverDate = effectiveDate
                        acc.randomCoverSize = size
                    }
                    // Track first/last by name
                    val nameLower = name.lowercase()
                    if (acc.nameFirstCoverId < 0 || nameLower < acc.nameFirstCoverName) {
                        acc.nameFirstCoverName = nameLower
                        acc.nameFirstCoverId = fileId
                        acc.nameFirstCoverMime = mime
                        acc.nameFirstCoverPath = data
                        acc.nameFirstCoverSize = size
                        acc.nameFirstCoverDateModified = dateModified
                    }
                    if (acc.nameLastCoverId < 0 || nameLower > acc.nameLastCoverName) {
                        acc.nameLastCoverName = nameLower
                        acc.nameLastCoverId = fileId
                        acc.nameLastCoverMime = mime
                        acc.nameLastCoverPath = data
                        acc.nameLastCoverSize = size
                        acc.nameLastCoverDateModified = dateModified
                    }
                    // Track first/last by size
                    if (size < acc.sizeFirstCoverSize) {
                        acc.sizeFirstCoverSize = size
                        acc.sizeFirstCoverId = fileId
                        acc.sizeFirstCoverMime = mime
                        acc.sizeFirstCoverPath = data
                        acc.sizeFirstCoverDateModified = dateModified
                    }
                    if (size > acc.sizeLastCoverSize) {
                        acc.sizeLastCoverSize = size
                        acc.sizeLastCoverId = fileId
                        acc.sizeLastCoverMime = mime
                        acc.sizeLastCoverPath = data
                        acc.sizeLastCoverDateModified = dateModified
                    }
                }

                if (acc.coverMimeType.startsWith("video/") && isImage) {
                    acc.coverId = fileId
                    acc.coverMimeType = mime
                    acc.coverPath = data
                    acc.coverDateModified = dateModified
                    acc.coverSize = size
                    acc.coverThumbnailPath = null
                }

                // Build search index (single pass, piggybacking on full MediaStore scan)
                searchBuilder.add(
                    id = fileId,
                    name = name,
                    parentId = parent,
                    mimeType = mime,
                    size = size,
                    dateModified = dateModified,
                    folderPath = data,
                    orientation = orientation,
                    width = w,
                    height = h
                )
            }
        }

        // Build and persist the search index atomically
        searchIndex.build(searchBuilder.build())
        cacheDir?.let { searchIndex.saveToDisk(it) }

        // Phase 2: FileTreeWalk per folder —补漏未被 MediaStore 索引的文件
        for ((parentId, acc) in folderMap) {
            currentCoroutineContext().ensureActive()
            if (acc.folderPath.isEmpty()) continue
            val unindexed = findUnindexedFilesLight(acc.folderPath, knownFilePaths)
            if (unindexed.isEmpty()) continue
            acc.mediaCount += unindexed.size
            for (f in unindexed) {
                if (f.isVideo) acc.hasVideo = true
                if (!f.isVideo) {
                    acc.hasImage = true
                    // Track cover strategy candidates for unindexed images
                    val synthId = f.absPath.hashCode().toLong() or Long.MIN_VALUE
                    val fDate = if (f.dateModified > 0) f.dateModified else 0L
                    if (fDate < acc.earliestCoverDate) {
                        acc.earliestCoverDate = fDate
                        acc.earliestCoverId = synthId
                        acc.earliestCoverMime = f.mime
                        acc.earliestCoverPath = f.absPath
                        acc.earliestCoverSize = f.size
                    }
                    if (fDate > acc.latestCoverDate) {
                        acc.latestCoverDate = fDate
                        acc.latestCoverId = synthId
                        acc.latestCoverMime = f.mime
                        acc.latestCoverPath = f.absPath
                        acc.latestCoverSize = f.size
                    }
                    acc.randomCount++
                    if (kotlin.random.Random.nextInt(acc.randomCount) == 0) {
                        acc.randomCoverId = synthId
                        acc.randomCoverMime = f.mime
                        acc.randomCoverPath = f.absPath
                        acc.randomCoverDate = fDate
                        acc.randomCoverSize = f.size
                    }
                }
                // Update cover: prefer images over videos
                if (acc.coverMimeType.startsWith("video/") && !f.isVideo) {
                    acc.coverId = f.absPath.hashCode().toLong() or Long.MIN_VALUE // synthetic ID
                    acc.coverMimeType = f.mime
                    acc.coverPath = f.absPath
                    acc.coverDateModified = f.dateModified
                    acc.coverSize = f.size
                    acc.coverThumbnailPath = null
                }
            }
            // Add unindexed files to search index (synthetic negative IDs from path hash)
            val syntheticEntries = unindexed.map { f ->
                SearchableMediaEntry(
                    id = f.absPath.hashCode().toLong() or Long.MIN_VALUE,
                    name = File(f.absPath).name,
                    nameLower = File(f.absPath).name.lowercase(),
                    parentId = parentId,
                    mimeTypeCode = SearchIndex.mimeCodeFromMime(f.mime),
                    size = f.size,
                    dateModified = f.dateModified,
                    folderPath = f.absPath,
                    orientation = 0,
                    width = 0,
                    height = 0
                )
            }
            searchIndex.upsert(syntheticEntries)
        }

        if (folderMap.isEmpty()) {
            emit(emptyList())
            allFoldersCache = emptyList()
            return@flow
        }

        // Apply cover strategy: override acc.coverId with the candidate
        // matching the user-chosen FolderCoverStrategy.
        for ((_, acc) in folderMap) {
            when (folderCoverStrategy) {
                FolderCoverStrategy.LATEST -> {
                    if (acc.latestCoverId >= 0) {
                        acc.coverId = acc.latestCoverId
                        acc.coverMimeType = acc.latestCoverMime
                        acc.coverPath = acc.latestCoverPath
                        acc.coverSize = acc.latestCoverSize
                        acc.coverThumbnailPath = null
                    }
                }
                FolderCoverStrategy.EARLIEST -> {
                    if (acc.earliestCoverId >= 0) {
                        acc.coverId = acc.earliestCoverId
                        acc.coverMimeType = acc.earliestCoverMime
                        acc.coverPath = acc.earliestCoverPath
                        acc.coverSize = acc.earliestCoverSize
                        acc.coverThumbnailPath = null
                    }
                }
                FolderCoverStrategy.RANDOM -> {
                    if (acc.randomCoverId >= 0) {
                        acc.coverId = acc.randomCoverId
                        acc.coverMimeType = acc.randomCoverMime
                        acc.coverPath = acc.randomCoverPath
                        acc.coverSize = acc.randomCoverSize
                        acc.coverThumbnailPath = null
                    }
                }
                FolderCoverStrategy.BY_SORT -> {
                    val candidate = selectCoverBySort(acc, coverSortMode, coverSortOrder)
                    if (candidate != null) {
                        acc.coverId = candidate.id
                        acc.coverMimeType = candidate.mime
                        acc.coverPath = candidate.path
                        acc.coverSize = candidate.size
                        acc.coverDateModified = candidate.dateModified
                        acc.coverThumbnailPath = null
                    }
                }
            }
        }

        val folders = folderMap.values.map { acc ->
            // Synthetic IDs (sign bit set) = unindexed files → use file:// URI
            val coverUri = if (acc.coverId < 0) {
                Uri.fromFile(File(acc.coverPath))
            } else {
                ContentUris.withAppendedId(unifiedUri, acc.coverId)
            }
            MediaFolder(
                id = acc.parent,
                folderName = acc.folderName,
                folderPath = acc.folderPath,
                coverImageUri = coverUri,
                mediaCount = acc.mediaCount,
                hasImages = acc.hasImage,
                hasVideos = acc.hasVideo,
                coverMimeType = acc.coverMimeType,
                coverPath = acc.coverPath,
                coverDateModified = acc.coverDateModified,
                coverSize = acc.coverSize,
                coverThumbnailPath = acc.coverThumbnailPath
            )
        }.sortedWith(
            when (sortMode) {
                SortMode.NAME -> compareBy<MediaFolder> { it.folderName.lowercase() }
                SortMode.DATE -> compareByDescending<MediaFolder> { folderMap[it.id]?.maxDate ?: 0L }
                SortMode.SIZE -> compareByDescending<MediaFolder> { it.mediaCount }
            }.let { if (sortOrder == SortOrder.ASC) it.reversed() else it }
        )

        cacheDir?.let { FolderCache.saveFolders(it, folders, folderCoverStrategy = folderCoverStrategy.name, videoCoverStrategy = videoCoverStrategy.name) }
        emit(folders)
        allFoldersCache = folders
    }.flowOn(Dispatchers.IO)

    // ─── 媒体列表（Hybrid: MediaStore → FileTreeWalk）───

    override fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder,
        mediaType: Int?
    ): Flow<List<MediaItem>> = flow {
        val (mediaStoreItems, folderPath) = queryMediaStoreItems(parentId, sortMode, sortOrder, mediaType)
        val knownFilePaths = mediaStoreItems.mapNotNull { it.folderPath.takeIf { p -> p.isNotEmpty() } }.toSet()

        // Phase 1: 立即发射全部 MediaStore 结果（含可能过期的条目），不做任何同步 IO
        emit(mediaStoreItems)

        // 优先用 allFoldersCache 的 folderPath（不受 mediaType 过滤影响，且来自上次完整扫描）
        val rootPath = allFoldersCache?.find { it.id == parentId }?.folderPath?.takeIf { it.isNotEmpty() }
            ?: folderPath
        if (rootPath.isEmpty()) return@flow

        // Phase 1.5 + Phase 2 并行执行：异步过期检查 & FileTreeWalk 补漏
        val (staleList, unindexedList) = withContext(Dispatchers.IO) {
            val staleDeferred = async {
                mediaStoreItems.filter { item ->
                    item.folderPath.isNotEmpty() && !File(item.folderPath).exists()
                }
            }
            val unindexedDeferred = async {
                findUnindexedFiles(rootPath, knownFilePaths, cacheDir, mediaType)
            }
            listOf(staleDeferred.await(), unindexedDeferred.await())
        }

        @Suppress("UNCHECKED_CAST")
        val staleItems = staleList as List<MediaItem>
        @Suppress("UNCHECKED_CAST")
        val unindexedItems = unindexedList as List<MediaItem>

        // 触发 MediaScanner 重新索引过期文件所在目录
        val staleDirs = staleItems.map { it.folderPath.substringBeforeLast("/") }.distinct()
        if (staleDirs.isNotEmpty()) {
            Log.w("MediaRepo", "发现 ${staleDirs.size} 个过期目录，触发 MediaScanner")
            for (dir in staleDirs) {
                MediaScannerConnection.scanFile(context, arrayOf(dir), null, null)
            }
        }

        // 合并：过滤过期 + 追加未索引文件
        val stalePaths = staleItems.map { it.folderPath }.toSet()
        val validItems = if (stalePaths.isEmpty()) mediaStoreItems
            else mediaStoreItems.filter { it.folderPath !in stalePaths }
        if (staleItems.isEmpty() && unindexedItems.isEmpty()) return@flow

        val merged = (validItems + unindexedItems).sortedWith(mediaComparator(sortMode, sortOrder))
        emit(merged)
    }.flowOn(Dispatchers.IO)

    // ─── 搜索 ───

    override fun searchMedia(query: String): Flow<List<MediaItem>> = flow {
        if (query.isBlank()) {
            emit(emptyList())
            return@flow
        }

        // Fast path: pure in-memory search using pre-built index
        if (searchIndex.isBuilt) {
            Log.d(TAG, "searchMedia: in-memory path (indexSize=${searchIndex.size})")
            val matchingEntries = searchIndex.search(query)
            if (matchingEntries.isEmpty()) {
                emit(emptyList())
                return@flow
            }
            val hiddenParents = cachedHiddenParents
            val visibleEntries = if (hiddenParents == null || hiddenParents.isEmpty()) {
                matchingEntries
            } else {
                matchingEntries.filter { it.parentId !in hiddenParents }
            }
            emit(searchIndex.toMediaItems(visibleEntries, unifiedUri))
            return@flow
        }

        // Fallback: index not yet built (cold start before getAllFolders completes)
        Log.d(TAG, "searchMedia: fallback SQL LIKE (index not built)")
        val selection = StringBuilder(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        )
        val selectionArgs = mutableListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection.append(" AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0")
            selection.append(" AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0")
        }

        val hiddenParents = getHiddenFolderParentIds()
        if (hiddenParents.isNotEmpty()) {
            val placeholders = hiddenParents.joinToString(",") { "?" }
            selection.append(" AND ${MediaStore.Files.FileColumns.PARENT} NOT IN ($placeholders)")
            selectionArgs.addAll(hiddenParents.map { it.toString() })
        }

        selection.append(" AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
        selectionArgs.add("%$query%")

        val cursor = contentResolver.query(
            unifiedUri, fileProjection, selection.toString(), selectionArgs.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )
        emit(readMediaItemsFromCursor(cursor))
    }.flowOn(Dispatchers.IO)

    override fun searchFolders(query: String): Flow<List<MediaFolder>> = flow {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val cache = allFoldersCache
        Log.d(TAG, "searchFolders: allFoldersCache=" + if (cache != null) "hit(" + cache!!.size + ")" else "miss--getAllFolders")
        if (cache != null) {
            emit(cache.filter { it.folderName.contains(trimmedQuery, ignoreCase = true) })
        } else {
            getAllFolders(SortMode.DATE, SortOrder.DESC, includeHidden = false).collect { folders ->
                emit(folders.filter { folder ->
                    folder.folderName.contains(trimmedQuery, ignoreCase = true)
                })
            }
        }
    }.flowOn(Dispatchers.IO)

    // ─── 内部方法 ───

    private fun queryMediaStoreItems(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder,
        mediaType: Int? = null
    ): Pair<List<MediaItem>, String> {
        val baseSelection = if (mediaType != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Files.FileColumns.PARENT} = ?" +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?" +
                    " AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0" +
                    " AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0"
            } else {
                "${MediaStore.Files.FileColumns.PARENT} = ?" +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Files.FileColumns.PARENT} = ?" +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" +
                    " AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0" +
                    " AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0"
            } else {
                "${MediaStore.Files.FileColumns.PARENT} = ?" +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            }
        }

        val selectionArgs = if (mediaType != null) {
            arrayOf(
                parentId.toString(),
                mediaType.toString()
            )
        } else {
            arrayOf(
                parentId.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
        }

        val sortCol = when (sortMode) {
            SortMode.NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
            SortMode.DATE -> MediaStore.Files.FileColumns.DATE_TAKEN
            SortMode.SIZE -> MediaStore.Files.FileColumns.SIZE
        }
        val direction = if (sortOrder == SortOrder.DESC) "DESC" else "ASC"

        val cursor = contentResolver.query(
            unifiedUri, fileProjection, baseSelection, selectionArgs, "$sortCol $direction"
        )

        val items = readMediaItemsFromCursor(cursor)
        val folderPath = items.firstOrNull()?.folderPath
            ?.substringBeforeLast("/") ?: ""
        return Pair(items, folderPath)
    }

    private fun readMediaItemsFromCursor(cursor: android.database.Cursor?): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        cursor?.use {
            val idCol = it.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameCol = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val parentCol = it.getColumnIndex(MediaStore.Files.FileColumns.PARENT)
            val orientCol = it.getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
            val mediaTypeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val widthCol = it.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = it.getColumnIndex(MediaStore.MediaColumns.HEIGHT)

            while (it.moveToNext()) {
                val id = if (idCol >= 0) it.getLong(idCol) else continue
                val name = if (nameCol >= 0) it.getString(nameCol) ?: "" else ""
                val mime = if (mimeCol >= 0) it.getString(mimeCol) ?: "" else ""
                val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                val date = if (dateCol >= 0) it.getLong(dateCol) else 0L
                val data = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                val parent = if (parentCol >= 0) it.getLong(parentCol) else 0L
                val orientation = if (orientCol >= 0) it.getInt(orientCol) else 0
                val mediaType = if (mediaTypeCol >= 0) it.getInt(mediaTypeCol) else 0
                val w = if (widthCol >= 0) it.getInt(widthCol) else 0
                val h = if (heightCol >= 0) it.getInt(heightCol) else 0

                // 不做同步 exists() 检查，由 getMediaByFolder 异步过期检查处理
                items.add(
                    MediaItem(
                        uri = ContentUris.withAppendedId(unifiedUri, id),
                        name = name,
                        mimeType = mime,
                        size = size,
                        dateModified = date,
                        folderPath = data,
                        parentId = parent,
                        orientation = orientation,
                        mediaType = mediaType,
                        width = w,
                        height = h
                    )
                )
            }
        }
        return items
    }

    /** BitmapFactory.inJustDecodeBounds 解码图片尺寸，不加载像素数据 */
    private fun decodeBounds(uriStr: String): DimensionRecord? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (uriStr.startsWith("content://")) {
                contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } else {
                BitmapFactory.decodeFile(uriStr.removePrefix("file://"), opts)
            }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                DimensionRecord(opts.outWidth, opts.outHeight)
            } else null
        } catch (_: Exception) { null }
    }

    /** FileTreeWalk — shared traversal logic for both getAllFolders and getMediaByFolder. */
    /** Lightweight info from FileTreeWalk — no Uri construction, no dimension decode. */
    private data class UnindexedFileInfo(
        val absPath: String,
        val mime: String,
        val isVideo: Boolean,
        val dateModified: Long,
        val size: Long
    )

    /** Shared directory walk: skips hidden dirs, EXCLUDED_DIRS, .nomedia, maxDepth 4. */
    private fun walkMediaFiles(rootPath: String): Sequence<File> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptySequence()
        return root.walkTopDown()
            .maxDepth(4)
            .onEnter { file ->
                if (file.isDirectory) {
                    if (file.name.startsWith(".") || file.name in EXCLUDED_DIRS) return@onEnter false
                    if (File(file, ".nomedia").exists()) return@onEnter false
                }
                true
            }
            .filter { file -> file.isFile && file.extension.lowercase() in ALL_MEDIA_EXTENSIONS }
    }

    /**
     * Root-level scan for folder-level aggregation (count + cover).
     * Only scans files directly in the folder (no subdirectories) for performance.
     * Subdirectory files are discovered when user opens the folder (getMediaByFolder Phase 2).
     * Skips files already known to MediaStore ([knownFilePaths]).
     */
    private fun findUnindexedFilesLight(
        rootPath: String,
        knownFilePaths: Set<String>
    ): List<UnindexedFileInfo> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()
        val items = mutableListOf<UnindexedFileInfo>()
        try {
            val files = root.listFiles() ?: return emptyList()
            for (file in files) {
                if (!file.isFile) continue
                if (file.absolutePath in knownFilePaths) continue
                val ext = file.extension.lowercase()
                if (ext !in ALL_MEDIA_EXTENSIONS) continue
                val isVid = ext in VIDEO_EXTENSIONS
                items.add(
                    UnindexedFileInfo(
                        absPath = file.absolutePath,
                        mime = estimateMimeType(ext),
                        isVideo = isVid,
                        dateModified = file.lastModified() / 1000,
                        size = file.length()
                    )
                )
            }
        } catch (_: SecurityException) { }
        return items
    }

    private suspend fun findUnindexedFiles(
        rootPath: String,
        knownFilePaths: Set<String>,
        cacheDir: File?,
        mediaType: Int? = null,
        preloadedCache: MutableMap<String, DimensionRecord>? = null
    ): List<MediaItem> {
        val cache = preloadedCache ?: if (cacheDir != null) MediaDimensionsCache.load(cacheDir) else null
        val recordsToSave = if (cacheDir != null) mutableMapOf<String, DimensionRecord>() else null
        val items = mutableListOf<MediaItem>()

        try {
            walkMediaFiles(rootPath).forEach { file ->
                currentCoroutineContext().ensureActive()
                val absPath = file.absolutePath
                if (absPath in knownFilePaths) return@forEach

                val isVideo2 = file.extension.lowercase() in VIDEO_EXTENSIONS
                val mime = estimateMimeType(file.extension.lowercase())
                val (w, h) = if (!isVideo2) {
                    val uriStr = Uri.fromFile(file).toString()
                    val cached = cache?.get(uriStr)
                    if (cached != null) {
                        Pair(cached.width, cached.height)
                    } else {
                        val decoded = decodeBounds(file.absolutePath)
                        if (decoded != null) {
                            recordsToSave?.put(uriStr, decoded)
                            Pair(decoded.width, decoded.height)
                        } else Pair(0, 0)
                    }
                } else Pair(0, 0)

                items.add(
                    MediaItem(
                        uri = Uri.fromFile(file),
                        name = file.name,
                        mimeType = mime,
                        size = file.length(),
                        dateModified = file.lastModified() / 1000,
                        folderPath = absPath,
                        parentId = 0,
                        orientation = 0,
                        mediaType = if (isVideo2) MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                            else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                        width = w,
                        height = h
                    )
                )
            }
        } catch (_: SecurityException) { }

        if (recordsToSave != null && recordsToSave.isNotEmpty() && cache != null) {
            cache.putAll(recordsToSave)
            MediaDimensionsCache.save(cacheDir!!, cache)
        }

        val filteredItems = if (mediaType != null) {
            items.filter { it.mediaType == mediaType }
        } else items
        return filteredItems
    }

    private fun normalizeMimeType(mime: String, mediaType: Int): String {
        if (mime.isNotEmpty()) return mime
        return when (mediaType) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image/*"
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video/*"
            else -> ""
        }
    }

    private fun estimateMimeType(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "tiff", "tif" -> "image/tiff"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private fun mediaComparator(sortMode: SortMode, sortOrder: SortOrder): Comparator<MediaItem> {
        val cmp: Comparator<MediaItem> = when (sortMode) {
            SortMode.NAME -> compareBy { it.name.lowercase() }
            SortMode.DATE -> compareByDescending { it.dateModified }
            SortMode.SIZE -> compareByDescending { it.size }
        }
        return if (sortOrder == SortOrder.ASC) cmp.reversed() else cmp
    }

    // --- .nomedia 检测 ---

    private fun getHiddenFolderParentIds(): Set<Long> {
        cachedHiddenParents?.let { return it }

        val fileCached = cacheDir?.let { FolderCache.loadHiddenParents(it) }
        if (fileCached != null && fileCached.isNotEmpty()) {
            cachedHiddenParents = fileCached
            return fileCached
        }

        val hiddenParents = mutableSetOf<Long>()
        val storageDirs = getStorageRoots()
        for (root in storageDirs) {
            findNomediaFolders(root, hiddenParents)
        }
        cachedHiddenParents = hiddenParents
        cacheDir?.let { FolderCache.saveHiddenParents(it, hiddenParents) }
        return hiddenParents
    }

    private fun findNomediaFolders(dir: File, result: MutableSet<Long>, depth: Int = 0) {
        if (depth > 12) return
        val files = dir.listFiles() ?: return
        var hasNomedia = false

        for (file in files) {
            if (file.isFile && file.name == ".nomedia") {
                hasNomedia = true
                break
            }
        }

        if (hasNomedia) {
            val parentId = resolveParentId(dir.absolutePath)
            if (parentId >= 0) {
                result.add(parentId)
                return
            }
        }

        for (file in files) {
            if (file.isDirectory && !file.name.startsWith(".") &&
                file.name != "Android" && file.name != "cache"
            ) {
                findNomediaFolders(file, result, depth + 1)
            }
        }
    }

    private fun resolveParentId(path: String): Long {
        val cursor = contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.PARENT),
            "${MediaStore.Files.FileColumns.DATA} LIKE ?",
            arrayOf("$path/%"),
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                val col = it.getColumnIndex(MediaStore.Files.FileColumns.PARENT)
                if (col >= 0) it.getLong(col) else -1L
            } else -1L
        } ?: -1L
    }

    private fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()
        roots.add(File("/storage/emulated/0"))

        val storageDir = File("/storage")
        if (storageDir.isDirectory) {
            storageDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name != "emulated" && file.name != "self") {
                    roots.add(file)
                }
            }
        }

        return roots
    }

    private class FolderAccumulator(
        val parent: Long,
        val folderName: String,
        val folderPath: String,
        var coverId: Long,
        var coverMimeType: String,
        var coverPath: String,
        var coverDateModified: Long,
        var coverSize: Long,
        var coverThumbnailPath: String? = null,
        var mediaCount: Int,
        var maxDate: Long,
        var hasImage: Boolean = false,
        var hasVideo: Boolean = false,
        // Cover strategy candidates
        var earliestCoverId: Long = -1L,
        var earliestCoverDate: Long = Long.MAX_VALUE,
        var earliestCoverMime: String = "",
        var earliestCoverPath: String = "",
        var earliestCoverSize: Long = 0L,
        var latestCoverId: Long = -1L,
        var latestCoverDate: Long = Long.MIN_VALUE,
        var latestCoverMime: String = "",
        var latestCoverPath: String = "",
        var latestCoverSize: Long = 0L,
        var randomCoverId: Long = -1L,
        var randomCoverMime: String = "",
        var randomCoverPath: String = "",
        var randomCoverDate: Long = 0L,
        var randomCoverSize: Long = 0L,
        var randomCount: Int = 0,
        // First/last by file name (for BY_SORT NAME mode)
        var nameFirstCoverId: Long = -1L,
        var nameFirstCoverMime: String = "",
        var nameFirstCoverPath: String = "",
        var nameFirstCoverSize: Long = 0L,
        var nameFirstCoverDateModified: Long = 0L,
        var nameFirstCoverName: String = "",
        var nameLastCoverId: Long = -1L,
        var nameLastCoverMime: String = "",
        var nameLastCoverPath: String = "",
        var nameLastCoverSize: Long = 0L,
        var nameLastCoverDateModified: Long = 0L,
        var nameLastCoverName: String = "",
        // First/last by file size (for BY_SORT SIZE mode)
        var sizeFirstCoverId: Long = -1L,
        var sizeFirstCoverMime: String = "",
        var sizeFirstCoverPath: String = "",
        var sizeFirstCoverSize: Long = Long.MAX_VALUE,
        var sizeFirstCoverDateModified: Long = 0L,
        var sizeLastCoverId: Long = -1L,
        var sizeLastCoverMime: String = "",
        var sizeLastCoverPath: String = "",
        var sizeLastCoverSize: Long = 0L,
        var sizeLastCoverDateModified: Long = 0L
    )
    private data class CoverCandidate(
        val id: Long, val mime: String, val path: String,
        val size: Long, val dateModified: Long
    )

    private fun selectCoverBySort(
        acc: FolderAccumulator,
        sortMode: SortMode,
        sortOrder: SortOrder
    ): CoverCandidate? {
        return when (sortMode) {
            SortMode.DATE -> {
                if (sortOrder == SortOrder.DESC) {
                    if (acc.latestCoverId >= 0) CoverCandidate(
                        acc.latestCoverId, acc.latestCoverMime,
                        acc.latestCoverPath, acc.latestCoverSize, 0L
                    ) else null
                } else {
                    if (acc.earliestCoverId >= 0) CoverCandidate(
                        acc.earliestCoverId, acc.earliestCoverMime,
                        acc.earliestCoverPath, acc.earliestCoverSize, 0L
                    ) else null
                }
            }
            SortMode.NAME -> {
                if (sortOrder == SortOrder.ASC) {
                    if (acc.nameFirstCoverId >= 0) CoverCandidate(
                        acc.nameFirstCoverId, acc.nameFirstCoverMime,
                        acc.nameFirstCoverPath, acc.nameFirstCoverSize,
                        acc.nameFirstCoverDateModified
                    ) else null
                } else {
                    if (acc.nameLastCoverId >= 0) CoverCandidate(
                        acc.nameLastCoverId, acc.nameLastCoverMime,
                        acc.nameLastCoverPath, acc.nameLastCoverSize,
                        acc.nameLastCoverDateModified
                    ) else null
                }
            }
            SortMode.SIZE -> {
                if (sortOrder == SortOrder.ASC) {
                    if (acc.sizeFirstCoverId >= 0) CoverCandidate(
                        acc.sizeFirstCoverId, acc.sizeFirstCoverMime,
                        acc.sizeFirstCoverPath, acc.sizeFirstCoverSize,
                        acc.sizeFirstCoverDateModified
                    ) else null
                } else {
                    if (acc.sizeLastCoverId >= 0) CoverCandidate(
                        acc.sizeLastCoverId, acc.sizeLastCoverMime,
                        acc.sizeLastCoverPath, acc.sizeLastCoverSize,
                        acc.sizeLastCoverDateModified
                    ) else null
                }
            }
        }
    }

    companion object {
        private const val TAG = "MediaRepo"
        private val EXCLUDED_DIRS = setOf("Android", "cache", "tmp", "temp", "data")
    }
}
