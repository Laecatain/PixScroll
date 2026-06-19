package com.example.reader.util

/**
 * Format milliseconds to "MM:SS" or "HH:MM:SS".
 */
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0)
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    else
        "%02d:%02d".format(minutes, seconds)
}
