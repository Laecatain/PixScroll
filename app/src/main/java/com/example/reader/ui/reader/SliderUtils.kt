package com.example.reader.ui.reader

import kotlin.math.roundToInt

fun clampSliderTarget(value: Float, itemCount: Int): Int {
    return value.roundToInt().coerceIn(0, (itemCount - 1).coerceAtLeast(0))
}
