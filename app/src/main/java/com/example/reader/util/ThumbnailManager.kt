package com.example.reader.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
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
 * 3. 全部 IO 均在 thumbDispatcher（limitedParallelism=2）上执行，
 *    避免与 Coil 读取 L2 缓存的线程竞争
 *
 * 抽帧引擎：
 * - API 27+: MediaMetadataRetriever.getScaledFrameAtTime — native 层缩放
 * - API 26:  getFrameAtTime + 手动 scaleToMaxDimension（回退）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThumbnailManager internal constructor(private val thumbDir: File) {

    constructor(context: Context) : this(
        File(context.cacheDir, "thumbnails").apply { mkdirs() }
    )

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
     * 所有 IO（包括 exists 检查）均在 thumbDispatcher 上执行。
     */
    suspend fun generateThumbnail(videoPath: String, dateModified: Long, size: Long): File? {
        return withContext(thumbDispatcher) {
            if (exists(videoPath, dateModified, size)) {
                return@withContext getThumbFile(videoPath, dateModified, size)
            }
            generateThumbnailSync(videoPath, dateModified, size)
        }
    }

    /** 清除所有缓存缩略图。 */
    fun clearCache() {
        thumbDir.listFiles()?.forEach { it.delete() }
    }

    // ── 内部方法 ──

    /**
     * 用 MediaMetadataRetriever 抽取视频帧并保存为缩略图。
     *
     * API 27+ 走 getScaledFrameAtTime（native 层缩放，避免分配全尺寸 Bitmap）；
     * API 26  走 getFrameAtTime + 手动缩放。
     */
    private fun generateThumbnailSync(videoPath: String, dateModified: Long, size: Long): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val bitmap = extractFrameScaled(retriever) ?: return null
            val file = getThumbFile(videoPath, dateModified, size)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            bitmap.recycle()
            file
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 在 API 27+ 上用 getScaledFrameAtTime 以 native 层缩放抽取帧，
     * 避免分配全分辨率 Bitmap。自动维持原始宽高比。
     *
     * API 26 回退到 getFrameAtTime（全分辨率）+ 手动缩放。
     */
    private fun extractFrameScaled(retriever: MediaMetadataRetriever): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // 查询原始尺寸以计算等比例目标尺寸
            val origWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val origHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val (targetW, targetH) = computeTargetSize(origWidth, origHeight, THUMB_MAX_DIMENSION)
            retriever.getScaledFrameAtTime(
                1_000_000L,                                          // 1s，避开黑屏片头
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,           // 最近关键帧，O(1)
                targetW, targetH
            )
        } else {
            val full = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            full?.let { scaleToMaxDimension(it, THUMB_MAX_DIMENSION) }
        }
    }

    /** 计算保持宽高比的缩放目标尺寸，最长边不超过 maxDimension。 */
    private fun computeTargetSize(origW: Int, origH: Int, maxDimension: Int): Pair<Int, Int> {
        if (origW <= 0 || origH <= 0) return Pair(maxDimension, maxDimension)
        val longestEdge = maxOf(origW, origH)
        if (longestEdge <= maxDimension) return Pair(origW, origH)
        val ratio = maxDimension.toFloat() / longestEdge
        return Pair(
            (origW * ratio).toInt().coerceAtLeast(1),
            (origH * ratio).toInt().coerceAtLeast(1)
        )
    }

    private fun hashKey(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** 按最长边缩放，保持宽高比。确保 L2 缓存文件 ~20-50KB。 */
    private fun scaleToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longestEdge
        val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
