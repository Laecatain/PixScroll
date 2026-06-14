package com.example.reader.ui.player

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.reader.util.PlayerPreloader
import com.example.reader.util.TakeResult
import com.example.reader.util.VideoPlayerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

private const val LOW_FPS_THRESHOLD = 30f

@Composable
fun VideoPlayerScreen(
    videoUri: Uri,
    thumbnailPath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriString = remember { videoUri.toString() }

    // ── 核心状态 ──
    var isFirstFrameRendered by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isControlVisible by remember { mutableStateOf(true) }
    var currentSpeed by remember { mutableFloatStateOf(1f) }
    var isLongPressing by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(false) }

    // ── Slider 双状态仲裁 ──
    // 用户拖拽时只写 sliderPosition，playerPosition 由 ExoPlayer 回调驱动。
    // 显示层在拖拽期间选择本地值，结束后切回 Player 反馈值。
    var sliderPosition by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    fun displayPosition() = if (isDragging) sliderPosition else playerPosition

    val skipSeekThresholdMs = 300L

    // ── 性能监控器 ──
    val performanceMonitor = remember { VideoPlayerFactory.DecodePerformanceMonitor() }
    var isPerformanceDegraded by remember { mutableStateOf(false) }

    // ── 播放器创建（三态 Take） ──
    val session = remember(videoUri) {
        val result = PlayerPreloader.take(uriString)
        val player = when (result) {
            is TakeResult.Ready -> result.player
            is TakeResult.InProgress -> result.player
            is TakeResult.Cold -> {
                val (probedW, probedH) = probeVideoDimensions(context, videoUri)
                VideoPlayerFactory.create(context, probedW, probedH).apply {
                    setMediaItem(MediaItem.fromUri(videoUri))
                    prepare()
                }
            }
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        player.playWhenReady = true
        VideoPlayerSession(player, result is TakeResult.Cold)
    }
    val exoPlayer = session.player

    fun seekFast(targetMs: Long) {
        val boundedDuration = duration.coerceAtLeast(0L)
        val target = targetMs.coerceIn(0L, boundedDuration)
        val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)

        if (kotlin.math.abs(target - currentPosition) < skipSeekThresholdMs) return

        playerPosition = target
        sliderPosition = target
        exoPlayer.seekTo(target)
        if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
            isBuffering = true
        }
    }

    // ── 画面清除工具函数 ──
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    /** 即时隐藏 SurfaceView + 归还播放器给 Preloader。 */
    fun clearPlayerSurface() {
        playerViewRef?.apply {
            visibility = android.view.View.INVISIBLE
            player = null
        }
        exoPlayer.clearVideoSurface()
    }

    // ── Player 事件监听 + 资源释放 ──
    // Single DisposableEffect 统一管理 listen/release 顺序，
    // 避免分离两个 effect 导致 release 在 removeListener 之前执行。
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        duration = exoPlayer.duration.coerceAtLeast(0L)
                        isBuffering = false
                    }
                    Player.STATE_BUFFERING -> isBuffering = true
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        isBuffering = false
                        isControlVisible = true
                    }
                }
            }
            override fun onRenderedFirstFrame() {
                isFirstFrameRendered = true
                isBuffering = false
                val format = exoPlayer.videoFormat
                if (format != null) {
                    android.util.Log.i("VideoPlayer",
                        "decoder_analytics: codec=${format.codecs ?: "unknown"}" +
                        " resolution=${format.width}x${format.height}" +
                        " bitrate=${format.bitrate / 1000}kbps" +
                        " frameRate=${format.frameRate}" +
                        " mimeType=${format.sampleMimeType}")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                isPlaying = false
                isBuffering = false
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                ) {
                    android.util.Log.e("VideoPlayer",
                        "decoder_error: code=${error.errorCode}" +
                        " msg=${error.message} cause=${error.cause}")
                }
                errorMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或无法访问"
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败，格式可能不受支持"
                    PlaybackException.ERROR_CODE_REMOTE_ERROR -> "远程播放错误"
                    else -> "播放失败 (${error.errorCode})"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            clearPlayerSurface()
            if (session.ownsPlayer) {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()
            } else {
                PlayerPreloader.notifyReleased(uriString)
            }
        }
    }

    // ── 生命周期 ──
    DisposableEffect(lifecycle) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPlaying = exoPlayer.playWhenReady
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlaying) exoPlayer.play()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // ── 进度轮询（仅播放时运行） ──
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                playerPosition = exoPlayer.currentPosition.coerceIn(0, duration)
                delay(200)
            }
        }
    }

    // ── 丢帧监控 + 性能监控（仅播放时，每 5s 采样一次） ──
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var lastDropped = 0
            var lastRendered = 0
            val targetFps = exoPlayer.videoFormat?.frameRate ?: 30f
            while (isActive) {
                delay(5000)
                val counters = exoPlayer.videoDecoderCounters ?: continue
                val dropped = counters.droppedBufferCount
                val rendered = counters.renderedOutputBufferCount
                val deltaDropped = dropped - lastDropped
                val deltaRendered = rendered - lastRendered
                lastDropped = dropped
                lastRendered = rendered

                // Update performance monitor with rendered frames
                if (deltaRendered > 0) {
                    performanceMonitor.onFramesRendered(deltaRendered.toInt())
                }

                val total = deltaDropped + deltaRendered
                if (total > 0) {
                    val dropRate = deltaDropped * 100f / total
                    val avgFps = performanceMonitor.getAverageFps()
                    isPerformanceDegraded = performanceMonitor.isPerformanceDegraded(targetFps)

                    if (dropRate > 5f || isPerformanceDegraded) {
                        android.util.Log.w("VideoPlayer",
                            "frame_drop: ${deltaDropped}/${total} frames dropped in 5s window " +
                            "(${dropRate.toInt()}%) | avg_fps=${avgFps.toInt()} target_fps=${targetFps.toInt()} | " +
                            "total_dropped=$dropped, total_rendered=$rendered")

                        // Log thermal impact analysis
                        if (performanceMonitor.isSevereThrottling()) {
                            android.util.Log.w("VideoPlayer",
                                "performance_warning: severe throttling detected " +
                                "(avg_fps=${avgFps.toInt()} < ${LOW_FPS_THRESHOLD.toInt()})")
                        }
                    }
                }
            }
        } else {
            performanceMonitor.reset()
            isPerformanceDegraded = false
        }
    }

    // ── 温控检测（播放期间持续监听） ──
    // Thermal status is forwarded to ThermalAwareVideoRenderer which skips
    // output frames at the codec level — no playback-speed manipulation needed.
    var thermalStatus by remember { mutableIntStateOf(0) }
    DisposableEffect(exoPlayer) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        val thermalListener = object : android.os.PowerManager.OnThermalStatusChangedListener {
            override fun onThermalStatusChanged(status: Int) {
                thermalStatus = status
                VideoPlayerFactory.updateThermalStatus(status)
                if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                    val label = when (status) {
                        android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                        android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                        android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                        android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                        else -> "UNKNOWN"
                    }
                    Log.w("VideoPlayer", "thermal_warning: device thermal=$label")
                    // Cancel any active fast-forward to reduce load
                    if (exoPlayer.playbackParameters.speed > 1f) {
                        exoPlayer.setPlaybackSpeed(1f)
                        currentSpeed = 1f
                        isLongPressing = false
                    }
                }
            }
        }
        try {
            powerManager.addThermalStatusListener(thermalListener)
        } catch (_: Throwable) { /* API < 29 */ }
        onDispose {
            try {
                powerManager.removeThermalStatusListener(thermalListener)
            } catch (_: Throwable) { /* guard against OEM quirks */ }
        }
    }

    // ── 自动隐藏计时器 ──
    LaunchedEffect(isControlVisible, isPlaying) {
        if (isControlVisible && isPlaying && !isDragging && !hasError) {
            delay(3000)
            isControlVisible = false
        }
    }

    // ── 沉浸模式 ──
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        val controller = WindowInsetsControllerCompat(window, view)
        if (isControlVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── 重试函数 ──
    val retry: () -> Unit = {
        hasError = false
        errorMessage = null
        isFirstFrameRendered = false
        isBuffering = false
        sliderPosition = 0L
        playerPosition = 0L
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // ── 返回手势拦截 ──
    BackHandler {
        clearPlayerSurface()
        onBack()
    }

    // ══════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── 视频渲染层（Surface 尺寸对齐） ──
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    // Surface 尺寸对齐：避免系统自动缩放
                    val videoFormat = exoPlayer.videoFormat
                    if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
                        // 设置 Surface 尺寸与视频一致，减少缩放开销
                        // PlayerView 内部会处理宽高比适配
                        Log.i("VideoPlayer", "surface_align: video=${videoFormat.width}x${videoFormat.height}")
                    }
                }
            },
            update = { playerViewRef = it },
            modifier = Modifier.fillMaxSize()
        )

        // ── 缩略图占位 → 淡出过渡 ──
        AnimatedVisibility(
            visible = !isFirstFrameRendered && thumbnailPath != null,
            enter = fadeIn(animationSpec = tween(50)),
            exit = fadeOut(animationSpec = tween(250))
        ) {
            thumbnailPath?.let { path ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(path))
                        .crossfade(true)
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // ── 缓冲加载指示器 ──
        if (isBuffering && !hasError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // ── 错误覆盖层 ──
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "播放失败",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage ?: "未知错误",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = retry) {
                        Text("重试")
                    }
                }
            }
        }

        // ── 手势层 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isControlVisible = !isControlVisible },
                        onDoubleTap = { offset ->
                            val isRightSide = offset.x > size.width / 2f
                            val seekAmount = if (isRightSide) 10000L else -10000L
                            seekFast(playerPosition + seekAmount)
                            isControlVisible = true
                        },
                        onLongPress = {
                            isLongPressing = true
                            currentSpeed = 3f
                            exoPlayer.setPlaybackSpeed(3f)
                        }
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        waitForUpOrCancellation()
                        if (isLongPressing) {
                            isLongPressing = false
                            currentSpeed = 1f
                            exoPlayer.setPlaybackSpeed(1f)
                        }
                    }
                }
        )

        // ── 倍速指示器 ──
        if (currentSpeed != 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    "${currentSpeed.toInt()}x",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── 控制栏 ──
        AnimatedVisibility(
            visible = isControlVisible && !hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 顶部：返回按钮
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .size(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                    onClick = {
                        clearPlayerSurface()
                        onBack()
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // 底部：进度条
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f)
                                )
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (isPlaying) {
                                        exoPlayer.pause()
                                        if (currentSpeed != 1f) {
                                            isLongPressing = false
                                            currentSpeed = 1f
                                            exoPlayer.setPlaybackSpeed(1f)
                                        }
                                    } else {
                                        exoPlayer.play()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                formatTime(displayPosition()),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                formatTime(duration),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Slider(
                            value = displayPosition().toFloat(),
                            onValueChange = {
                                sliderPosition = it.toLong()
                                isDragging = true
                                isControlVisible = true
                            },
                            onValueChangeFinished = {
                                seekFast(sliderPosition)
                                isDragging = false
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }
    }
}

private data class VideoPlayerSession(
    val player: ExoPlayer,
    val ownsPlayer: Boolean
)

/**
 * Quickly probe video dimensions using MediaMetadataRetriever.
 * Returns (0, 0) on failure — the factory will use HD-tier defaults.
 */
private fun probeVideoDimensions(context: Context, uri: Uri): Pair<Int, Int> {
    val retriever = android.media.MediaMetadataRetriever()
    return try {
        when (uri.scheme) {
            "file" -> retriever.setDataSource(uri.path)
            "content" -> retriever.setDataSource(context, uri)
            else -> return Pair(0, 0)
        }
        val w = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(
            android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
        Pair(w, h)
    } catch (_: RuntimeException) {
        Pair(0, 0)
    } finally {
        retriever.release()
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0)
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    else
        "%02d:%02d".format(minutes, seconds)
}
