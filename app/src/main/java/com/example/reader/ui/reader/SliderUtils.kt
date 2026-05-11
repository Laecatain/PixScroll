package com.example.reader.ui.reader

import kotlin.math.roundToInt

/** 将 Slider 的 Float 值四舍五入为合法的列表索引 [0, itemCount-1]。 */
fun clampSliderTarget(value: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return value.roundToInt().coerceIn(0, itemCount - 1)
}
