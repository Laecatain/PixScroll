package com.example.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.reader.data.model.MediaItem
import com.example.reader.ui.theme.ThemeState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun ContinuousScrollReader(
    mediaItems: List<MediaItem>,
    onBack: () -> Unit,
    onSwitchMode: () -> Unit,
    onVideoClick: (MediaItem) -> Unit
) {
    var showToolbar by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val isZoomed = scale > 1f
    val totalCount = mediaItems.size
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var isAnimatingScroll by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var visibleIndex by remember { mutableStateOf(0) }

    LaunchedEffect(visibleIndex, isDragging, isAnimatingScroll) {
        if (!isDragging && !isAnimatingScroll) {
            sliderValue = visibleIndex.toFloat()
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
        val listState = rememberLazyListState()
        val idx = listState.firstVisibleItemIndex.coerceIn(0, (totalCount - 1).coerceAtLeast(0))
        LaunchedEffect(idx) { visibleIndex = idx }

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
            userScrollEnabled = !isZoomed
        ) {
            itemsIndexed(mediaItems, key = { _, item -> item.uri ?: item.name }) { _, item ->
                if (item.isVideo) {
                    VideoThumbnail(item = item, onClick = { onVideoClick(item) })
                } else {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }

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
                        onValueChange = {
                            isDragging = true
                            isAnimatingScroll = false
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            val target = sliderValue.roundToInt()
                                .coerceIn(0, (totalCount - 1).coerceAtLeast(0))
                            isDragging = false
                            sliderValue = target.toFloat()  // 立即锁定目标，消除回退
                            isAnimatingScroll = true
                            scope.launch {
                                try {
                                    listState.animateScrollToItem(target, scrollOffset = 0)
                                } finally {
                                    isAnimatingScroll = false
                                }
                            }
                        },
                        valueRange = 0f..(totalCount - 1).toFloat().coerceAtLeast(0f),
                        modifier = Modifier.fillMaxWidth(),
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
