package com.example.reader.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class MediaFolder(
    val id: Long,
    val folderName: String,
    val folderPath: String,
    val coverImageUri: Uri?,
    val mediaCount: Int,
    val hasImages: Boolean = true,
    val hasVideos: Boolean = true,
    val coverMimeType: String = "",
    val coverPath: String = "",
    val coverDateModified: Long = 0L,
    val coverSize: Long = 0L,
    val coverThumbnailPath: String? = null
) {
    val coverIsVideo: Boolean get() = coverMimeType.startsWith("video/")
}
