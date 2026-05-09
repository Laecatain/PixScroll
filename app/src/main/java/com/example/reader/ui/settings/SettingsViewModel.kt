package com.example.reader.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.ui.theme.ThemeState
import com.example.reader.util.dataStore
import com.example.reader.util.saveGridColumns
import com.example.reader.util.saveSortMode
import com.example.reader.util.saveSortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsState(
    val themeMode: ThemeState.ThemeMode = ThemeState.ThemeMode.DARK,
    val sortMode: SortMode = SortMode.DATE,
    val sortOrder: SortOrder = SortOrder.DESC,
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
                sortMode = SortMode.valueOf(prefs[com.example.reader.util.PreferenceKeys.SORT_MODE] ?: "DATE"),
                sortOrder = SortOrder.valueOf(prefs[com.example.reader.util.PreferenceKeys.SORT_ORDER] ?: "DESC"),
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

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(sortMode = mode)
        viewModelScope.launch { application.saveSortMode(mode.name) }
    }

    fun setSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(sortOrder = order)
        viewModelScope.launch { application.saveSortOrder(order.name) }
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
