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
import com.example.reader.util.saveSortMode
import com.example.reader.util.saveSortOrder
import com.example.reader.util.settingsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
    private val application: Application? = null,
    private val mediaType: Int? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState(currentIndex = initialIndex))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

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
        viewModelScope.launch {
            val prevState = _state.value
            val prevUri = prevState.mediaItems.getOrNull(prevState.currentIndex)?.uri?.toString()
            var isFirstEmission = true
            try {
                repository.getMediaByFolder(
                    parentId,
                    sortMode = prevState.sortMode,
                    sortOrder = prevState.sortOrder,
                    mediaType = mediaType
                ).collect { items ->
                    val folderName = items.firstOrNull()?.folderPath
                        ?.substringBeforeLast("/")?.substringAfterLast("/") ?: ""
                    // 索引修正: 首次发射用 prevState 判断首次加载，
                    // 后续发射（Phase 2 合并）从当前状态修正，避免覆盖用户已滚动的位置
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
