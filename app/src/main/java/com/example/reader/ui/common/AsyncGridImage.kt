package com.example.reader.ui.common

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.example.reader.data.model.MediaItem
import com.example.reader.util.ThumbnailManager
import com.example.reader.util.VideoCoverStrategy
import java.io.File

private val PLACEHOLDER_DRAWABLE = ColorDrawable(0xFF2C2C2E.toInt())

/**
 * 网格/列表缩略图专用 AsyncImage 封装。
 * .size(300) 避免解码全分辨率。
 *
 * 用于 [MediaFolder.coverImageUri] 等简单 Uri 场景。
 */
@Composable
fun AsyncGridImage(
    uri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(300)
            .crossfade(100)
            .placeholder(PLACEHOLDER_DRAWABLE)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

/**
 * 网格/列表缩略图（支持视频缓存）。
 * - 优先使用 [MediaItem.thumbnailPath]（L2 磁盘缓存直读，Precision.EXACT 避免二次缩放）
 * - 视频无 thumbnailPath 时先查磁盘缓存文件，命中则直读 JPEG（极快，无需打开视频）
 * - 磁盘未命中时回退到 Coil VideoFrameDecoder 实时抽帧，成功后自动回写 L2 缓存
 * - 所有请求统一应用 crossfade(100) + 深色占位符消除 UI pop-in
 */
@Composable
fun AsyncGridImage(
    item: MediaItem,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    thumbnailManager: ThumbnailManager? = null,
    videoCoverStrategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S
) {
    val context = LocalContext.current
    val model = remember(item, thumbnailManager, videoCoverStrategy) {
        val thumbnailPath = item.thumbnailPath
        if (thumbnailPath != null) {
            // L2 缓存命中：直读缓存文件，EXACT 精度避免 Coil 二次缩放
            ImageRequest.Builder(context)
                .data(File(thumbnailPath))
                .size(300)
                .precision(Precision.EXACT)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(100)
                .placeholder(PLACEHOLDER_DRAWABLE)
                .build()
        } else if (item.isVideo && thumbnailManager != null) {
            val cachedFile = thumbnailManager.getThumbFile(
                item.folderPath, item.dateModified, item.size
            )
            if (cachedFile.exists()) {
                // 磁盘缓存命中：直读 JPEG，无需打开视频文件
                ImageRequest.Builder(context)
                    .data(cachedFile)
                    .size(300)
                    .precision(Precision.EXACT)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .crossfade(100)
                    .placeholder(PLACEHOLDER_DRAWABLE)
                    .build()
            } else {
                // 磁盘未命中：Coil VideoFrameDecoder 实时抽帧 → 回写 L2
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .size(Size(300, 300))
                    .crossfade(100)
                    .placeholder(PLACEHOLDER_DRAWABLE)
                    .listener(onSuccess = { _, result ->
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable) {
                            val file = thumbnailManager.getThumbFile(
                                item.folderPath, item.dateModified, item.size
                            )
                            if (!file.exists()) {
                                val bitmap = drawable.bitmap
                                if (maxOf(bitmap.width, bitmap.height) <= 300) {
                                    thumbnailManager.saveBitmap(bitmap, file)
                                } else {
                                    thumbnailManager.save(bitmap, file)
                                }
                            }
                        }
                    })
                    .build()
            }
        } else {
            // 普通图片或无需 ThumbnailManager 的场景
            ImageRequest.Builder(context)
                .data(item.uri)
                .size(300)
                .crossfade(100)
                .placeholder(PLACEHOLDER_DRAWABLE)
                .build()
        }
    }

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}
