package com.example.reader.ui.search

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.AndroidMediaRepository
import com.example.reader.data.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SearchCategory { FOLDERS, VIDEOS, IMAGES }

data class SearchState(
    val query: String = "",
    val selectedCategory: SearchCategory = SearchCategory.FOLDERS,
    val folderResults: List<MediaFolder> = emptyList(),
    val videoResults: List<MediaItem> = emptyList(),
    val imageResults: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null
)

class SearchViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = SearchState()
            return
        }

        val trimmedQuery = query.trim()
        _state.value = _state.value.copy(
            query = query,
            isLoading = true,
            hasSearched = false,
            error = null
        )
        searchJob = viewModelScope.launch {
            try {
                val folders = repository.searchFolders(trimmedQuery).first()
                val mediaItems = repository.searchMedia(trimmedQuery).first()
                _state.value = _state.value.copy(
                    folderResults = folders,
                    videoResults = mediaItems.filter { it.isVideo },
                    imageResults = mediaItems.filter { !it.isVideo },
                    isLoading = false,
                    hasSearched = true,
                    error = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "搜索失败",
                    hasSearched = true
                )
            }
        }
    }

    fun selectCategory(category: SearchCategory) {
        _state.value = _state.value.copy(selectedCategory = category)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.value = SearchState()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(AndroidMediaRepository(application.contentResolver, application.cacheDir)) as T
        }
    }
}
