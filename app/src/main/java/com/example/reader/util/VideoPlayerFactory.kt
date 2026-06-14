package com.example.reader.util

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector as Media3CodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

object VideoPlayerFactory {
    private const val TAG = "VideoPlayer"

    // Operating rate limits to avoid EINVAL on some Qualcomm chips
    // while keeping enough headroom for smooth 60fps playback.
    private const val MAX_OPERATING_RATE = 120.0f
    private const val DEFAULT_OPERATING_RATE = 60.0f
    private const val FALLBACK_OPERATING_RATE = 30.0f

    // Frame rate monitoring thresholds
    private const val LOW_FPS_THRESHOLD = 30f  // Below this = severe throttling
    private const val TARGET_FPS_MULTIPLIER = 0.7f  // 70% of target = acceptable

    // Active renderer reference for thermal status updates
    private var activeRenderer: ThermalAwareVideoRenderer? = null

    /** Update thermal status on the active video renderer. Called from VideoPlayerScreen. */
    fun updateThermalStatus(status: Int) {
        activeRenderer?.setThermalStatus(status)
    }

    // SD tier (longest edge ≤ 720p)
    private const val SD_MIN_BUFFER_MS = 5_000
    private const val SD_MAX_BUFFER_MS = 20_000
    private const val SD_BUFFER_FOR_PLAYBACK_MS = 500
    private const val SD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000

    // HD tier (720p < edge ≤ 1080p)
    private const val HD_MIN_BUFFER_MS = 15_000
    private const val HD_MAX_BUFFER_MS = 40_000
    private const val HD_BUFFER_FOR_PLAYBACK_MS = 2_000
    private const val HD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    // 2K tier (1080p < edge ≤ 1440p)
    private const val QHD_MIN_BUFFER_MS = 25_000
    private const val QHD_MAX_BUFFER_MS = 60_000
    private const val QHD_BUFFER_FOR_PLAYBACK_MS = 3_000
    private const val QHD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

    // 4K+ tier (edge > 1440p)
    private const val FOUR_K_MIN_BUFFER_MS = 50_000
    private const val FOUR_K_MAX_BUFFER_MS = 120_000
    private const val FOUR_K_BUFFER_FOR_PLAYBACK_MS = 6_000
    private const val FOUR_K_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000

    // Resolution thresholds
    private const val HD_THRESHOLD = 720
    private const val FHD_THRESHOLD = 1080
    private const val QHD_THRESHOLD = 1440

    /** Backward-compatible overload using HD-tier defaults. */
    fun create(context: Context): ExoPlayer =
        create(context, videoWidth = 0, videoHeight = 0, isLowRam = false)

    fun create(
        context: Context,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        isLowRam: Boolean = false
    ): ExoPlayer {
        Log.e(TAG, ">>> VideoPlayerFactory.create(${videoWidth}x${videoHeight}, lowRam=$isLowRam)")
        val appContext = context.applicationContext

        val (minBuffer, maxBuffer, playbackBuffer, rebufferBuffer) =
            selectBufferParams(videoWidth, videoHeight, isLowRam)

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                playClearSamplesWithoutKeys: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<Renderer>
            ) {
                val renderer = ThermalAwareVideoRenderer(
                    context,
                    object : MediaCodecAdapter.Factory {
                        private val delegate = MediaCodecAdapter.Factory.DEFAULT
                        override fun createAdapter(
                            configuration: MediaCodecAdapter.Configuration
                        ): MediaCodecAdapter {
                            // Keep original operating-rate but cap to a safe range.
                            // Some Qualcomm Codec2 HALs reject configure() with EINVAL
                            // when operating-rate is too high for high-res content,
                            // but setting it to 1.0 causes severe frame drops.
                            val original = configuration.mediaFormat
                            val fmt = android.media.MediaFormat(original)
                            val originalRate = try {
                                original.getFloat(android.media.MediaFormat.KEY_OPERATING_RATE)
                            } catch (_: Exception) { 0f }
                            // Cap at 120.0 to avoid EINVAL on some chips while keeping
                            // enough headroom for 60fps content.
                            val safeRate = if (originalRate > 0f) {
                                originalRate.coerceIn(1.0f, MAX_OPERATING_RATE)
                            } else {
                                DEFAULT_OPERATING_RATE
                            }
                            fmt.setFloat(android.media.MediaFormat.KEY_OPERATING_RATE, safeRate)
                            Log.i(TAG, "CONFIGURE: codec=${configuration.codecInfo.name}")
                            Log.i(TAG, "operating-rate: original=$originalRate, safe=$safeRate")
                            try {
                                val newConfig = MediaCodecAdapter.Configuration.createForVideoDecoding(
                                    configuration.codecInfo, fmt, configuration.format,
                                    configuration.surface, configuration.crypto
                                )
                                return delegate.createAdapter(newConfig)
                            } catch (e: Exception) {
                                // If configure fails with EINVAL, try with lower operating-rate
                                Log.w(TAG, "Configure failed with rate=$safeRate, trying lower rate")
                                fmt.setFloat(android.media.MediaFormat.KEY_OPERATING_RATE, FALLBACK_OPERATING_RATE)
                                val fallbackConfig = MediaCodecAdapter.Configuration.createForVideoDecoding(
                                    configuration.codecInfo, fmt, configuration.format,
                                    configuration.surface, configuration.crypto
                                )
                                return delegate.createAdapter(fallbackConfig)
                            }
                        }
                    },
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    playClearSamplesWithoutKeys,
                    eventHandler,
                    eventListener,
                    50
                )
                activeRenderer = renderer
                out.add(renderer)
            }
        }.apply {
            setEnableDecoderFallback(true)
            // Note: forceEnableMediaCodecAsynchronousQueueing() removed
            // as it can cause EINVAL on some Qualcomm chips with high-res content.
            setMediaCodecSelector(hardwarePreferredSelector)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebufferBuffer)
            .build()

        val trackSelector = DefaultTrackSelector(appContext).apply {
            setParameters(
                buildUponParameters()
                    .setViewportSizeToPhysicalDisplaySize(appContext, true)
            )
        }

        Log.i(
            TAG,
            "resolution=${videoWidth}x${videoHeight}, " +
                "minBufferMs=$minBuffer, maxBufferMs=$maxBuffer, " +
                "bufferForPlaybackMs=$playbackBuffer, " +
                "bufferForPlaybackAfterRebufferMs=$rebufferBuffer"
        )

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
    }

    private fun selectBufferParams(
        width: Int,
        height: Int,
        isLowRam: Boolean
    ): BufferParams {
        if (isLowRam) {
            return BufferParams(
                SD_MIN_BUFFER_MS, SD_MAX_BUFFER_MS,
                SD_BUFFER_FOR_PLAYBACK_MS, SD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
        }

        val longestEdge = maxOf(width, height)
        return when {
            longestEdge == 0 -> BufferParams(
                HD_MIN_BUFFER_MS, HD_MAX_BUFFER_MS,
                HD_BUFFER_FOR_PLAYBACK_MS, HD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            longestEdge <= HD_THRESHOLD -> BufferParams(
                SD_MIN_BUFFER_MS, SD_MAX_BUFFER_MS,
                SD_BUFFER_FOR_PLAYBACK_MS, SD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            longestEdge <= FHD_THRESHOLD -> BufferParams(
                HD_MIN_BUFFER_MS, HD_MAX_BUFFER_MS,
                HD_BUFFER_FOR_PLAYBACK_MS, HD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            longestEdge <= QHD_THRESHOLD -> BufferParams(
                QHD_MIN_BUFFER_MS, QHD_MAX_BUFFER_MS,
                QHD_BUFFER_FOR_PLAYBACK_MS, QHD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            else -> BufferParams(
                FOUR_K_MIN_BUFFER_MS, FOUR_K_MAX_BUFFER_MS,
                FOUR_K_BUFFER_FOR_PLAYBACK_MS, FOUR_K_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
        }
    }

    /** Prefers hardware-accelerated decoders over software decoders. */
    private val hardwarePreferredSelector = object : Media3CodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            Log.e(TAG, "decoder_list[$mimeType]: ${all.joinToString { "${it.name}(hw=${it.hardwareAccelerated})" }}")
            return all.sortedByDescending { it.hardwareAccelerated }
        }
    }

    private data class BufferParams(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int
    )

    /**
     * Custom MediaCodecVideoRenderer that skips frames during thermal throttling.
     *
     * When device reaches THERMAL_STATUS_SEVERE or above, skips rendering
     * most output buffers to reduce GPU/display load while keeping audio
     * in sync. The skip interval escalates with thermal severity:
     * - SEVERE:   render 1 of every 3 frames (~10fps for 30fps content)
     * - CRITICAL: render 1 of every 4 frames (~7.5fps)
     * - EMERGENCY/SHUTDOWN: render 1 of every 6 frames (~5fps)
     */
    private class ThermalAwareVideoRenderer(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector,
        allowedJoiningTimeMs: Long,
        playClearSamplesWithoutKeys: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        maxDroppedVideoFrameCountToNotify: Int
    ) : MediaCodecVideoRenderer(
        context,
        codecAdapterFactory,
        mediaCodecSelector,
        allowedJoiningTimeMs,
        playClearSamplesWithoutKeys,
        eventHandler,
        eventListener,
        maxDroppedVideoFrameCountToNotify
    ) {
        @Volatile
        private var thermalStatus: Int = android.os.PowerManager.THERMAL_STATUS_NONE

        private var frameCounter = 0
        private var skipInterval = 1  // 1 = render every frame (no skip)

        fun setThermalStatus(status: Int) {
            thermalStatus = status
            skipInterval = when (status) {
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> 3
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> 4
                android.os.PowerManager.THERMAL_STATUS_EMERGENCY,
                android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> 6
                else -> 1
            }
            frameCounter = 0
            if (skipInterval > 1) {
                Log.w(TAG, "thermal_frame_skip: status=$status, skipInterval=$skipInterval")
            }
        }

        override fun onPositionReset(positionUs: Long, joining: Boolean) {
            super.onPositionReset(positionUs, joining)
            frameCounter = 0
        }

        override fun processOutputBuffer(
            positionUs: Long,
            elapsedRealtimeUs: Long,
            codec: MediaCodecAdapter?,
            buffer: java.nio.ByteBuffer?,
            bufferIndex: Int,
            bufferFlags: Int,
            sampleCount: Int,
            bufferPresentationTimeUs: Long,
            isDecodeOnlyBuffer: Boolean,
            isLastBuffer: Boolean,
            format: Format
        ): Boolean {
            if (skipInterval > 1) {
                frameCounter++
                if (frameCounter % skipInterval != 0) {
                    // Skip this frame: release buffer without rendering
                    codec?.releaseOutputBuffer(bufferIndex, false)
                    return true
                }
            }
            return super.processOutputBuffer(
                positionUs, elapsedRealtimeUs, codec, buffer,
                bufferIndex, bufferFlags, sampleCount,
                bufferPresentationTimeUs, isDecodeOnlyBuffer,
                isLastBuffer, format
            )
        }
    }

    /**
     * Performance monitor that tracks actual output frame rate from MediaCodec.
     * Uses time-window based calculation for accurate fps measurement.
     */
    class DecodePerformanceMonitor {
        private var windowFrameCount = 0L
        private var windowStartTime = 0L
        private var currentFps = 0f
        private val fpsHistory = ArrayDeque<Float>(10)
        private val WINDOW_SIZE_MS = 1000L  // 1 second window

        fun onFramesRendered(count: Int) {
            val now = System.currentTimeMillis()

            if (windowStartTime == 0L) {
                windowStartTime = now
            }

            windowFrameCount += count
            val elapsed = now - windowStartTime

            // Calculate fps when window is complete
            if (elapsed >= WINDOW_SIZE_MS) {
                currentFps = windowFrameCount * 1000f / elapsed
                fpsHistory.addLast(currentFps)
                if (fpsHistory.size > 10) fpsHistory.removeFirst()

                // Reset window
                windowFrameCount = 0
                windowStartTime = now
            }
        }

        fun getAverageFps(): Float {
            if (fpsHistory.isEmpty()) return 0f
            return fpsHistory.average().toFloat()
        }

        fun isPerformanceDegraded(targetFps: Float): Boolean {
            val avgFps = getAverageFps()
            return avgFps > 0 && avgFps < targetFps * TARGET_FPS_MULTIPLIER
        }

        fun isSevereThrottling(): Boolean {
            return getAverageFps() in 0.1f..LOW_FPS_THRESHOLD
        }

        fun reset() {
            windowFrameCount = 0
            windowStartTime = 0
            currentFps = 0f
            fpsHistory.clear()
        }
    }
}

// ── 4K+ 高性能渲染 ──────────────────────────────────────────────

/**
 * Custom [DefaultRenderersFactory] that replaces the default video renderer
 * with [BoostedVideoRenderer] for 4K+ content. The boosted renderer applies
 * a 1.5x operating rate hint to the hardware decoder, requesting higher
 * clock frequency to sustain high-bitrate decode throughput.
 */
private class HighPerformanceRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        playClearSamplesWithoutKeys: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: java.util.ArrayList<Renderer>
    ) {
        out.add(
            BoostedVideoRenderer(
                context,
                MediaCodecAdapter.Factory.DEFAULT,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                playClearSamplesWithoutKeys,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            )
        )
    }

    companion object {
        private const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }
}

/**
 * [MediaCodecVideoRenderer] subclass that overrides [getCodecOperatingRateV23]
 * to apply a 1.5x boost. This hints the SoC to clock up the decoder pipeline,
 * reducing frame drops for sustained high-bitrate playback (4K 60fps 80Mbps+).
 *
 * The boost is multiplicative: if the base rate would be 60.0 (for 60fps content),
 * the effective rate becomes 90.0, telling the hardware to run 50% faster than
 * real-time requirement.
 */
private class BoostedVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    playClearSamplesWithoutKeys: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedVideoFrameCountToNotify: Int
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    playClearSamplesWithoutKeys,
    eventHandler,
    eventListener,
    maxDroppedVideoFrameCountToNotify
) {
    override fun getCodecOperatingRateV23(
        operatingRate: Float,
        inputFormat: Format,
        streamFormats: Array<out Format>
    ): Float {
        val base = super.getCodecOperatingRateV23(operatingRate, inputFormat, streamFormats)
        val result = if (base < 1.0f) (base * OPERATING_RATE_BOOST).coerceAtMost(1.0f) else base
        Log.i("VideoPlayer", "operating_rate: base=$base, result=$result, fps=${inputFormat.frameRate}")
        return result
    }

    companion object {
        private const val OPERATING_RATE_BOOST = 1.5f
    }
}
