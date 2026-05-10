package com.example.reader.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.util.DimensionRecord
import com.example.reader.util.FolderCache
import com.example.reader.util.MediaDimensionsCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
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
        sortOrder: SortOrder = SortOrder.DESC
    ): Flow<List<MediaItem>>

    fun searchMedia(query: String): Flow<List<MediaItem>>
}

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif"
)
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "3gp")
private val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS

class AndroidMediaRepository(
    private val contentResolver: ContentResolver,
    private val cacheDir: File? = null
) : MediaRepository {

    private val unifiedUri = MediaStore.Files.getContentUri("external")
    private var cachedHiddenParents: Set<Long>? = null

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

    // ═══════════════════════════════════════════════════════════════
    //  文件夹列表
    // ═══════════════════════════════════════════════════════════════

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

        cursor?.use {
            val idCol = it.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val parentCol = it.getColumnIndex(MediaStore.Files.FileColumns.PARENT)
            val bucketCol = it.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val dataCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
            val mimeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

            while (it.moveToNext()) {
                val parent = if (parentCol >= 0) it.getLong(parentCol) else continue
                val data = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                val fileId = if (idCol >= 0) it.getLong(idCol) else continue
                val mime = if (mimeCol >= 0) it.getString(mimeCol) ?: "" else ""

                val acc = folderMap.getOrPut(parent) {
                    val bucket = if (bucketCol >= 0) it.getString(bucketCol) ?: "Unknown" else "Unknown"
                    val folderPath = if (data.isNotEmpty()) data.substringBeforeLast("/") else ""
                    FolderAccumulator(
                        parent = parent,
                        folderName = bucket,
                        folderPath = folderPath,
                        coverId = fileId,
                        coverIsVideo = mime.startsWith("video/"),
                        mediaCount = 0,
                        maxDate = 0L
                    )
                }

                acc.mediaCount++
                val dateTaken = if (dateCol >= 0) it.getLong(dateCol) else 0L
                if (dateTaken > acc.maxDate) acc.maxDate = dateTaken

                if (acc.coverIsVideo && !mime.startsWith("video/")) {
                    acc.coverId = fileId
                    acc.coverIsVideo = false
                }
            }
        }

        if (folderMap.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val folders = folderMap.values.map { acc ->
            MediaFolder(
                id = acc.parent,
                folderName = acc.folderName,
                folderPath = acc.folderPath,
                coverImageUri = ContentUris.withAppendedId(unifiedUri, acc.coverId),
                mediaCount = acc.mediaCount
            )
        }.let { list ->
            when (sortMode) {
                SortMode.NAME -> list.sortedBy { it.folderName.lowercase() }
                SortMode.DATE -> list.sortedByDescending { folderMap[it.id]?.maxDate ?: 0L }
                SortMode.SIZE -> list.sortedByDescending { it.mediaCount }
            }
        }.let { list ->
            if (sortOrder == SortOrder.ASC) list.reversed() else list
        }

        cacheDir?.let { FolderCache.saveFolders(it, folders) }
        emit(folders)
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    //  媒体列表（Hybrid: MediaStore → FileTreeWalk）
    // ═══════════════════════════════════════════════════════════════

    override fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder
    ): Flow<List<MediaItem>> = flow {
        val (mediaStoreItems, folderPath) = queryMediaStoreItems(parentId, sortMode, sortOrder)
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

        // Phase 1: 快速发射 MediaStore 数据
        emit(filledItems)
        if (filledItems.isEmpty()) return@flow

        // Phase 2: FileTreeWalk 补偿未索引文件
        val rootPath = folderPath.ifEmpty { return@flow }
        val unindexed = findUnindexedFiles(rootPath, knownFilePaths, cacheDir)
        if (unindexed.isEmpty()) return@flow

        val merged = (filledItems + unindexed).sortedWith(mediaComparator(sortMode, sortOrder))
        emit(merged)
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    //  搜索
    // ═══════════════════════════════════════════════════════════════

    override fun searchMedia(query: String): Flow<List<MediaItem>> = flow {
        if (query.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" +
                " AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0" +
                " AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" +
                " AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "%$query%"
        )

        val cursor = contentResolver.query(
            unifiedUri, fileProjection, selection, selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
        )

        emit(readMediaItemsFromCursor(cursor))
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════

    private fun queryMediaStoreItems(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder
    ): Pair<List<MediaItem>, String> {
        val baseSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Files.FileColumns.PARENT} = ?" +
                " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)" +
                " AND ${MediaStore.Files.FileColumns.IS_PENDING} = 0"
        } else {
            "${MediaStore.Files.FileColumns.PARENT} = ?" +
                " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        }

        val selectionArgs = arrayOf(
            parentId.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

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

    /** BitmapFactory.inJustDecodeBounds 解码图片尺寸，不加载像素数据。 */
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

    /** FileTreeWalk 扫描未被 MediaStore 索引的文件 */
    private suspend fun findUnindexedFiles(
        rootPath: String,
        knownFilePaths: Set<String>,
        cacheDir: File?
    ): List<MediaItem> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()

        val cache = if (cacheDir != null) MediaDimensionsCache.load(cacheDir) else null
        val recordsToSave = if (cacheDir != null) mutableMapOf<String, DimensionRecord>() else null
        val items = mutableListOf<MediaItem>()

        try {
            root.walkTopDown()
                .maxDepth(4)
                .onEnter { file ->
                    if (file.isDirectory) {
                        if (file.name.startsWith(".") || file.name in EXCLUDED_DIRS) return@onEnter false
                        if (File(file, ".nomedia").exists()) return@onEnter false
                    }
                    true
                }
                .filter { file ->
                    file.isFile && file.extension.lowercase() in ALL_MEDIA_EXTENSIONS
                }
                .forEach { file ->
                    currentCoroutineContext().ensureActive() // 支持 ViewModel 销毁时中断扫描
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
        } catch (_: SecurityException) {
            // 无权限读取时静默跳过
        }

        if (recordsToSave != null && recordsToSave.isNotEmpty() && cache != null) {
            cache.putAll(recordsToSave)
            MediaDimensionsCache.save(cacheDir!!, cache)
        }

        return items
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

    // ═══════════════════════════════════════════════════════════════
    //  .nomedia 检测
    // ═══════════════════════════════════════════════════════════════

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
        var coverIsVideo: Boolean,
        var mediaCount: Int,
        var maxDate: Long
    )

    companion object {
        private val EXCLUDED_DIRS = setOf("Android", "cache", "tmp", "temp", "data")
    }
}
