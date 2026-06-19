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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class SortMode { NAME, DATE, SIZE }
enum class SortOrder { ASC, DESC }

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
        includeHidden: Boolean
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
            SortMode.DATE -> MediaStore.Files.FileColumns.DATE_MODIFIED
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
                if (dateModified > acc.maxDate) acc.maxDate = dateModified

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
                if (!f.isVideo) acc.hasImage = true
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
                SortMode.NAME -> compareByDescending<MediaFolder> { it.folderName.lowercase() }
                SortMode.DATE -> compareByDescending<MediaFolder> { folderMap[it.id]?.maxDate ?: 0L }
                SortMode.SIZE -> compareByDescending<MediaFolder> { it.mediaCount }
            }.let { if (sortOrder == SortOrder.ASC) it.reversed() else it }
        )

        cacheDir?.let { FolderCache.saveFolders(it, folders) }
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
        val (mediaStoreItems, folderPath, staleDirs) = queryMediaStoreItems(parentId, sortMode, sortOrder, mediaType)
        val knownFilePaths = mediaStoreItems.mapNotNull { it.folderPath.takeIf { p -> p.isNotEmpty() } }.toSet()

        // BitmapFactory 补齐 MediaStore 中缺失的宽高
        val cache = if (cacheDir != null) MediaDimensionsCache.load(cacheDir) else null
        val recordsToSave = mutableMapOf<String, DimensionRecord>()
        val filledItems = mediaStoreItems.map { item ->
            if (item.width > 0 && item.height > 0) {
                item
            } else {
                val uriStr = item.uri?.toString() ?: return@map item
                val record = cache?.get(uriStr) ?: decodeBounds(uriStr) ?: return@map item
                recordsToSave[uriStr] = record
                item.copy(width = record.width, height = record.height)
            }
        }

        if (recordsToSave.isNotEmpty() && cacheDir != null) {
            if (cache != null) cache.putAll(recordsToSave)
            MediaDimensionsCache.save(cacheDir, cache ?: recordsToSave)
        }

        // Phase 1: MediaStore 已索引的文件
        emit(filledItems)

        // 发现过期条目时触发 MediaScanner 重新索引（让重命名后的文件被正确收录）
        if (staleDirs.isNotEmpty()) {
            Log.w("MediaRepo", "发现 ${staleDirs.size} 个过期目录，触发 MediaScanner")
            for (dir in staleDirs) {
                MediaScannerConnection.scanFile(context, arrayOf(dir), null, null)
            }
        }
        val rootPath = folderPath.ifEmpty {
            allFoldersCache?.find { it.id == parentId }?.folderPath ?: ""
        }
        if (filledItems.isEmpty() && rootPath.isEmpty()) return@flow
        if (rootPath.isEmpty()) return@flow

        // Phase 2: FileTreeWalk 补漏未索引的文件
        val unindexed = findUnindexedFiles(rootPath, knownFilePaths, cacheDir, mediaType)
        if (unindexed.isEmpty()) return@flow

        val merged = (filledItems + unindexed).sortedWith(mediaComparator(sortMode, sortOrder))
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
        emit(readMediaItemsFromCursor(cursor).items)
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
    ): Triple<List<MediaItem>, String, List<String>> {
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
            SortMode.DATE -> MediaStore.Files.FileColumns.DATE_MODIFIED
            SortMode.SIZE -> MediaStore.Files.FileColumns.SIZE
        }
        val direction = if (sortOrder == SortOrder.DESC) "DESC" else "ASC"

        val cursor = contentResolver.query(
            unifiedUri, fileProjection, baseSelection, selectionArgs, "$sortCol $direction"
        )

        val result = readMediaItemsFromCursor(cursor)
        val folderPath = result.items.firstOrNull()?.folderPath
            ?.substringBeforeLast("/") ?: ""
        return Triple(result.items, folderPath, result.staleDirs)
    }

    /** Cursor 查询结果：有效条目 + 过期文件所在目录列表 */
    private data class CursorResult(val items: List<MediaItem>, val staleDirs: List<String>)

    private fun readMediaItemsFromCursor(cursor: android.database.Cursor?): CursorResult {
        val items = mutableListOf<MediaItem>()
        val staleDirs = mutableListOf<String>()
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

                // 文件存在性校验：跳过 MediaStore 中已过期的条目
                if (data.isNotEmpty() && !File(data).exists()) {
                    staleDirs.add(data.substringBeforeLast("/"))
                    continue
                }

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
        return CursorResult(items, staleDirs.distinct())
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
        mediaType: Int? = null
    ): List<MediaItem> {
        val cache = if (cacheDir != null) MediaDimensionsCache.load(cacheDir) else null
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
            SortMode.NAME -> compareByDescending { it.name.lowercase() }
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
        var hasVideo: Boolean = false
    )
    companion object {
        private const val TAG = "MediaRepo"
        private val EXCLUDED_DIRS = setOf("Android", "cache", "tmp", "temp", "data")
    }
}
