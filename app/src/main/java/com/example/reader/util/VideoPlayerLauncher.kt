package com.example.reader.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext

/**
 * Pure decision function — JVM-testable, no Android types.
 * Returns true if the user wants the in-app ExoPlayer; false for system player.
 */
fun shouldOpenInApp(preference: VideoPlayerPreference): Boolean =
    preference == VideoPlayerPreference.IN_APP

/**
 * Compose helper — reactive subscription to the video-player preference.
 * Centralizes the parse-with-fallback so callers can't drift on error handling.
 *
 * Cancellation-safe: uses narrow `try/catch (IllegalArgumentException)` to match
 * `SettingsViewModel.loadSettings`, never `runCatching` (which would swallow
 * `CancellationException` on screen leave).
 */
@Composable
fun rememberVideoPlayerPreference(): State<VideoPlayerPreference> {
    val context = LocalContext.current
    return produceState(VideoPlayerPreference.IN_APP) {
        context.applicationContext.settingsFlow().collect { data ->
            value = parseVideoPlayerPreference(data.videoPlayerPreference)
        }
    }
}

/**
 * Pure parser — JVM-testable. Returns [VideoPlayerPreference.IN_APP] on unknown
 * values or empty input. Used by both `SettingsViewModel.loadSettings` and
 * `rememberVideoPlayerPreference` to keep the fallback semantics in one place.
 */
fun parseVideoPlayerPreference(raw: String): VideoPlayerPreference =
    try {
        VideoPlayerPreference.valueOf(raw)
    } catch (_: IllegalArgumentException) {
        VideoPlayerPreference.IN_APP
    }

/**
 * Hand the video URI off to a system-installed player via Intent.ACTION_VIEW.
 *
 * Uses `Intent.createChooser` so the user always sees a chooser surface, even when
 * only one player is installed. FLAG_GRANT_READ_URI_PERMISSION lets the receiving
 * app read the `content://` URI we got from MediaStore without a FileProvider.
 *
 * Requires an Activity context (or one with FLAG_ACTIVITY_NEW_TASK set by caller)
 * — calling startActivity() from an Application context without that flag crashes.
 *
 * @throws ActivityNotFoundException when no system player is installed — caller
 *   decides fallback (typically: navigate to the in-app player instead).
 * @throws UnsupportedVideoUriException when the URI uses a scheme other than
 *   `content` (e.g. raw `file://` from unindexed media). `FLAG_GRANT_READ_URI_PERMISSION`
 *   does nothing for `file://`, and Android 7+ StrictMode would throw
 *   `FileUriExposedException` on Intent dispatch. Caller must fall back to in-app.
 */
class UnsupportedVideoUriException(val uri: Uri) :
    IllegalArgumentException("unsupported video URI scheme: ${uri.scheme}")

fun launchSystemPlayer(context: Context, videoUri: Uri) {
    if (videoUri.scheme != "content") {
        throw UnsupportedVideoUriException(videoUri)
    }
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(videoUri, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(viewIntent, "选择播放器"))
    } catch (e: ActivityNotFoundException) {
        Log.w("VideoPlayerLauncher", "no system video player available, scheme=${videoUri.scheme}")
        throw e
    }
}
