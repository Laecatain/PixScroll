package com.example.reader.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class MediaItem(
    val uri: Uri?,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val folderPath: String,
    val parentId: Long = 0,
    val orientation: Int = 0,
    val mediaType: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnailPath: String? = null
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** 宽高比，用于 LazyColumn 占位符预设高度。0 表示未知，UI 层使用默认占位。 */
    val aspectRatio: Float get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
}
