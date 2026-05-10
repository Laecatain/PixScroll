package com.example.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.reader.data.model.MediaItem
import com.example.reader.ui.theme.ThemeState
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * 连续滚动阅读器（条漫模式）。
 *
 * ## 三大核心策略
 *
 * ### 1. 状态隔离 —— State Decoupling
 * Slider 拖拽状态通过 [MutableInteractionSource.collectIsDraggedAsState] 感知，
 * 列表滚动状态通过 [LazyListState.isScrollInProgress] 感知。
 * **只有**当 !isDragged && !isScrollInProgress 时，才将 firstVisibleItemIndex
 * 同步给 sliderValue，切断拖拽和跳转动画期间的数据回流。
 *
 * ### 2. 混合跳转 —— Hybrid Scroll
 * 在 onValueChangeFinished 中触发跳转：
 * - 短距离 (|target - current| <= 10)：直接 animateScrollToItem(target)
 * - 长距离 (>10)：先 scrollToItem(midTarget) 瞬移到目标附近（aspectRatio
 *   占位保证瞬间完成），再 animateScrollToItem(target) 补足平滑过渡
 *
 * ### 3. 图片占位防塌陷 —— Aspect Ratio Placeholder
 * 图片容器在加载前通过 [Modifier.aspectRatio] 预设准确高度。
 * LazyColumn 无需等待图片加载即可测量每个 item，消除跳转偏移和白屏。
 * 宽高比来自 MediaStore 的 WIDTH/HEIGHT，未知时 fallback 到 16:9。
 */
@Composable
fun ContinuousScrollReader(
    mediaItems: List<MediaItem>,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onVideoClick: (MediaItem) -> Unit
) {
    // ── 缩放状态 ──
    var showToolbar by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val isZoomed = scale > 1f

    val totalCount = mediaItems.size
    val scope = rememberCoroutineScope()

    // ── 进度条状态 ──
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var visibleIndex by remember { mutableStateOf(0) }

    // ── 策略1: 状态锁 —— MutableInteractionSource 感知 Slider 拖拽 ──
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isDragged by sliderInteractionSource.collectIsDraggedAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showToolbar = !showToolbar },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val zoomChange = event.zoomChange()
                        val panChange = event.panChange()

                        if (zoomChange != 1f || (isZoomed && panChange != Offset.Zero)) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 3f)
                            scale = newScale
                            if (newScale > 1f) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            } else {
                                offsetX = 0f; offsetY = 0f
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
            }
    ) {
        val listState = rememberLazyListState()
        val configuration = LocalConfiguration.current
        val screenHeightDp = configuration.screenHeightDp.dp

        // 策略1: isScrollInProgress 在 animateScrollToItem 动画期间为 true
        val isScrolling = listState.isScrollInProgress

        val idx = listState.firstVisibleItemIndex
            .coerceIn(0, (totalCount - 1).coerceAtLeast(0))
        LaunchedEffect(idx) { visibleIndex = idx }

        // 策略1: 状态锁核心 —— 拖拽中或动画中不反写 sliderValue
        LaunchedEffect(visibleIndex, isDragged, isScrolling) {
            if (!isDragged && !isScrolling) {
                sliderValue = visibleIndex.toFloat()
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            userScrollEnabled = !isZoomed,
            // 策略4: 预组合上下各一屏，减少正常滑动时的白屏
            contentPadding = PaddingValues(vertical = screenHeightDp)
        ) {
            itemsIndexed(mediaItems, key = { _, item -> item.uri ?: item.name }) { _, item ->
                if (item.isVideo) {
                    VideoThumbnail(item = item, onClick = { onVideoClick(item) })
                } else {
                    // 策略3: AspectRatio 占位 —— 加载前即有准确高度
                    ImageWithAspectPlaceholder(item = item)
                }
            }
        }

        // ── 顶部工具栏 ──
        AnimatedVisibility(
            visible = showToolbar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.7f),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { ThemeState.cycle() }) {
                        Icon(
                            if (ThemeState.isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "切换主题",
                            tint = Color.White
                        )
                    }
                    TextButton(onClick = onSwitchMode) {
                        Text("切换翻页", color = Color.White)
                    }
                }
            }
        }

        // ── 底部进度条 ──
        AnimatedVisibility(
            visible = showToolbar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.5f),
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "${visibleIndex + 1} / $totalCount",
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val target = clampSliderTarget(sliderValue, totalCount)
                            sliderValue = target.toFloat() // 立即锁定，不跳变

                            scope.launch {
                                val currentIdx = visibleIndex
                                // 策略2: 混合跳转
                                if (abs(target - currentIdx) > 10) {
                                    // 长跳：先瞬移到目标附近（aspectRatio 保证瞬间完成）
                                    val midTarget = if (target > currentIdx)
                                        (target - 3).coerceAtLeast(0)
                                    else
                                        (target + 3).coerceAtMost(totalCount - 1)
                                    listState.scrollToItem(midTarget)
                                }
                                // 最后一段动画平滑到达
                                listState.animateScrollToItem(target)
                            }
                        },
                        valueRange = 0f..(totalCount - 1).toFloat().coerceAtLeast(0f),
                        modifier = Modifier.fillMaxWidth(),
                        interactionSource = sliderInteractionSource,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// 策略3: AspectRatio 占位图片
// ══════════════════════════════════════════════════════════════════

/** 未知宽高比时的默认值 */
private val DEFAULT_ASPECT_RATIO = 16f / 9f

/**
 * 带宽高比占位的图片组件。
 *
 * 在 Coil 加载图片之前用 [Modifier.aspectRatio] 撑起准确高度。
 * LazyColumn 无需等待解码即可计算偏移量 → scrollToItem 瞬间完成。
 */
@Composable
private fun ImageWithAspectPlaceholder(item: MediaItem) {
    val ratio = if (item.aspectRatio > 0f) item.aspectRatio else DEFAULT_ASPECT_RATIO

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) { /* 占位背景 */ }

        AsyncImage(
            model = item.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillWidth
        )
    }
}

// ══════════════════════════════════════════════════════════════════
// 手势工具
// ══════════════════════════════════════════════════════════════════

private fun PointerEvent.zoomChange(): Float {
    val changes = changes
    if (changes.size < 2) return 1f
    val p0 = changes[0]
    val p1 = changes[1]
    if (!p0.pressed || !p1.pressed) return 1f
    val prevDx = p0.previousPosition.x - p1.previousPosition.x
    val prevDy = p0.previousPosition.y - p1.previousPosition.y
    val prevDist = sqrt(prevDx * prevDx + prevDy * prevDy)
    val currDx = p0.position.x - p1.position.x
    val currDy = p0.position.y - p1.position.y
    val currDist = sqrt(currDx * currDx + currDy * currDy)
    if (prevDist < 1f) return 1f
    return (currDist / prevDist)
}

private fun PointerEvent.panChange(): Offset {
    val change = changes.firstOrNull { it.pressed } ?: return Offset.Zero
    return Offset(
        change.position.x - change.previousPosition.x,
        change.position.y - change.previousPosition.y
    )
}

// ══════════════════════════════════════════════════════════════════
// 视频缩略图
// ══════════════════════════════════════════════════════════════════

@Composable
private fun VideoThumbnail(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
