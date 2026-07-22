package com.example.reader.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import com.example.reader.util.VideoCoverStrategy

/**
 * 视频缩略图管理器：L1 内存缓存 + L2 磁盘缓存。
 *
 * ── L1 内存缓存 ──
 *   LruCache<String, Bitmap>，键为 MD5，值为 RGB_565 Bitmap。
 *   默认 4MB（~120 张 300px 缩略图），LRU 淘汰时自动 recycle()。
 *   写入路径（save / saveBitmap / generateThumbnailSync）均同时填充 L1 + L2。
 *
 * ── L2 磁盘缓存 ──
 *   cacheDir/thumbnails/{md5}.jpg，300px JPEG，quality=80。
 *   键基于 (path + lastModified + size) 的 MD5，文件内容变化自动失效。
 *
 * ── 读取优先级 ──
 *   getBitmap(): L1 内存 → L2 磁盘解码 → null
 *   Coil 加载: 直读 L2 磁盘文件（无需视频解码），Coil 自身 memory cache 兜底
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
        /** L1 内存缓存上限：4MB（~120 张 300px RGB_565 缩略图 ~32KB/张） */
        private const val MEMORY_CACHE_MAX_BYTES = 4 * 1024 * 1024
        private val thumbDispatcher = Dispatchers.IO.limitedParallelism(2)
    }

    // ══════════════════════════════════════════════════
    //  L1 内存缓存
    // ══════════════════════════════════════════════════

    /**
     * L1 内存缓存。
     * - 键 = MD5（与 L2 磁盘文件名一致，不含扩展名）
     * - 值 = RGB_565 Bitmap（显存占用减半）
     * - 淘汰回调中自动 recycle()，防止 native 内存泄漏
     */
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            oldValue.recycle()
        }
    }

    // ══════════════════════════════════════════════════
    //  公共 API
    // ══════════════════════════════════════════════════

    /**
     * 从 L1 内存 → L2 磁盘依次查找，返回缓存的 Bitmap（可能为 null）。
     * L2 命中时自动解码并升温 L1，下次访问走内存。
     */
    fun getBitmap(path: String, lastModified: Long, size: Long): Bitmap? {
        val key = hashKey("$path$lastModified$size")
        // 1. L1 内存命中
        memoryCache.get(key)?.let { return it }
        // 2. L2 磁盘命中 → 解码并升温 L1
        val file = File(thumbDir, "$key.jpg")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                val cached = bitmap.copy(Bitmap.Config.RGB_565, false)
                if (cached != null) memoryCache.put(key, cached)
            }
            return bitmap
        }
        return null
    }

    /** 返回缓存文件对象（可能不存在），基于 path + lastModified + size 生成键。 */
    fun getThumbFile(path: String, lastModified: Long, size: Long): File {
        val key = hashKey("$path$lastModified$size")
        return File(thumbDir, "$key.jpg")
    }

    /** 检查该视频的缩略图是否已缓存（L1 内存或 L2 磁盘）。 */
    fun exists(path: String, lastModified: Long, size: Long): Boolean {
        val key = hashKey("$path$lastModified$size")
        return memoryCache.get(key) != null || File(thumbDir, "$key.jpg").exists()
    }

    /**
     * 从已有的 Bitmap 保存为缩略图缓存文件。
     * 同步执行（供 Coil onSuccess 回调使用，运行在 Coil 后台线程）。
     * 自动写入 L2 磁盘 + L1 内存缓存。
     */
    fun save(bitmap: Bitmap, file: File): File? {
        return try {
            val scaled = scaleToMaxDimension(bitmap, THUMB_MAX_DIMENSION)
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            // L1 内存缓存：使用独立 RGB_565 副本，不持有调用方引用
            val cached = scaled.copy(Bitmap.Config.RGB_565, false)
            if (cached != null) memoryCache.put(file.nameWithoutExtension, cached)
            if (scaled !== bitmap) scaled.recycle()
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Save a bitmap that is already at or below the target max dimension.
     * Skips the scale check when bitmap is already small enough.
     * 自动写入 L2 磁盘 + L1 内存缓存。
     */
    fun saveBitmap(bitmap: Bitmap, file: File): File? {
        return try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            // L1 内存缓存
            val cached = bitmap.copy(Bitmap.Config.RGB_565, false)
            if (cached != null) memoryCache.put(file.nameWithoutExtension, cached)
            file
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 生成视频缩略图并写入 L2 磁盘 + L1 内存缓存。
     * 所有 IO（包括 exists 检查）均在 thumbDispatcher 上执行。
     */
    suspend fun generateThumbnail(
        videoPath: String,
        dateModified: Long,
        size: Long,
        strategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S
    ): File? {
        return withContext(thumbDispatcher) {
            val key = hashKey("$videoPath$dateModified${size}${strategy.name}")
            // L1 命中直接返回
            if (memoryCache.get(key) != null) {
                return@withContext File(thumbDir, "$key.jpg")
            }
            if (File(thumbDir, "$key.jpg").exists()) {
                return@withContext File(thumbDir, "$key.jpg")
            }
            generateThumbnailSync(videoPath, dateModified, size, strategy)
        }
    }

    /** 清除所有缓存缩略图（L1 内存 + L2 磁盘）。 */
    fun clearCache() {
        thumbDir.listFiles()?.forEach { it.delete() }
        memoryCache.evictAll()
    }

    /**
     * 响应系统内存紧张 —— 由 Application.onTrimMemory() 调用。
     *
     * @param level ComponentCallbacks2.TRIM_MEMORY_* 级别
     */
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> memoryCache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> memoryCache.trimToSize(MEMORY_CACHE_MAX_BYTES / 2)
        }
    }

    // ── 内部方法 ──

    /**
     * 用 MediaMetadataRetriever 抽取视频帧，写入 L2 磁盘 + L1 内存缓存。
     *
     * API 27+ 走 getScaledFrameAtTime（native 层缩放，避免分配全尺寸 Bitmap）；
     * API 26  走 getFrameAtTime + 手动缩放。
     */
    private fun generateThumbnailSync(
        videoPath: String,
        dateModified: Long,
        size: Long,
        strategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S
    ): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val bitmap = extractFrameScaled(retriever, strategy) ?: return null
            val file = getThumbFile(videoPath, dateModified, size)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            // L1 内存缓存
            val cached = bitmap.copy(Bitmap.Config.RGB_565, false)
            if (cached != null) memoryCache.put(file.nameWithoutExtension, cached)
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
    private fun extractFrameScaled(
        retriever: MediaMetadataRetriever,
        strategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S
    ): Bitmap? {
        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L

        val timeUs = when (strategy) {
            VideoCoverStrategy.EXACT_1S -> 1_000_000L
            VideoCoverStrategy.MID_FRAME -> {
                val safeDuration = durationMs.coerceAtLeast(1000)
                val midMs = (safeDuration * 4 / 10).coerceIn(500, safeDuration - 200)
                midMs * 1000
            }
            VideoCoverStrategy.CLOSEST_KEYFRAME -> {
                val safeDuration = durationMs.coerceAtLeast(1000)
                val targetMs = (safeDuration * 3 / 10).coerceIn(500, safeDuration - 200)
                targetMs * 1000
            }
        }

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
                timeUs,                                          // 1s，避开黑屏片头
                MediaMetadataRetriever.OPTION_CLOSEST,           // 最近关键帧，O(1)
                targetW, targetH
            )
        } else {
            val full = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            full?.let { scaleToMaxDimension(it, THUMB_MAX_DIMENSION) }
        }
    }

    private fun isMostlyBlack(bitmap: Bitmap, threshold: Float = 0.05f): Boolean {
        val w = bitmap.width.coerceAtMost(100)
        val h = bitmap.height.coerceAtMost(100)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val darkCount = pixels.count { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            r < 30 && g < 30 && b < 30
        }
        return darkCount.toFloat() / pixels.size > (1f - threshold)
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
