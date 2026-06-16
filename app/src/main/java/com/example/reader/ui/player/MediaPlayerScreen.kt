package com.example.reader.ui.player

import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

private const val TAG = "MediaPlayerScreen"

/**
 * 基于 MediaPlayer 的视频播放屏幕。
 * 用于 ExoPlayer 无法播放的高分辨率视频（硬件解码器 configure 失败时）。
 * MediaPlayer 使用系统底层解码链路，不依赖 ExoPlayer 的 Surface 管理。
 */
@Composable
fun MediaPlayerScreen(
    videoUri: Uri,
    thumbnailPath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // ── 核心状态 ──
    var isFirstFrameRendered by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isControlVisible by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(true) }
    var surfaceReady by remember { mutableStateOf(false) }

    // ── Slider 双状态仲裁 ──
    var sliderPosition by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    fun displayPosition() = if (isDragging) sliderPosition else playerPosition
    val skipSeekThresholdMs = 300L

    // ── MediaPlayer ──
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var surfaceHolderRef by remember { mutableStateOf<SurfaceHolder?>(null) }

    fun seekFast(targetMs: Long) {
        val mp = mediaPlayer ?: return
        val boundedDuration = duration.coerceAtLeast(0L)
        val target = targetMs.coerceIn(0L, boundedDuration)
        val currentPosition = mp.currentPosition.toLong().coerceAtLeast(0L)
        if (kotlin.math.abs(target - currentPosition) < skipSeekThresholdMs) return
        playerPosition = target
        sliderPosition = target
        mp.seekTo(target.toInt().coerceAtLeast(0))
    }

    fun releasePlayer() {
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {}
            release()
        }
        mediaPlayer = null
    }

    fun createAndAttachPlayer(holder: SurfaceHolder) {
        releasePlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, videoUri)
            mp.setDisplay(holder)
            mp.setOnPreparedListener { player ->
                Log.i(TAG, "onPrepared: duration=${player.duration}ms")
                duration = player.duration.toLong().coerceAtLeast(0L)
                isBuffering = false
                isFirstFrameRendered = true
                mediaPlayer = player
                player.start()
                isPlaying = true
            }
            mp.setOnCompletionListener {
                isPlaying = false
                isControlVisible = true
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                hasError = true
                isPlaying = false
                isBuffering = false
                errorMessage = when (what) {
                    MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "媒体服务异常"
                    MediaPlayer.MEDIA_ERROR_UNKNOWN -> "播放失败"
                    else -> "播放失败 (error=$what)"
                }
                true
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    isBuffering = true
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    isBuffering = false
                }
                false
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaPlayer", e)
            hasError = true
            errorMessage = "无法创建播放器: ${e.message}"
        }
    }

    // ── Surface 生命周期 ──
    val surfaceCallback = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                surfaceHolderRef = holder
                surfaceReady = true
                if (mediaPlayer == null) {
                    createAndAttachPlayer(holder)
                } else {
                    mediaPlayer?.setDisplay(holder)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
                surfaceReady = false
                releasePlayer()
            }
        }
    }

    // ── 生命周期 ──
    DisposableEffect(lifecycle) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPlaying = isPlaying
                    mediaPlayer?.pause()
                    isPlaying = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlaying && surfaceReady) {
                        mediaPlayer?.start()
                        isPlaying = true
                    }
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            releasePlayer()
        }
    }

    // ── 进度轮询 ──
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                mediaPlayer?.let {
                    playerPosition = it.currentPosition.toLong().coerceIn(0, duration)
                }
                delay(200)
            }
        }
    }

    // ── 自动隐藏 ──
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

    // ── 返回手势拦截 ──
    BackHandler {
        onBack()
    }

    // ══════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── 视频渲染层 ──
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(surfaceCallback)
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── 缩略图占位 ──
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

        // ── 缓冲指示器 ──
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
                    Text("播放失败", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage ?: "未知错误",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        hasError = false
                        errorMessage = null
                        isFirstFrameRendered = false
                        isBuffering = true
                        sliderPosition = 0L
                        playerPosition = 0L
                        surfaceHolderRef?.let { createAndAttachPlayer(it) }
                    }) {
                        Text("重试")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW
                            ).apply {
                                setDataAndType(videoUri, "video/*")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            errorMessage = "没有可用的外部播放器"
                        }
                    }) {
                        Text("用外部播放器打开", color = Color.White)
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
                        }
                    )
                }
        )

        // ── 控制栏 ──
        AnimatedVisibility(
            visible = isControlVisible && !hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 返回按钮
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .size(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                    onClick = { onBack() }
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

                // 底部进度条
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
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
                                    mediaPlayer?.let {
                                        if (isPlaying) it.pause() else it.start()
                                        isPlaying = !isPlaying
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
