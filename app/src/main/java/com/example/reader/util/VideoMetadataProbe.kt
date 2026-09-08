package com.example.reader.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Probe the longest edge of a video in pixels via MediaMetadataRetriever.
 *
 * Does NOT correct for rotation (unlike PlayerRouter.kt — we don't need
 * per-orientation accuracy for a coarse "is this > 1440px" gate). Returns 0
 * on any failure (missing scheme, IO error, missing metadata), which the
 * caller treats as "do not auto-route" — i.e. fails open to in-app playback.
 *
 * Synchronous + blocking. Documented cost: ~20-50ms on real devices for a
 * local MediaStore content:// URI. Caller should invoke from a click handler,
 * not from composition.
 */
fun probeLongestEdge(context: Context, uri: Uri): Int {
    val retriever = MediaMetadataRetriever()
    return try {
        when (uri.scheme) {
            "file" -> retriever.setDataSource(uri.path)
            "content" -> retriever.setDataSource(context, uri)
            else -> return 0
        }
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        maxOf(w, h)
    } catch (_: RuntimeException) {
        0
    } finally {
        retriever.release()
    }
}

/**
 * Pure decision: should this video bypass the user's video-player preference
 * and go straight to the system player?
 *
 * Rules (matching the user-approved product spec):
 * - If the auto-route toggle is OFF, never auto-route.
 * - If the probe returned 0 (failure), never auto-route — fails open to the
 *   user's preference rather than silently downgrading.
 * - Auto-route iff `longestEdge > threshold`.
 *
 * Extracted as a top-level pure function so it is JVM-testable without Android.
 */
fun shouldAutoRouteToSystem(
    enabled: Boolean,
    longestEdge: Int,
    threshold: Int,
): Boolean = enabled && longestEdge > 0 && longestEdge > threshold
