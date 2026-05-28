package com.example.reader.ui.reader

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.AndroidMediaRepository
import com.example.reader.data.repository.MediaRepository
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.util.ThumbnailManager
import com.example.reader.util.saveSortMode
import com.example.reader.util.saveSortOrder
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

class ReaderViewModel(
    private val repository: MediaRepository,
    private val parentId: Long,
    private val initialIndex: Int = 0,
    private val application: Application? = null,
    private val mediaType: Int? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState(currentIndex = initialIndex))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    // ── Generation ID Ticket System ──
    private var mediaLoadJob: Job? = null
    @Volatile private var currentGeneration = 0
    @Volatile private var isFrozen = false

    private val thumbnailManager: ThumbnailManager? = application?.let { ThumbnailManager(it) }

    init {
        if (application != null) {
            // ① 读取初始排序偏好并首次加载
            viewModelScope.launch {
                val initial = application.settingsFlow().first()
                _state.value = _state.value.copy(
                    sortMode = try {
                        SortMode.valueOf(initial.sortMode)
                    } catch (_: IllegalArgumentException) { SortMode.DATE },
                    sortOrder = try {
                        SortOrder.valueOf(initial.sortOrder)
                    } catch (_: IllegalArgumentException) { SortOrder.DESC }
                )
                loadMedia()
            }
            // ② 响应外部排序变更（DataStore 被其他界面写入时自动同步）
            viewModelScope.launch {
                application.settingsFlow().drop(1).collect { settings ->
                    val newMode = try {
                        SortMode.valueOf(settings.sortMode)
                    } catch (_: IllegalArgumentException) { SortMode.DATE }
                    val newOrder = try {
                        SortOrder.valueOf(settings.sortOrder)
                    } catch (_: IllegalArgumentException) { SortOrder.DESC }
                    val s = _state.value
                    if (newMode != s.sortMode || newOrder != s.sortOrder) {
                        _state.value = s.copy(sortMode = newMode, sortOrder = newOrder)
                        loadMedia()
                    }
                }
            }
        } else {
            // 无 Application（测试）: 默认排序首次加载
            viewModelScope.launch { loadMedia() }
        }
    }

    /** 冻结所有后台工作，用于离开界面时防止僵尸协程。 */
    fun stopAllWork() {
        mediaLoadJob?.cancel()
        isFrozen = true
    }

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(sortMode = mode)
        application?.let { app ->
            viewModelScope.launch { app.saveSortMode(mode.name) }
        }
        loadMedia()
    }

    fun setSortOrder(order: SortOrder) {
        _state.value = _state.value.copy(sortOrder = order)
        application?.let { app ->
            viewModelScope.launch { app.saveSortOrder(order.name) }
        }
        loadMedia()
    }

    fun toggleSortOrder() {
        val newOrder = if (_state.value.sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
        _state.value = _state.value.copy(sortOrder = newOrder)
        application?.let { app ->
            viewModelScope.launch { app.saveSortOrder(newOrder.name) }
        }
        loadMedia()
    }

    private fun loadMedia() {
        // 取消前序任务，提升代际 ID，杀死所有过期协程
        mediaLoadJob?.cancel()
        isFrozen = false
        val myGeneration = ++currentGeneration

        mediaLoadJob = viewModelScope.launch {
            val prevState = _state.value
            val prevUri = prevState.mediaItems.getOrNull(prevState.currentIndex)?.uri?.toString()
            var isFirstEmission = true

            // ── Phase 1: 仓库 Flow 直通发射（带索引修正）──
            try {
                repository.getMediaByFolder(
                    parentId,
                    sortMode = prevState.sortMode,
                    sortOrder = prevState.sortOrder,
                    mediaType = mediaType
                ).collect { items ->
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
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "加载失败")
                return@launch
            }

            val mgr = thumbnailManager ?: return@launch
            updateVideoThumbnails(myGeneration, mgr)
        }
    }

    private suspend fun updateVideoThumbnails(generation: Int, mgr: ThumbnailManager) {
        val videoItems = _state.value.mediaItems
            .withIndex()
            .filter { (_, item) ->
                item.isVideo && item.thumbnailPath == null && item.folderPath.isNotBlank()
            }

        withContext(Dispatchers.IO) {
            videoItems.chunked(5).forEach { chunk ->
                if (generation != currentGeneration || isFrozen) return@withContext

                val generated = chunk.mapNotNull { (originalIndex, item) ->
                    val thumbFile = mgr.generateThumbnail(item.folderPath, item.dateModified, item.size)
                        ?: return@mapNotNull null
                    ThumbnailUpdate(originalIndex, item, thumbFile.absolutePath)
                }

                if (generated.isNotEmpty()) {
                    applyThumbnailUpdates(generation, generated)
                }
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
