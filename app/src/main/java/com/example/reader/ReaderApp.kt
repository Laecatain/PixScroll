package com.example.reader

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.reader.ui.theme.ThemeState
import com.example.reader.util.CustomVideoFrameDecoder
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
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)   // 固定 100MB，避免分区百分比波动
                    .build()
            }
            .components { add(CustomVideoFrameDecoder.Factory()) }
            .crossfade(true)
            .bitmapConfig(Bitmap.Config.RGB_565)       // 缩略图不需要 alpha 通道，省 50% 内存
            .build()
    }
}
