package com.example.reader.ui.reader

import android.app.Application
// MediaStore constants: IMAGE=1, VIDEO=3
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.AndroidMediaRepository
import com.example.reader.data.repository.MediaRepository
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.util.ThumbnailBackfillManager
import com.example.reader.util.ThumbnailManager
import com.example.reader.util.saveImageSortMode
import com.example.reader.util.saveImageSortOrder
import com.example.reader.util.saveSortMode
import com.example.reader.util.saveSortOrder
import com.example.reader.util.saveVideoSortMode
import com.example.reader.util.saveVideoSortOrder
import com.example.reader.util.settingsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderMode { ContinuousScroll, Pager }

@Immutable
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

private class SortPrefs(
    val mode: SortMode,
    val order: SortOrder,
    val modeKey: String,
    val orderKey: String,
    val saveMode: suspend (Application, String) -> Unit,
    val saveOrder: suspend (Application, String) -> Unit
)

private fun sortPrefsFor(mediaType: Int?): SortPrefs {
    return when (mediaType) {
        1 /* IMAGE */ -> SortPrefs(
            mode = SortMode.DATE, order = SortOrder.DESC,
            modeKey = "imageSortMode", orderKey = "imageSortOrder",
            saveMode = { app, mode -> app.saveImageSortMode(mode) },
            saveOrder = { app, order -> app.saveImageSortOrder(order) }
        )
        3 /* VIDEO */ -> SortPrefs(
            mode = SortMode.DATE, order = SortOrder.DESC,
            modeKey = "videoSortMode", orderKey = "videoSortOrder",
            saveMode = { app, mode -> app.saveVideoSortMode(mode) },
            saveOrder = { app, order -> app.saveVideoSortOrder(order) }
        )
        else -> SortPrefs(
            mode = SortMode.DATE, order = SortOrder.DESC,
            modeKey = "sortMode", orderKey = "sortOrder",
            saveMode = { app, mode -> app.saveSortMode(mode) },
            saveOrder = { app, order -> app.saveSortOrder(order) }
        )
    }
}

class ReaderViewModel(
    private val repository: MediaRepository,
    private val parentId: Long,
    private val initialIndex: Int = 0,
    private val application: Application? = null,
    private val mediaType: Int? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState(currentIndex = initialIndex))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    // ?? Generation ID Ticket System ??
    private var mediaLoadJob: Job? = null
    @Volatile private var currentGeneration = 0
    @Volatile private var isFrozen = false

    private val thumbnailManager: ThumbnailManager? = application?.let { ThumbnailManager(it) }
    private val sortPrefs = sortPrefsFor(mediaType)
    private val backfillManager: ThumbnailBackfillManager? =
        thumbnailManager?.let { ThumbnailBackfillManager(it) }

    init {
        if (application != null) {
            // ? read initial sort prefs for this mediaType, then load
            viewModelScope.launch {
                val initial = application.settingsFlow().first()
                val modeName = when (mediaType) {
                    1 /* IMAGE */ -> initial.imageSortMode
                    3 /* VIDEO */ -> initial.videoSortMode
                    else -> initial.sortMode
                }
                val orderName = when (mediaType) {
                    1 /* IMAGE */ -> initial.imageSortOrder
                    3 /* VIDEO */ -> initial.videoSortOrder
                    else -> initial.sortOrder
                }
                _state.value = _state.value.copy(
                    sortMode = try { SortMode.valueOf(modeName) } catch (_: IllegalArgumentException) { SortMode.DATE },
                    sortOrder = try { SortOrder.valueOf(orderName) } catch (_: IllegalArgumentException) { SortOrder.DESC }
                )
                loadMedia()
            }
            // ? respond to external sort changes (DataStore written by Settings screen)
            viewModelScope.launch {
                application.settingsFlow().drop(1).collect { settings ->
                    val newModeName = when (mediaType) {
                        1 /* IMAGE */ -> settings.imageSortMode
                        3 /* VIDEO */ -> settings.videoSortMode
                        else -> settings.sortMode
                    }
                    val newOrderName = when (mediaType) {
                        1 /* IMAGE */ -> settings.imageSortOrder
                        3 /* VIDEO */ -> settings.videoSortOrder
                        else -> settings.sortOrder
                    }
                    val newMode = try { SortMode.valueOf(newModeName) } catch (_: IllegalArgumentException) { SortMode.DATE }
                    val newOrder = try { SortOrder.valueOf(newOrderName) } catch (_: IllegalArgumentException) { SortOrder.DESC }
                    val s = _state.value
                    if (newMode != s.sortMode || newOrder != s.sortOrder) {
                        _state.value = s.copy(sortMode = newMode, sortOrder = newOrder)
                        loadMedia()
                    }
                }
            }
        } else {
            // no Application (test): default sort, load once
            viewModelScope.launch { loadMedia() }
        }
    }

    /** freeze all background work when leaving screen */
    fun stopAllWork() {
        mediaLoadJob?.cancel()
        isFrozen = true
    }

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(sortMode = mode)
        application?.let { app ->
            viewModelScope.launch { sortPrefs.saveMode(app, mode.name) }
        }
        loadMedia()
    }

    fun setSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(sortOrder = order)
        application?.let { app ->
            viewModelScope.launch { sortPrefs.saveOrder(app, order.name) }
        }
        loadMedia()
    }

    fun toggleSortOrder() {
        val newOrder = if (_state.value.sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
        _state.value = _state.value.copy(sortOrder = newOrder)
        application?.let { app ->
            viewModelScope.launch { sortPrefs.saveOrder(app, newOrder.name) }
        }
        loadMedia()
    }

    private fun loadMedia() {
        mediaLoadJob?.cancel()
        val generation = ++currentGeneration
        isFrozen = false
        val myGeneration = generation
        val mode = _state.value.sortMode
        val order = _state.value.sortOrder

        mediaLoadJob = viewModelScope.launch {
            try {
                var isFirstEmission = true
                val prevState = _state.value
                val prevUri = prevState.mediaItems.getOrNull(prevState.currentIndex)?.uri?.toString()

                repository.getMediaByFolder(
                    parentId = parentId,
                    sortMode = mode,
                    sortOrder = order,
                    mediaType = mediaType
                ).collect { items ->
                    if (generation != currentGeneration || isFrozen) return@collect
                    val folderName = items.firstOrNull()?.folderPath
                        ?.substringBeforeLast("/")?.substringAfterLast("/") ?: ""
                    val reconciledIndex = if (isFirstEmission) {
                        isFirstEmission = false
                        if (prevState.mediaItems.isEmpty()) {
                            prevState.currentIndex
                        } else {
                            val found = prevUri?.let { uri ->
                                items.indexOfFirst { it.uri?.toString() == uri }
                                    .takeIf { it >= 0 }
                            }
                            found ?: prevState.currentIndex
                                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
                        }
                    } else {
                        val curIndex = _state.value.currentIndex
                        val curUri = _state.value.mediaItems.getOrNull(curIndex)?.uri?.toString()
                        val found = curUri?.let { uri ->
                            items.indexOfFirst { it.uri?.toString() == uri }
                                .takeIf { it >= 0 }
                        }
                        found ?: curIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    }
                    _state.value = _state.value.copy(
                        mediaItems = items,
                        currentIndex = reconciledIndex,
                        folderName = folderName,
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "load failed")
                return@launch
            }

            val mgr = thumbnailManager ?: return@launch
            updateVideoThumbnails(myGeneration, mgr)
        }
    }

    private suspend fun updateVideoThumbnails(generation: Int, mgr: ThumbnailManager) {
        val backfill = backfillManager ?: return
        val items = _state.value.mediaItems
        backfill.backfill(items) { results ->
            if (generation != currentGeneration || isFrozen) return@backfill
            val updates = results.mapNotNull { (index, path) ->
                val item = items.getOrNull(index) ?: return@mapNotNull null
                ThumbnailUpdate(index, item, path)
            }
            if (updates.isNotEmpty()) {
                applyThumbnailUpdates(generation, updates)
            }
        }
    }

    private fun applyThumbnailUpdates(generation: Int, updates: List<ThumbnailUpdate>) {
        _state.update { current ->
            if (generation != currentGeneration || isFrozen) return@update current

            val updatedItems = current.mediaItems.toMutableList()
            var changed = false

            updates.forEach { update ->
                val currentItem = updatedItems.getOrNull(update.index)
                if (currentItem != null && currentItem.isSameIdentity(update.sourceItem)) {
                    updatedItems[update.index] = currentItem.copy(thumbnailPath = update.thumbnailPath)
                    changed = true
                }
            }

            if (changed) current.copy(mediaItems = updatedItems) else current
        }
    }

    private data class ThumbnailUpdate(
        val index: Int,
        val sourceItem: MediaItem,
        val thumbnailPath: String
    )


    private fun MediaItem.isSameIdentity(other: MediaItem): Boolean {
        return uri == other.uri &&
            folderPath == other.folderPath &&
            dateModified == other.dateModified &&
            size == other.size
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
        private val initialIndex: Int = 0,
        private val mediaType: Int? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                AndroidMediaRepository(application.contentResolver),
                parentId,
                initialIndex,
                application,
                mediaType
            ) as T
        }
    }
}
