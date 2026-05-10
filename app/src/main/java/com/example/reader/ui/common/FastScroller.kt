package com.example.reader.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 右侧快速滚动条。适配 [LazyGridState]。
 *
 * - 触控区 32dp，视觉滑块条 4dp（触控范围 > 视觉范围，易用性优先）
 * - 拖拽/滚动中立即显示（alpha=1），停止 1.5s 后以 300ms 渐隐至 0
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
    var isDragging by remember { mutableStateOf(false) }
    val isScrolling = gridState.isScrollInProgress

    val alpha = remember { Animatable(0f) }
    LaunchedEffect(isDragging, isScrolling) {
        if (isDragging || isScrolling) {
            alpha.snapTo(1f)
        } else {
            delay(1500)
            alpha.animateTo(0f, tween(durationMillis = 300))
        }
    }

    val progress by remember(itemCount) {
        derivedStateOf {
            val total = itemCount.coerceAtLeast(1)
            if (total <= 1) 0f
            else gridState.firstVisibleItemIndex.toFloat() / (total - 1).toFloat()
        }
    }

    var containerHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val thumbHeight = 40.dp
    val maxOffset = (containerHeightDp - thumbHeight).coerceAtLeast(0.dp)

    Box(
        modifier = modifier
            .width(32.dp)
            .fillMaxHeight()
            .alpha(alpha.value)
            .onSizeChanged {
                containerHeightDp = with(density) { it.height.toDp() }
            }
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val p = (offset.y / size.height).coerceIn(0f, 1f)
                        val target = (p * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
                        scope.launch { gridState.scrollToItem(target) }
                    },
                    onVerticalDrag = { change, _ ->
                        val p = (change.position.y / size.height).coerceIn(0f, 1f)
                        val target = (p * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
                        scope.launch { gridState.scrollToItem(target) }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .width(4.dp)
                .height(thumbHeight)
                .offset(y = maxOffset * progress)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        )
    }
}
