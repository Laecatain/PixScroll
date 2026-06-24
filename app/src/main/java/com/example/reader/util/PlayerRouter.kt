package com.example.reader.util

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache

/**
 * 智能播放器路由：根据设备解码能力决定使用 ExoPlayer 还是 MediaPlayer。
 *
 * 决策因素：
 * 1. 视频编码格式（H.264/H.265/VP9 等）
 * 2. 视频分辨率 + 帧率
 * 3. 设备硬件解码器能力（MediaCodecList）
 * 4. 历史播放性能反馈（丢帧率 > 15% → MediaPlayer）
 * 5. 用户手动覆盖（特性开关）
 *
 * 路由结果按 URI 缓存，同一视频多次打开不重复计算。
 */
object PlayerRouter {
    private const val TAG = "PlayerRouter"
    private const val DROP_RATE_THRESHOLD = 15f  // 丢帧率超过此值时降级到 MediaPlayer

    // 缓存最近 64 个视频的路由结果
    private val routeCache = LruCache<String, Boolean>(64)

    // 历史播放性能反馈（仅内存，重启清空）
    private val playbackHistory = LruCache<String, PlaybackOutcome>(32)

    /**
     * 决定视频应走哪个播放器（使用默认 AUTO 模式）。
     * @return true = ExoPlayer, false = MediaPlayer
     */
    fun shouldUseExoPlayer(context: Context, uri: Uri): Boolean {
        return shouldUseExoPlayer(context, uri, PlayerRouterMode.AUTO)
    }

    /**
     * 决定视频应走哪个播放器（支持用户覆盖）。
     * @param mode 路由模式：AUTO / EXOPLAYER / MEDIAPLAYER
     * @return true = ExoPlayer, false = MediaPlayer
     */
    fun shouldUseExoPlayer(context: Context, uri: Uri, mode: PlayerRouterMode): Boolean {
        // 用户手动覆盖：跳过智能检测
        if (mode == PlayerRouterMode.EXOPLAYER) return true
        if (mode == PlayerRouterMode.MEDIAPLAYER) return false

        // AUTO 模式：查缓存 → 智能检测
        val cacheKey = uri.toString().hashCode().toString()
        routeCache.get(cacheKey)?.let { return it }

        val result = evaluate(context, uri)
        routeCache.put(cacheKey, result)
        return result
    }

    /**
     * 记录播放结果，用于动态路由优化。
     * 由 VideoPlayerScreen（丢帧监控）和 MediaPlayerScreen（成功/失败）调用。
     *
     * @param uri 视频 URI
     * @param success 播放是否成功
     * @param dropRate 丢帧率百分比（仅 ExoPlayer 路径有值）
     */
    fun recordPlaybackOutcome(uri: Uri, success: Boolean, dropRate: Float = 0f) {
        val key = buildHistoryKey(uri)
        val existing = playbackHistory.get(key)
        val outcome = if (existing != null) {
            // 合并：加权平均丢帧率，保留最近结果
            PlaybackOutcome(
                success = success && existing.success,
                dropRate = (existing.dropRate + dropRate) / 2f,
                timestamp = System.currentTimeMillis()
            )
        } else {
            PlaybackOutcome(success, dropRate, System.currentTimeMillis())
        }
        playbackHistory.put(key, outcome)
        Log.d(TAG, "recordOutcome: ${uri.lastPathSegment} → success=$success, dropRate=${dropRate}%")
    }

    private fun evaluate(context: Context, uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        try {
            when (uri.scheme) {
                "file" -> retriever.setDataSource(uri.path)
                "content" -> retriever.setDataSource(context, uri)
                else -> return true // 默认 ExoPlayer
            }

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            ) ?: "video/avc"
            val frameRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull() ?: 30f

            // 旋转校正后的实际显示尺寸
            val effectiveWidth = if (rotation == 90 || rotation == 270) height else width
            val effectiveHeight = if (rotation == 90 || rotation == 270) width else height
            val longestEdge = maxOf(effectiveWidth, effectiveHeight)

            // 低分辨率视频直接走 ExoPlayer
            if (longestEdge <= 1080) {
                Log.d(TAG, "low_res: ${effectiveWidth}x${effectiveHeight} → ExoPlayer")
                return true
            }

            // 检查硬件解码器是否支持该格式+分辨率
            val hasHwDecoder = hasHardwareDecoder(mime, effectiveWidth, effectiveHeight, frameRate)
            if (hasHwDecoder) {
                // 查历史播放性能：有硬件解码器但历史丢帧率过高 → MediaPlayer
                val historyKey = buildHistoryKey(uri)
                val history = playbackHistory.get(historyKey)
                if (history != null && !history.success && history.dropRate > DROP_RATE_THRESHOLD) {
                    Log.i(TAG, "hw_decode_but_poor_history: dropRate=${history.dropRate}% → MediaPlayer")
                    return false
                }
                Log.i(TAG, "hw_decode_capable: ${mime} ${effectiveWidth}x${effectiveHeight}@${frameRate}fps → ExoPlayer")
                return true
            }

            Log.i(TAG, "no_hw_decoder: ${mime} ${effectiveWidth}x${effectiveHeight}@${frameRate}fps → MediaPlayer")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "probe failed, defaulting to ExoPlayer: ${e.message}")
            return true
        } finally {
            retriever.release()
        }
    }

    /**
     * 检查 MediaCodecList 中是否有支持指定格式+分辨率的硬件解码器。
     */
    private fun hasHardwareDecoder(mime: String, width: Int, height: Int, frameRate: Float): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue

            // 仅检查硬件加速解码器
            if (!isHardwareAccelerated(info)) continue

            try {
                val caps = info.getCapabilitiesForType(mime)
                val videoCaps = caps.videoCapabilities ?: continue

                // 检查分辨率范围
                val supportedWidths = videoCaps.supportedWidths
                val supportedHeights = videoCaps.supportedHeights
                if (width !in supportedWidths.lower..supportedWidths.upper) continue
                if (height !in supportedHeights.lower..supportedHeights.upper) continue

                // 检查帧率范围
                if (frameRate > 0) {
                    val minFps = videoCaps.supportedFrameRates.lower.toDouble()
                    val maxFps = videoCaps.supportedFrameRates.upper.toDouble()
                    if (frameRate.toDouble() !in minFps..maxFps) continue
                }

                Log.d(TAG, "found_hw_decoder: ${info.name} supports ${mime} ${width}x${height}@${frameRate}fps")
                return true
            } catch (_: IllegalArgumentException) {
                // 该 codec 不支持此 mime type
                continue
            }
        }
        return false
    }

    /**
     * 判断 codec 是否为硬件加速解码器。
     * 软件解码器名称通常以 "OMX.google." 或 "c2.android." 开头。
     */
    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        val name = info.name
        // 软件解码器黑名单前缀
        if (name.startsWith("OMX.google.") || name.startsWith("c2.android.")) return false
        // API 29+ 可以直接查询
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        // API < 29: 非 google/android 前缀即认为是硬件
        return true
    }

    private fun buildHistoryKey(uri: Uri): String {
        // 用 URI 的 lastPathSegment 作为 key，同一文件不同 scheme 也能匹配
        return uri.lastPathSegment ?: uri.toString().hashCode().toString()
    }

    /** 清除路由缓存（模式切换时调用） */
    fun clearCache() {
        routeCache.evictAll()
        playbackHistory.evictAll()
    }
}

/** 路由模式枚举 */
enum class PlayerRouterMode {
    /** 智能检测：MediaCodecList + 历史性能反馈 */
    AUTO,
    /** 强制 ExoPlayer */
    EXOPLAYER,
    /** 强制 MediaPlayer（调试用） */
    MEDIAPLAYER
}

/** 播放结果记录 */
data class PlaybackOutcome(
    val success: Boolean,
    val dropRate: Float,
    val timestamp: Long
)
