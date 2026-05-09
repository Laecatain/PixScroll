package com.example.reader.ui.folderlist

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.AndroidMediaRepository
import com.example.reader.data.repository.MediaRepository
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.util.PreferenceKeys
import com.example.reader.util.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FolderListState(
    val folders: List<MediaFolder> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class FolderListViewModel(
    private val repository: MediaRepository,
    private val application: Application? = null
) : ViewModel() {

    private val _state = MutableStateFlow(FolderListState())
    val state: StateFlow<FolderListState> = _state.asStateFlow()

    var sortMode by mutableStateOf(SortMode.DATE)
        private set
    var sortOrder by mutableStateOf(SortOrder.DESC)
        private set

    init {
        viewModelScope.launch {
            if (application != null) {
                val prefs = application.dataStore.data.first()
                sortMode = try {
                    SortMode.valueOf(prefs[PreferenceKeys.SORT_MODE] ?: "DATE")
                } catch (_: IllegalArgumentException) { SortMode.DATE }
                sortOrder = try {
                    SortOrder.valueOf(prefs[PreferenceKeys.SORT_ORDER] ?: "DESC")
                } catch (_: IllegalArgumentException) { SortOrder.DESC }
            }
            loadFolders()
        }
    }

    fun updateSortMode(mode: SortMode) {
        sortMode = mode
        loadFolders()
    }

    fun updateSortOrder(order: SortOrder) {
        sortOrder = order
        loadFolders()
    }

    fun toggleSortOrder() {
        sortOrder = if (sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
        loadFolders()
    }

    private fun loadFolders() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                repository.getAllFolders(sortMode = sortMode, sortOrder = sortOrder).collect { folders ->
                    _state.value = FolderListState(folders = folders, isLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = FolderListState(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderListViewModel(
                AndroidMediaRepository(application.contentResolver),
                application
            ) as T
        }
    }
}
