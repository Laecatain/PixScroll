package com.example.reader.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.ui.theme.ThemeState
import com.example.reader.util.dataStore
import com.example.reader.util.saveFolderSortMode
import com.example.reader.util.saveFolderSortOrder
import com.example.reader.util.saveGridColumns
import com.example.reader.util.saveImageSortMode
import com.example.reader.util.saveImageSortOrder
import com.example.reader.util.saveVideoSortMode
import com.example.reader.util.saveVideoSortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsState(
    val themeMode: ThemeState.ThemeMode = ThemeState.ThemeMode.DARK,
    val imageSortMode: SortMode = SortMode.DATE,
    val imageSortOrder: SortOrder = SortOrder.DESC,
    val videoSortMode: SortMode = SortMode.DATE,
    val videoSortOrder: SortOrder = SortOrder.DESC,
    val folderSortMode: SortMode = SortMode.DATE,
    val folderSortOrder: SortOrder = SortOrder.DESC,
    val gridColumns: Int = 3,
    val showHidden: Boolean = false
)

class SettingsViewModel(
    private val application: Application
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val prefs = application.dataStore.data.first()
            _state.value = SettingsState(
                themeMode = ThemeState.themeMode,
                imageSortMode = SortMode.valueOf(prefs[com.example.reader.util.PreferenceKeys.IMAGE_SORT_MODE] ?: "DATE"),
                imageSortOrder = SortOrder.valueOf(prefs[com.example.reader.util.PreferenceKeys.IMAGE_SORT_ORDER] ?: "DESC"),
                videoSortMode = SortMode.valueOf(prefs[com.example.reader.util.PreferenceKeys.VIDEO_SORT_MODE] ?: "DATE"),
                videoSortOrder = SortOrder.valueOf(prefs[com.example.reader.util.PreferenceKeys.VIDEO_SORT_ORDER] ?: "DESC"),
                folderSortMode = SortMode.valueOf(prefs[com.example.reader.util.PreferenceKeys.FOLDER_SORT_MODE] ?: "DATE"),
                folderSortOrder = SortOrder.valueOf(prefs[com.example.reader.util.PreferenceKeys.FOLDER_SORT_ORDER] ?: "DESC"),
                gridColumns = prefs[com.example.reader.util.PreferenceKeys.GRID_COLUMNS] ?: 3,
                showHidden = false
            )
        }
    }

    fun setThemeMode(mode: ThemeState.ThemeMode) {
        ThemeState.init(mode)
        ThemeState.onModeChanged(mode)
        _state.value = _state.value.copy(themeMode = mode)
    }

    /** image folder sort */
    fun setImageSortMode(mode: SortMode) {
        _state.value = _state.value.copy(imageSortMode = mode)
        viewModelScope.launch { application.saveImageSortMode(mode.name) }
    }

    fun setImageSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(imageSortOrder = order)
        viewModelScope.launch { application.saveImageSortOrder(order.name) }
    }

    /** video folder sort */
    fun setVideoSortMode(mode: SortMode) {
        _state.value = _state.value.copy(videoSortMode = mode)
        viewModelScope.launch { application.saveVideoSortMode(mode.name) }
    }

    fun setVideoSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(videoSortOrder = order)
        viewModelScope.launch { application.saveVideoSortOrder(order.name) }
    }

    /** home folder sort */
    fun setFolderSortMode(mode: SortMode) {
        _state.value = _state.value.copy(folderSortMode = mode)
        viewModelScope.launch { application.saveFolderSortMode(mode.name) }
    }

    fun setFolderSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(folderSortOrder = order)
        viewModelScope.launch { application.saveFolderSortOrder(order.name) }
    }

    fun setGridColumns(columns: Int) {
        _state.value = _state.value.copy(gridColumns = columns)
        viewModelScope.launch { application.saveGridColumns(columns) }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(application) as T
        }
    }
}
