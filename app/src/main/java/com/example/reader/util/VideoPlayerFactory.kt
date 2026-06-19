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

    // Frame rate monitoring thresholds
    private const val LOW_FPS_THRESHOLD = 30f  // Below this = severe throttling
    private const val TARGET_FPS_MULTIPLIER = 0.7f  // 70% of target = acceptable

    // Active renderer reference for thermal status updates
    private var activeRenderer: ThermalAwareVideoRenderer? = null

    /** Update thermal status on the active video renderer. Called from VideoPlayerScreen. */
    fun updateThermalStatus(status: Int) {
        activeRenderer?.setThermalStatus(status)
    }

    /** Release the active renderer reference. Called when ExoPlayer is released outside onDispose. */
    fun clearActiveRenderer() {
        activeRenderer = null
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
        Log.i(TAG, "VideoPlayerFactory.create(${videoWidth}x${videoHeight}, lowRam=$isLowRam)")
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
                    MediaCodecAdapter.Factory.DEFAULT,
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
            Log.i(TAG, "decoder_list[$mimeType]: ${all.joinToString { "${it.name}(hw=${it.hardwareAccelerated})" }}")
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
