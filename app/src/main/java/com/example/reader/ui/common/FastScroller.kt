package com.example.reader.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 右侧快速滚动条。适配 [LazyGridState]。
 *
 * - 触控区 24dp，视觉滑块条 6dp×50dp 圆角矩形
 * - 拖拽/滚动中显示（alpha=0.8），停止 1.5s 后以 300ms 渐隐至 0
 * - 拖拽时在滑块左侧显示半透明页码气泡
 * - [itemCount] ≤ 1 时不渲染
 */
@Composable
fun FastScroller(
    gridState: LazyGridState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    if (itemCount <= 1) return

    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    val isScrolling = gridState.isScrollInProgress

    // 当前拖拽到的索引（用于气泡显示）
    var dragTargetIndex by remember { mutableStateOf(0) }

    fun targetIndexAt(y: Float, height: Int) =
        ((y / height).coerceIn(0f, 1f) * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)

    val alpha = remember { Animatable(0f) }
    val targetAlpha = if (isDragging || isScrolling) 0.8f else 0f
    LaunchedEffect(targetAlpha) {
        if (targetAlpha > 0f) {
            alpha.snapTo(targetAlpha)
        } else {
            delay(1500)
            alpha.animateTo(0f, tween(durationMillis = 300))
        }
    }

    val progress by remember(itemCount) {
        derivedStateOf {
            gridState.firstVisibleItemIndex.toFloat() / (itemCount - 1).toFloat()
        }
    }

    var containerHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val thumbHeight = 50.dp
    val maxOffset = (containerHeightDp - thumbHeight).coerceAtLeast(0.dp)

    // 页码气泡
    val bubbleText = "${dragTargetIndex + 1} / $itemCount"

    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight()
            .alpha(alpha.value)
            .onSizeChanged {
                containerHeightDp = with(density) { it.height.toDp() }
            }
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragTargetIndex = targetIndexAt(offset.y, size.height)
                        val target = dragTargetIndex
                        scrollJob?.cancel()
                        scrollJob = scope.launch { gridState.scrollToItem(target) }
                    },
                    onVerticalDrag = { change, _ ->
                        dragTargetIndex = targetIndexAt(change.position.y, size.height)
                        val target = dragTargetIndex
                        scrollJob?.cancel()
                        scrollJob = scope.launch { gridState.scrollToItem(target) }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        // 页码气泡
        if (isDragging) {
            PageNumberBubble(
                text = bubbleText,
                offsetY = maxOffset * progress
            )
        }

        // 滑块条
        Box(
            modifier = Modifier
                .padding(horizontal = 9.dp)
                .width(6.dp)
                .height(thumbHeight)
                .offset(y = maxOffset * progress)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun PageNumberBubble(
    text: String,
    offsetY: Dp
) {
    Surface(
        modifier = Modifier
            .offset(x = (-40).dp, y = offsetY)
            .widthIn(min = 40.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.7f),
        shadowElevation = 4.dp
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
