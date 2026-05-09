package com.example.reader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.example.reader.ui.theme.ThemeState
import androidx.datastore.preferences.core.edit
import com.example.reader.util.dataStore
import com.example.reader.util.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ReaderApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Load saved theme synchronously (DataStore first read is fast from disk)
        runBlocking {
            val prefs = dataStore.data.first()
            val savedMode = prefs[PreferenceKeys.THEME_MODE] ?: 1
            ThemeState.init(ThemeState.ThemeMode.fromValue(savedMode))
        }

        // Persist theme changes
        ThemeState.onModeChanged = { mode ->
            appScope.launch {
                dataStore.edit { prefs ->
                    prefs[PreferenceKeys.THEME_MODE] = mode.value
                }
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}
