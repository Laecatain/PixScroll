package com.example.reader.data.model

import android.net.Uri

data class MediaItem(
    val uri: Uri?,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val folderPath: String,
    val orientation: Int = 0,
    val mediaType: Int = 0
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}
