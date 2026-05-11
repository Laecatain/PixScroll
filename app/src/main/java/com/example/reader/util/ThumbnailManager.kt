package com.example.reader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 视频缩略图 L2 磁盘缓存管理器。
 *
 * 职责：
 * 1. 将视频帧抽稀为 JPG 文件存入 cacheDir/thumbnails/
 * 2. 用 (path + lastModified + size) 的 MD5 作为唯一缓存键
 * 3. 后台抽帧使用 [MediaMetadataRetriever]，并发限制为 2
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThumbnailManager(private val context: Context) {

    private val thumbDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    companion object {
        private const val THUMB_QUALITY = 80
        private const val THUMB_MAX_DIMENSION = 300
        private val thumbDispatcher = Dispatchers.IO.limitedParallelism(2)
    }

    /** 返回缓存文件对象（可能不存在），基于 path + lastModified + size 生成键。 */
    fun getThumbFile(path: String, lastModified: Long, size: Long): File {
        val key = hashKey("$path$lastModified$size")
        return File(thumbDir, "$key.jpg")
    }

    /** 检查该视频的缩略图是否已缓存。 */
    fun exists(path: String, lastModified: Long, size: Long): Boolean {
        return getThumbFile(path, lastModified, size).exists()
    }

    /**
     * 从已有的 Bitmap 保存为缩略图缓存文件。
     * 同步执行（供 Coil onSuccess 回调使用，运行在 Coil 后台线程）。
     */
    fun save(bitmap: Bitmap, file: File): File? {
        return try {
            val scaled = scaleToMaxDimension(bitmap, THUMB_MAX_DIMENSION)
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 生成视频缩略图并写入磁盘缓存。
     * 如果已缓存则直接返回。
     */
    suspend fun generateThumbnail(videoPath: String, dateModified: Long, size: Long): File? {
        if (exists(videoPath, dateModified, size)) {
            return getThumbFile(videoPath, dateModified, size)
        }
        return withContext(thumbDispatcher) {
            generateThumbnailSync(videoPath, dateModified, size)
        }
    }

    /** 清除所有缓存缩略图。 */
    fun clearCache() {
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    // ── 内部方法 ──

    @Suppress("DEPRECATION")
    private fun generateThumbnailSync(videoPath: String, dateModified: Long, size: Long): File? {
        return try {
            val bitmap = ThumbnailUtils.createVideoThumbnail(
                videoPath, MediaStore.Video.Thumbnails.MINI_KIND
            )
            val bmp = bitmap ?: return null
            val file = getThumbFile(videoPath, dateModified, size)
            save(bmp, file)
            bmp.recycle()
            file
        } catch (_: Exception) {
            null
        }
    }

    /** 从尺寸未知的视频文件解码一帧，生成缩略图并存入缓存。 */
    suspend fun generateThumbnailFromUri(videoPath: String, uri: android.net.Uri, size: Long): File? {
        val dateModified = try {
            File(videoPath).lastModified()
        } catch (_: Exception) { System.currentTimeMillis() }

        if (exists(videoPath, dateModified, size)) {
            return getThumbFile(videoPath, dateModified, size)
        }

        return withContext(thumbDispatcher) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val bitmap = retriever.frameAtTime
                retriever.release()
                val bmp = bitmap ?: return@withContext null
                val file = getThumbFile(videoPath, dateModified, size)
                save(bmp, file)
                bmp.recycle()
                file
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun hashKey(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** 按最长边缩放，保持宽高比。 */
    private fun scaleToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longestEdge
        val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
