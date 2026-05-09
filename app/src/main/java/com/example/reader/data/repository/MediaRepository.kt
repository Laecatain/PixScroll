package com.example.reader.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class MediaRepository(private val contentResolver: ContentResolver) {

    private val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATA
    )

    private val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DATA
    )

    private val imageCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun getAllFolders(): Flow<List<MediaFolder>> = flow {
        val folders = mutableMapOf<String, MutableList<MediaItem>>()

        queryMedia(imageCollection, imageProjection) { uri, name, mime, size, date, bucket, data ->
            folders.getOrPut(bucket) { mutableListOf() }.add(MediaItem(uri, name, mime, size, date, data))
        }

        queryMedia(videoCollection, videoProjection) { uri, name, mime, size, date, bucket, data ->
            folders.getOrPut(bucket) { mutableListOf() }.add(MediaItem(uri, name, mime, size, date, data))
        }

        val result = folders.map { (name, items) ->
            MediaFolder(
                folderName = name,
                folderPath = items.first().folderPath.substringBeforeLast("/"),
                coverImageUri = items.firstOrNull { !it.isVideo }?.uri ?: items.first().uri,
                mediaCount = items.size
            )
        }.sortedByDescending { it.mediaCount }

        emit(result)
    }.flowOn(Dispatchers.IO)

    fun getMediaByFolder(folderPath: String): Flow<List<MediaItem>> = flow {
        val items = mutableListOf<MediaItem>()

        val imageSelection = "${MediaStore.Images.Media.DATA} LIKE ?"
        val imageArgs = arrayOf("$folderPath/%")

        queryMedia(imageCollection, imageProjection, imageSelection, imageArgs) { uri, name, mime, size, date, _, data ->
            items.add(MediaItem(uri, name, mime, size, date, data))
        }

        queryMedia(videoCollection, videoProjection, imageSelection, imageArgs) { uri, name, mime, size, date, _, data ->
            items.add(MediaItem(uri, name, mime, size, date, data))
        }

        emit(items.sortedByDescending { it.dateModified })
    }.flowOn(Dispatchers.IO)

    private fun queryMedia(
        collection: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        mapper: (Uri, String, String, Long, Long, String, String) -> Unit
    ) {
        val cursor = contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        ) ?: return

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val bucketCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val name = it.getString(nameCol) ?: ""
                val mime = it.getString(mimeCol) ?: ""
                val size = it.getLong(sizeCol)
                val date = it.getLong(dateCol)
                val bucket = it.getString(bucketCol) ?: "Unknown"
                val data = it.getString(dataCol) ?: ""

                val contentUri = Uri.withAppendedPath(collection, id.toString())
                mapper(contentUri, name, mime, size, date, bucket, data)
            }
        }
    }
}
