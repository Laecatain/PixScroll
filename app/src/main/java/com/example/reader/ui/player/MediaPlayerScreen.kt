package com.example.reader.ui.player

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
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
import com.example.reader.util.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

private const val TAG = "MediaPlayerScreen"

/**
 * MediaPlayer-based video player for high-res content that ExoPlayer struggles with.
 * Uses TextureView (not SurfaceView) to avoid compositor window-layer issues with Compose.
 * TextureView renders within the normal View hierarchy and survives pause/resume without
 * losing its SurfaceTexture, eliminating the black-screen-on-resume problem.
 */
@Composable
fun MediaPlayerScreen(
    videoUri: Uri,
    thumbnailPath: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // -- Core state --
    var isFirstFrameRendered by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isControlVisible by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBuffering by remember { mutableStateOf(true) }
    var surfaceReady by remember { mutableStateOf(false) }
    var surfaceTextureRef by remember { mutableStateOf<SurfaceTexture?>(null) }
    var surfaceRef by remember { mutableStateOf<Surface?>(null) }

    // -- Aspect ratio state --
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    fun applyAspectRatioMatrix() {
        val vw = videoWidth; val vh = videoHeight
        val tw = viewWidth; val th = viewHeight
        if (vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return
        val textureView = textureViewRef ?: return
        // 等比缩放：取较小的 scale，视频不超出 view
        val scale = minOf(tw.toFloat() / vw, th.toFloat() / vh)
        val scaledW = vw * scale
        val scaledH = vh * scale
        // 居中偏移
        val tx = (tw - scaledW) / 2f
        val ty = (th - scaledH) / 2f
        val matrix = android.graphics.Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        textureView.setTransform(matrix)
    }

    // -- Generation counter for stale-callback protection --
    var playerGeneration by remember { mutableIntStateOf(0) }

    // -- Slider dual-state --
    // -- Swipe to seek --
    var isSwipeSeeking by remember { mutableStateOf(false) }
    var swipeBaseline by remember { mutableLongStateOf(0L) }
    var swipeSeekTarget by remember { mutableLongStateOf(0L) }
    var swipeSeekDirection by remember { mutableStateOf("") }

    var sliderPosition by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    fun displayPosition() = if (isDragging) sliderPosition else if (isSwipeSeeking) swipeSeekTarget else playerPosition
    val skipSeekThresholdMs = 300L

    // -- MediaPlayer --
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun seekFast(targetMs: Long) {
        val mp = mediaPlayer ?: return
        val boundedDuration = duration.coerceAtLeast(0L)
        val target = targetMs.coerceIn(0L, boundedDuration)
        val currentPosition = mp.currentPosition.toLong().coerceAtLeast(0L)
        if (kotlin.math.abs(target - currentPosition) < skipSeekThresholdMs) return
        playerPosition = target
        sliderPosition = target
        mp.seekTo(target.toInt().coerceIn(0, Int.MAX_VALUE))
    }

    fun releasePlayer() {
        mediaPlayer?.apply {
            Log.i(TAG, "releasePlayer")
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {}
            setSurface(null)
            release()
        }
        mediaPlayer = null
        surfaceRef?.release()
        surfaceRef = null
    }

    fun clearSurfaceRefs() {
        surfaceRef = null
        surfaceTextureRef = null
    }

    fun createAndAttachPlayer(surfaceTexture: SurfaceTexture) {
        releasePlayer()
        val gen = ++playerGeneration
        Log.i(TAG, "createAndAttachPlayer: uri=$videoUri gen=$gen")
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            mp.setDataSource(context, videoUri)
            val surface = Surface(surfaceTexture)
            mp.setSurface(surface)
            surfaceRef = surface
            Log.i(TAG, "setDataSource+setSurface done, preparing async gen=$gen")
            mp.setOnPreparedListener { player ->
                if (playerGeneration != gen) {
                    Log.w(TAG, "onPrepared: stale gen=$gen (current=$playerGeneration), ignoring")
                    player.release()
                    return@setOnPreparedListener
                }
                // Apply scaling mode when player is ready and surface is bound
                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                Log.i(TAG, "onPrepared: duration=${player.duration}ms gen=$gen")
                duration = player.duration.toLong().coerceAtLeast(0L)
                isBuffering = false
                isFirstFrameRendered = true
                mediaPlayer = player
                player.start()
                isPlaying = true
            }
            mp.setOnCompletionListener {
                if (playerGeneration != gen) return@setOnCompletionListener
                Log.i(TAG, "onCompletion gen=$gen")
                isPlaying = false
                isControlVisible = true
            }
            mp.setOnErrorListener { _, what, extra ->
                if (playerGeneration != gen) return@setOnErrorListener true
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra gen=$gen")
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
                if (playerGeneration != gen) return@setOnInfoListener false
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> isBuffering = true
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> isBuffering = false
                }
                false
            }
            mp.setOnVideoSizeChangedListener { _, width, height ->
                if (playerGeneration != gen) return@setOnVideoSizeChangedListener
                Log.i(TAG, "onVideoSizeChanged: ${width}x${height} gen=$gen")
                videoWidth = width
                videoHeight = height
                applyAspectRatioMatrix()
            }
            mp.prepareAsync()
            mediaPlayer = mp
            Log.i(TAG, "prepareAsync called, waiting for onPrepared gen=$gen")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaPlayer", e)
            hasError = true
            errorMessage = "无法创建播放器: ${e.message}"
        }
    }

    // -- TextureView surface lifecycle --
    val textureListener = remember {
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
                surfaceTextureRef = st
                surfaceReady = true
                viewWidth = width
                viewHeight = height
                createAndAttachPlayer(st)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "onSurfaceTextureSizeChanged: ${width}x${height}")
                viewWidth = width
                viewHeight = height
                applyAspectRatioMatrix()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                Log.i(TAG, "onSurfaceTextureDestroyed")
                surfaceReady = false
                releasePlayer()
                clearSurfaceRefs()
                return true // let the system release the SurfaceTexture
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                // Frames are arriving - if we see this log but no video, it's a compositing issue
            }
        }
    }

    // -- Lifecycle --
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
                    // TextureView retains SurfaceTexture through pause/resume,
                    // so the player is still alive — just restart playback.
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
            clearSurfaceRefs()
        }
    }

    // -- Progress polling --
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

    // -- Auto-hide controls --
    LaunchedEffect(isControlVisible, isPlaying) {
        if (isControlVisible && isPlaying && !isDragging && !hasError) {
            delay(3000)
            isControlVisible = false
        }
    }

    // -- Immersive mode --
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, view)
        if (isControlVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // -- Back handler --
    BackHandler {
        releasePlayer()
        onBack()
    }

    // ══════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // -- Video rendering layer --
        // TextureView renders in the normal View hierarchy (no independent compositor window).
        // setVideoScalingMode(SCALE_TO_FIT) handles aspect ratio / letterbox at the system level.
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = textureListener
                    keepScreenOn = true
                    textureViewRef = this
                    // Force hardware layer so TextureView composites correctly
                    // within Compose's rendering pipeline
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // -- Thumbnail placeholder --
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

        // -- Buffering indicator --
        if (isBuffering && !hasError) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // -- Error overlay --
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
                        // TextureView's SurfaceTexture is still alive — reuse it
                        surfaceTextureRef?.let { createAndAttachPlayer(it) }
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

        // -- Gesture layer --
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
                .pointerInput(duration) {
                    if (duration <= 0L) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dragAccum = 0f
                        var pastThreshold = false
                        swipeBaseline = playerPosition
                        drag(down.id) { change ->
                            change.consume()
                            dragAccum += change.position.x - change.previousPosition.x
                            if (!pastThreshold && kotlin.math.abs(dragAccum) > viewConfiguration.touchSlop) {
                                pastThreshold = true
                            }
                            if (pastThreshold) {
                                isSwipeSeeking = true
                                isControlVisible = true
                                val pixelsPerMs = size.width.toFloat() / (duration.toFloat() / 4f)
                                val deltaMs = (dragAccum / pixelsPerMs).toLong()
                                swipeSeekTarget = (swipeBaseline + deltaMs).coerceIn(0L, duration)
                                swipeSeekDirection = if (swipeSeekTarget >= playerPosition) "▶▶" else "◀◀"
                            }
                        }
                        if (isSwipeSeeking) {
                            isSwipeSeeking = false
                            seekFast(swipeSeekTarget)
                        }
                    }
                }
        )

        // -- Swipe seek indicator --
        AnimatedVisibility(
            visible = isSwipeSeeking,
            enter = fadeIn(animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${formatTime(swipeSeekTarget)} / ${formatTime(duration)}",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = swipeSeekDirection,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }
        }

        // -- Controls --
        AnimatedVisibility(
            visible = isControlVisible && !hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Back button
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

                // Bottom progress bar
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
                            value = if (duration > 0) (displayPosition().toDouble() / duration.toDouble()).toFloat() else 0f,
                            onValueChange = { fraction ->
                                sliderPosition = (fraction.toDouble() * duration.toDouble()).toLong()
                                isDragging = true
                                isControlVisible = true
                            },
                            onValueChangeFinished = {
                                seekFast(sliderPosition)
                                isDragging = false
                            },
                            valueRange = 0f..1f,
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
