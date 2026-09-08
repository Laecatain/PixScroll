package com.example.reader.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Folder cover strategy */
enum class FolderCoverStrategy {
    LATEST,
    EARLIEST,
    RANDOM,
    BY_SORT
}

/** Video cover frame extraction strategy */
enum class VideoCoverStrategy {
    EXACT_1S,
    MID_FRAME,
    CLOSEST_KEYFRAME
}

/** Which player to open when the user taps a video */
enum class VideoPlayerPreference {
    /** Use the in-app ExoPlayer (default) */
    IN_APP,
    /** Hand off to a system-installed video player via Intent.ACTION_VIEW */
    SYSTEM
}


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

object PreferenceKeys {
    val THEME_MODE = intPreferencesKey("theme_mode")
    val SORT_MODE = stringPreferencesKey("sort_mode")               // media list sort (legacy/non-specific)
    val SORT_ORDER = stringPreferencesKey("sort_order")              // media list sort direction
    val IMAGE_SORT_MODE = stringPreferencesKey("image_sort_mode")    // image folder sort
    val IMAGE_SORT_ORDER = stringPreferencesKey("image_sort_order")  // image folder sort direction
    val VIDEO_SORT_MODE = stringPreferencesKey("video_sort_mode")    // video folder sort
    val VIDEO_SORT_ORDER = stringPreferencesKey("video_sort_order")  // video folder sort direction
    val FOLDER_SORT_MODE = stringPreferencesKey("folder_sort_mode")   // home folder sort
    val FOLDER_SORT_ORDER = stringPreferencesKey("folder_sort_order") // home folder sort direction
    val GRID_COLUMNS = intPreferencesKey("grid_columns")
    val FOLDER_COVER_STRATEGY = stringPreferencesKey("folder_cover_strategy")
    val VIDEO_COVER_STRATEGY = stringPreferencesKey("video_cover_strategy")
    val COVER_SORT_MODE = stringPreferencesKey("cover_sort_mode")
    val COVER_SORT_ORDER = stringPreferencesKey("cover_sort_order")
    val VIDEO_PLAYER_PREFERENCE = stringPreferencesKey("video_player_preference")
}

data class AppSettingsData(
    val themeMode: Int = 1,                // 0=LIGHT, 1=DARK, 2=AMOLED_BLACK
    val sortMode: String = "DATE",         // media list sort (legacy)
    val sortOrder: String = "DESC",
    val imageSortMode: String = "DATE",    // image folder sort
    val imageSortOrder: String = "DESC",
    val videoSortMode: String = "DATE",    // video folder sort
    val videoSortOrder: String = "DESC",
    val folderSortMode: String = "DATE",   // home folder sort
    val folderSortOrder: String = "DESC",
    val gridColumns: Int = 3,
    val folderCoverStrategy: String = "LATEST",
    val videoCoverStrategy: String = "EXACT_1S",
    val coverSortMode: String = "DATE",
    val coverSortOrder: String = "DESC",
    val videoPlayerPreference: String = "IN_APP"
)

fun Context.settingsFlow(): Flow<AppSettingsData> = dataStore.data.map { prefs ->
    AppSettingsData(
        themeMode = prefs[PreferenceKeys.THEME_MODE] ?: 1,
        sortMode = prefs[PreferenceKeys.SORT_MODE] ?: "DATE",
        sortOrder = prefs[PreferenceKeys.SORT_ORDER] ?: "DESC",
        imageSortMode = prefs[PreferenceKeys.IMAGE_SORT_MODE] ?: "DATE",
        imageSortOrder = prefs[PreferenceKeys.IMAGE_SORT_ORDER] ?: "DESC",
        videoSortMode = prefs[PreferenceKeys.VIDEO_SORT_MODE] ?: "DATE",
        videoSortOrder = prefs[PreferenceKeys.VIDEO_SORT_ORDER] ?: "DESC",
        folderSortMode = prefs[PreferenceKeys.FOLDER_SORT_MODE] ?: "DATE",
        folderSortOrder = prefs[PreferenceKeys.FOLDER_SORT_ORDER] ?: "DESC",
        gridColumns = prefs[PreferenceKeys.GRID_COLUMNS] ?: 3,
        folderCoverStrategy = prefs[PreferenceKeys.FOLDER_COVER_STRATEGY] ?: "LATEST",
        videoCoverStrategy = prefs[PreferenceKeys.VIDEO_COVER_STRATEGY] ?: "EXACT_1S",
        coverSortMode = prefs[PreferenceKeys.COVER_SORT_MODE] ?: "DATE",
        coverSortOrder = prefs[PreferenceKeys.COVER_SORT_ORDER] ?: "DESC",
        videoPlayerPreference = prefs[PreferenceKeys.VIDEO_PLAYER_PREFERENCE] ?: "IN_APP"
    )
}

suspend fun Context.saveThemeMode(mode: Int) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.THEME_MODE] = mode }
}

suspend fun Context.saveSortMode(mode: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.SORT_MODE] = mode }
}

suspend fun Context.saveSortOrder(order: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.SORT_ORDER] = order }
}

suspend fun Context.saveImageSortMode(mode: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.IMAGE_SORT_MODE] = mode }
}

suspend fun Context.saveImageSortOrder(order: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.IMAGE_SORT_ORDER] = order }
}

suspend fun Context.saveVideoSortMode(mode: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.VIDEO_SORT_MODE] = mode }
}

suspend fun Context.saveVideoSortOrder(order: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.VIDEO_SORT_ORDER] = order }
}

suspend fun Context.saveFolderSortMode(mode: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.FOLDER_SORT_MODE] = mode }
}

suspend fun Context.saveFolderSortOrder(order: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.FOLDER_SORT_ORDER] = order }
}

suspend fun Context.saveGridColumns(columns: Int) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.GRID_COLUMNS] = columns }
}
suspend fun Context.saveFolderCoverStrategy(strategy: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.FOLDER_COVER_STRATEGY] = strategy }
}

suspend fun Context.saveVideoCoverStrategy(strategy: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.VIDEO_COVER_STRATEGY] = strategy }
}

suspend fun Context.saveCoverSortMode(mode: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.COVER_SORT_MODE] = mode }
}

suspend fun Context.saveCoverSortOrder(order: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.COVER_SORT_ORDER] = order }
}

suspend fun Context.saveVideoPlayerPreference(preference: String) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.VIDEO_PLAYER_PREFERENCE] = preference }
}
