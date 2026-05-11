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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.reader.data.model.MediaItem
import com.example.reader.ui.theme.ThemeState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PagerReader(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onVideoClick: (MediaItem) -> Unit,
    onIndexChange: (Int) -> Unit = {}
) {
    var showToolbar by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val safeInitial = initialIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeInitial) { mediaItems.size }
    val scope = rememberCoroutineScope()

    val isZoomed = scale > 1f
    val totalCount = mediaItems.size
    var rawSliderValue by remember { mutableFloatStateOf(safeInitial.toFloat()) }
    var isUserInteracting by remember { mutableStateOf(false) }

    // 策略1: derivedStateOf 声明式同步 —— PagerPage 到 Slider 是 1:1 映射，
    // 拖拽或跳转动画中锁定在 rawSliderValue，其余时刻从 currentPage 声明式推导。
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isDragged by sliderInteractionSource.collectIsDraggedAsState()

    val effectiveSliderValue by remember {
        derivedStateOf {
            if (isDragged || isUserInteracting || pagerState.isScrollInProgress) {
                rawSliderValue
            } else {
                pagerState.currentPage.toFloat()
            }
        }
    }

    // 监听翻页位置，同步到 ViewModel（distinctUntilChanged 过滤重组无效回调）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onIndexChange(page) }
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
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            userScrollEnabled = !isZoomed
        ) { page ->
            val item = mediaItems[page]
            if (item.isVideo) {
                PagerVideoItem(item = item, onClick = { onVideoClick(item) })
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
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
                        Text("连续滚动", color = Color.White)
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
                    val displayPage = (effectiveSliderValue.roundToInt() + 1)
                        .coerceIn(1, totalCount.coerceAtLeast(1))
                    Text(
                        text = "$displayPage / $totalCount",
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Slider(
                        value = effectiveSliderValue,
                        onValueChange = { rawSliderValue = it },
                        onValueChangeFinished = {
                            isUserInteracting = true  // SYNC: must be before coroutine dispatch
                            val target = clampSliderTarget(rawSliderValue, totalCount)
                            rawSliderValue = target.toFloat()
                            val curPage = pagerState.currentPage
                            scope.launch {
                                try {
                                    if (abs(target - curPage) > 10) {
                                        val midTarget = if (target > curPage)
                                            (target - 3).coerceAtLeast(0)
                                        else
                                            (target + 3).coerceAtMost(totalCount - 1)
                                        pagerState.scrollToPage(midTarget)
                                    }
                                    pagerState.animateScrollToPage(target)
                                } finally {
                                    isUserInteracting = false
                                }
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

private fun androidx.compose.ui.input.pointer.PointerEvent.zoomChange(): Float {
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

private fun androidx.compose.ui.input.pointer.PointerEvent.panChange(): Offset {
    val change = changes.firstOrNull { it.pressed } ?: return Offset.Zero
    return Offset(
        change.position.x - change.previousPosition.x,
        change.position.y - change.previousPosition.y
    )
}

@Composable
private fun PagerVideoItem(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
