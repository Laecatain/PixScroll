package com.example.reader.ui.search

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.ReaderApp
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

enum class SearchCategory { FOLDERS, IMAGES, VIDEOS }

data class SearchState(
    val query: String = "",
    val results: List<MediaItem> = emptyList(),
    val folderResults: List<MediaFolder> = emptyList(),
    val videoResults: List<MediaItem> = emptyList(),
    val imageResults: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val selectedCategory: SearchCategory = SearchCategory.FOLDERS
)

class SearchViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    private val searchCache = object : LinkedHashMap<String, SearchState>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchState>?): Boolean {
            return size > 20
        }
    }

    fun onQueryChange(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = SearchState()
            return
        }

        val trimmedQuery = query.trim()
        val key = trimmedQuery.lowercase()
        val cached: SearchState? = synchronized(searchCache) { searchCache[key] }
        if (cached != null) {
            _state.value = cached.copy(isLoading = false)
            return
        }

        _state.value = _state.value.copy(
            query = query, isLoading = true, hasSearched = false, error = null
        )

        searchJob = viewModelScope.launch {
            delay(300)
            val trimmedQuery = query.trim()
            try {
                val (folders, mediaItems) = coroutineScope {
                    val foldersDeferred = async {
                        try {
                            repository.searchFolders(trimmedQuery).first()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList<MediaFolder>()
                        }
                    }
                    val mediaDeferred = async {
                        try {
                            repository.searchMedia(trimmedQuery).first()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList<MediaItem>()
                        }
                    }
                    Pair(foldersDeferred.await(), mediaDeferred.await())
                }
                val newState = _state.value.copy(
                    folderResults = folders,
                    videoResults = mediaItems.filter { it.isVideo },
                    imageResults = mediaItems.filter { !it.isVideo },
                    results = mediaItems,
                    isLoading = false, hasSearched = true, error = null
                )
                synchronized(searchCache) { searchCache[trimmedQuery.lowercase()] = newState }
                _state.value = newState
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false, error = e.message ?: "搜索失败", hasSearched = true
                )
            }
        }
    }

    fun selectCategory(category: SearchCategory) {
        _state.value = _state.value.copy(selectedCategory = category)
    }

    fun clearSearch() {
        searchJob?.cancel()
        synchronized(searchCache) { searchCache.clear() }
        _state.value = SearchState()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel((application as ReaderApp).mediaRepository) as T
        }
    }
}
