package com.example.reader.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ThemeState {
    enum class ThemeMode(val value: Int) {
        LIGHT(0), DARK(1), AMOLED_BLACK(2);

        companion object {
            fun fromValue(v: Int): ThemeMode = entries.firstOrNull { it.value == v } ?: DARK
        }
    }

    var themeMode by mutableStateOf(ThemeMode.DARK)
        private set

    var onModeChanged: (ThemeMode) -> Unit = {}

    fun init(mode: ThemeMode) {
        themeMode = mode
    }

    fun cycle() {
        themeMode = when (themeMode) {
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.AMOLED_BLACK
            ThemeMode.AMOLED_BLACK -> ThemeMode.LIGHT
        }
        onModeChanged(themeMode)
    }

    val isDark get() = themeMode != ThemeMode.LIGHT
}
