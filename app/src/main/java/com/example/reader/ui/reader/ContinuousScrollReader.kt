package com.example.reader.ui.reader

import android.util.Log
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.reader.data.model.MediaItem
import com.example.reader.ui.theme.ThemeState
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "ContinuousScroll"
private const val LONG_JUMP_THRESHOLD = 10
private const val LONG_JUMP_OFFSET = 3

/**
 * 连续滚动阅读器（条漫模式）。
 *
 * ## 核心策略
 *
 * ### 状态锁 (State Decoupling)
 * [MutableInteractionSource.collectIsDraggedAsState] + [LazyListState.isScrollInProgress]
 * 双条件控制：(!isDragged && !isScrolling) 时才将 firstVisibleItemIndex → sliderValue。
 *
 * ### 混合跳转 (Hybrid Scroll)
 * |target - current| > 10: scrollToItem(target±3) 瞬移 → animateScrollToItem(target) 补间。
 * aspectRatio 占位保证 scrollToItem 瞬间完成。
 *
 * ### 初始定位
 * 冷启动: [rememberLazyListState] 的 initialFirstVisibleItemIndex 直接定位，首帧不白屏。
 * 热启动: [LaunchedEffect] + [snapshotFlow] 监听 totalItemsCount，数据就绪后执行跳转。
 */
@Composable
fun ContinuousScrollReader(
    mediaItems: List<MediaItem>,
    initialIndex: Int = 0,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onIndexChange: (Int) -> Unit = {}
) {
    // ── 缩放 ──
    var showToolbar by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val isZoomed = scale > 1f
    val totalCount = mediaItems.size
    val scope = rememberCoroutineScope()

    // ── 滑块状态 ──
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var visibleIndex by remember { mutableStateOf(0) }

    // 状态锁: MutableInteractionSource 感知 Slider 拖拽
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isDragged by sliderInteractionSource.collectIsDraggedAsState()

    // 冷启动: 计算居中偏移，用 initialFirstVisibleItemScrollOffset 使目标图片垂直居中
    val safeInitial = initialIndex.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val coldCenteringOffset = remember(safeInitial, mediaItems.getOrNull(safeInitial)) {
        calculateCenteringOffset(mediaItems, safeInitial, screenHeightPx, screenWidthPx)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = safeInitial,
        initialFirstVisibleItemScrollOffset = coldCenteringOffset
    )

    // isScrollInProgress 在 animateScrollToItem 动画期间为 true
    val isScrolling = listState.isScrollInProgress

    // 状态锁：用 snapshotFlow 监听 firstVisibleItemIndex，
    // distinctUntilChanged 过滤连续相同值，避免滚动时过度回调
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { raw ->
                val idx = raw.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
                visibleIndex = idx
                onIndexChange(idx)
            }
    }

    // 状态锁: 拖拽中或动画中不反写 sliderValue
    LaunchedEffect(visibleIndex, isDragged, isScrolling) {
        if (!isDragged && !isScrolling) {
            sliderValue = visibleIndex.toFloat()
        }
    }

    // 热启动: 监听 totalItemsCount，数据就绪后执行跳转（防竞态）
    LaunchedEffect(initialIndex) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .collect { count ->
                if (count > 0) {
                    val target = initialIndex.coerceIn(0, count - 1)
                    // 状态锁：已在目标位置则跳过，防止 onIndexChange → LaunchedEffect 反馈环
                    if (listState.firstVisibleItemIndex == target) return@collect
                    Log.d(TAG, "热启动跳转: target=$target total=$count")
                    val vp = listState.layoutInfo.viewportSize.height.toFloat()
                    val offset = calculateCenteringOffset(mediaItems, target, vp, screenWidthPx)
                    if (abs(target - listState.firstVisibleItemIndex) > LONG_JUMP_THRESHOLD) {
                        val midTarget = if (target > listState.firstVisibleItemIndex)
                            (target - LONG_JUMP_OFFSET).coerceAtLeast(0)
                        else
                            (target + LONG_JUMP_OFFSET).coerceAtMost(count - 1)
                        listState.scrollToItem(midTarget)
                    }
                    listState.animateScrollToItem(target, scrollOffset = offset)
                    // 只执行一次
                    return@collect
                }
            }
    }

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
            contentPadding = PaddingValues(vertical = screenHeightDp)
        ) {
            itemsIndexed(
                mediaItems,
                key = { _, item -> item.uri ?: item.name },
                // contentType 优化：同类型 Item 可高效复用 Composition
                contentType = { _, item -> if (item.isVideo) "video" else "image" }
            ) { _, item ->
                if (item.isVideo) {
                    VideoThumbnail(item = item, onClick = { onVideoClick(item) })
                } else {
                    ImageWithAspectPlaceholder(item = item)
                }
            }
        }

        // ── 顶部工具栏 ──
        AnimatedVisibility(
            visible = showToolbar, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.7f),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { ThemeState.cycle() }) {
                        Icon(
                            if (ThemeState.isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            "切换主题", tint = Color.White
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
            visible = showToolbar, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.5f),
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "${visibleIndex + 1} / $totalCount", color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val target = clampSliderTarget(sliderValue, totalCount)
                            sliderValue = target.toFloat()
                            scope.launch {
                                val cur = visibleIndex
                                if (abs(target - cur) > LONG_JUMP_THRESHOLD) {
                                    val mid = if (target > cur)
                                        (target - LONG_JUMP_OFFSET).coerceAtLeast(0)
                                    else
                                        (target + LONG_JUMP_OFFSET).coerceAtMost(totalCount - 1)
                                    listState.scrollToItem(mid)
                                }
                                val vp = listState.layoutInfo.viewportSize.height.toFloat()
                                val offset = calculateCenteringOffset(mediaItems, target, vp, screenWidthPx)
                                listState.animateScrollToItem(target, scrollOffset = offset)
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

// ═══════════════════════════════════ AspectRatio 占位 ═══════════════════════════════════

private val DEFAULT_ASPECT_RATIO = 16f / 9f

/** 计算首次跳转居中偏移。短图片负偏移垂直居中，长图片（超过一屏）返回 0 顶对齐。 */
private fun calculateCenteringOffset(
    mediaItems: List<MediaItem>,
    targetIndex: Int,
    viewportPx: Float,
    screenWidthPx: Float
): Int {
    val ratio = mediaItems.getOrNull(targetIndex)?.aspectRatio
        ?.takeIf { it > 0f } ?: DEFAULT_ASPECT_RATIO
    val itemHeightPx = screenWidthPx / ratio  // ContentScale.FillWidth 下的实际渲染高度
    val gap = (viewportPx - itemHeightPx) / 2f
    return if (gap > 0) -gap.toInt() else 0   // 负偏移 = 内容前加边距
}

@Composable
private fun ImageWithAspectPlaceholder(item: MediaItem) {
    val ratio = if (item.aspectRatio > 0f) item.aspectRatio else DEFAULT_ASPECT_RATIO
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) { /* 占位 */ }
        AsyncImage(
            model = item.uri, contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillWidth
        )
    }
}

// ═══════════════════════════════════ 手势 ═══════════════════════════════════

private fun PointerEvent.zoomChange(): Float {
    val changes = changes
    if (changes.size < 2) return 1f
    val p0 = changes[0]; val p1 = changes[1]
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

// ═══════════════════════════════════ 视频缩略图 ═══════════════════════════════════

@Composable
private fun VideoThumbnail(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(model = item.uri, contentDescription = item.name,
            modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        Surface(shape = MaterialTheme.shapes.extraLarge,
            color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
