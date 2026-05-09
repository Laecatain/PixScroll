package com.example.reader.ui.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.model.MediaItem
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

enum class ReaderMode { ContinuousScroll, Pager }

data class ReaderState(
    val mediaItems: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val currentMode: ReaderMode = ReaderMode.ContinuousScroll,
    val isLoading: Boolean = true,
    val error: String? = null,
    val folderName: String = "",
    val sortMode: SortMode = SortMode.DATE,
    val sortOrder: SortOrder = SortOrder.DESC
)

class ReaderViewModel(
    private val repository: MediaRepository,
    private val parentId: Long,
    private val initialIndex: Int = 0,
    private val application: Application? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState(currentIndex = initialIndex))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var sortMode = SortMode.DATE
            var sortOrder = SortOrder.DESC
            if (application != null) {
                val prefs = application.dataStore.data.first()
                sortMode = try {
                    SortMode.valueOf(prefs[PreferenceKeys.SORT_MODE] ?: "DATE")
                } catch (_: IllegalArgumentException) { SortMode.DATE }
                sortOrder = try {
                    SortOrder.valueOf(prefs[PreferenceKeys.SORT_ORDER] ?: "DESC")
                } catch (_: IllegalArgumentException) { SortOrder.DESC }
            }
            _state.value = _state.value.copy(sortMode = sortMode, sortOrder = sortOrder)
            loadMedia()
        }
    }

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(sortMode = mode)
        loadMedia()
    }

    fun setSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(sortOrder = order)
        loadMedia()
    }

    fun toggleSortOrder() {
        val newOrder = if (_state.value.sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
        _state.value = _state.value.copy(sortOrder = newOrder)
        loadMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            try {
                repository.getMediaByFolder(
                    parentId,
                    sortMode = _state.value.sortMode,
                    sortOrder = _state.value.sortOrder
                ).collect { items ->
                    val folderName = items.firstOrNull()?.folderPath
                        ?.substringBeforeLast("/")?.substringAfterLast("/") ?: ""
                    _state.value = _state.value.copy(
                        mediaItems = items,
                        folderName = folderName,
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun setCurrentIndex(index: Int) {
        _state.value = _state.value.copy(currentIndex = index)
    }

    fun switchMode(mode: ReaderMode) {
        _state.value = _state.value.copy(currentMode = mode)
    }

    class Factory(
        private val application: Application,
        private val parentId: Long,
        private val initialIndex: Int = 0
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                AndroidMediaRepository(application.contentResolver),
                parentId,
                initialIndex,
                application
            ) as T
        }
    }
}
