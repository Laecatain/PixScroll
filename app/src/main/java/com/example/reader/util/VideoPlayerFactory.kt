package com.example.reader.util

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector as Media3CodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

object VideoPlayerFactory {
    private const val TAG = "VideoPlayer"

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
        val appContext = context.applicationContext

        val (minBuffer, maxBuffer, playbackBuffer, rebufferBuffer) =
            selectBufferParams(videoWidth, videoHeight, isLowRam)

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setMediaCodecSelector(hardwarePreferredSelector)

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
        ) = MediaCodecUtil.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        ).sortedByDescending { it.hardwareAccelerated }
    }

    private data class BufferParams(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int
    )
}
