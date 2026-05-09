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

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

object PreferenceKeys {
    val THEME_MODE = intPreferencesKey("theme_mode")
    val SORT_MODE = stringPreferencesKey("sort_mode")
    val SORT_ORDER = stringPreferencesKey("sort_order")
    val GRID_COLUMNS = intPreferencesKey("grid_columns")
}

data class AppSettingsData(
    val themeMode: Int = 1,       // 0=LIGHT, 1=DARK, 2=AMOLED_BLACK
    val sortMode: String = "DATE",
    val sortOrder: String = "DESC",
    val gridColumns: Int = 3
)

fun Context.settingsFlow(): Flow<AppSettingsData> = dataStore.data.map { prefs ->
    AppSettingsData(
        themeMode = prefs[PreferenceKeys.THEME_MODE] ?: 1,
        sortMode = prefs[PreferenceKeys.SORT_MODE] ?: "DATE",
        sortOrder = prefs[PreferenceKeys.SORT_ORDER] ?: "DESC",
        gridColumns = prefs[PreferenceKeys.GRID_COLUMNS] ?: 3
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

suspend fun Context.saveGridColumns(columns: Int) {
    dataStore.edit { prefs -> prefs[PreferenceKeys.GRID_COLUMNS] = columns }
}
