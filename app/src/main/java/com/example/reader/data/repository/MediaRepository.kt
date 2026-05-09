package com.example.reader.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

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

class AndroidMediaRepository(private val contentResolver: ContentResolver) : MediaRepository {

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
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
    )

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

        emit(folders)
    }.flowOn(Dispatchers.IO)

    override fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder
    ): Flow<List<MediaItem>> = flow {
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
            unifiedUri,
            fileProjection,
            baseSelection,
            selectionArgs,
            "$sortCol $direction"
        )

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
                        mediaType = mediaType
                    )
                )
            }
        }

        emit(items)
    }.flowOn(Dispatchers.IO)

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
            unifiedUri,
            fileProjection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
        )

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
                        mediaType = mediaType
                    )
                )
            }
        }

        emit(items)
    }.flowOn(Dispatchers.IO)

    private fun getHiddenFolderParentIds(): Set<Long> {
        cachedHiddenParents?.let { return it }
        val hiddenParents = mutableSetOf<Long>()
        val storageDirs = getStorageRoots()
        for (root in storageDirs) {
            findNomediaFolders(root, hiddenParents)
        }
        cachedHiddenParents = hiddenParents
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
}
