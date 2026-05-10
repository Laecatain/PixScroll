package com.example.reader.ui.folderlist

import android.app.Application
import android.util.Log
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
import com.example.reader.util.FolderCache
import com.example.reader.util.PreferenceKeys
import com.example.reader.util.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

private const val TAG = "FolderListVM"

class FolderListViewModel(
    private val repository: MediaRepository,
    private val application: Application? = null
) : ViewModel() {

    var sortMode by mutableStateOf(SortMode.DATE)
        private set
    var sortOrder by mutableStateOf(SortOrder.DESC)
        private set

    // 首次启动时跳过 Loading 直接显示缓存
    private var skipNextLoading = false

    private val _state: MutableStateFlow<FolderUiState>
    val state: StateFlow<FolderUiState>

    init {
        // Cache-First: 同步读取缓存作为 StateFlow 初始值
        val cached = application?.cacheDir?.let { FolderCache.loadFolders(it) }
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "缓存命中: ${cached.size} 个文件夹")
            skipNextLoading = true
            _state = MutableStateFlow(FolderUiState.Success(cached))
        } else {
            _state = MutableStateFlow(FolderUiState.Loading)
        }
        state = _state.asStateFlow()

        // 异步读取排序偏好
        if (application != null) {
            viewModelScope.launch {
                val prefs = application.dataStore.data.first()
                sortMode = try {
                    SortMode.valueOf(prefs[PreferenceKeys.SORT_MODE] ?: "DATE")
                } catch (_: IllegalArgumentException) { SortMode.DATE }
                sortOrder = try {
                    SortOrder.valueOf(prefs[PreferenceKeys.SORT_ORDER] ?: "DESC")
                } catch (_: IllegalArgumentException) { SortOrder.DESC }
            }
        }

        // 刷新数据（首次跳 Loading 以复用缓存显示）
        loadFolders()
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
        if (!skipNextLoading) {
            _state.value = FolderUiState.Loading
        }
        skipNextLoading = false
        viewModelScope.launch {
            try {
                repository.getAllFolders(sortMode = sortMode, sortOrder = sortOrder)
                    .onStart { Log.d(TAG, "Flow.onStart [thread=${Thread.currentThread().name}]") }
                    .onEach { folders ->
                        Log.d(TAG, "Flow.onEach: ${folders.size} 个文件夹 [thread=${Thread.currentThread().name}]")
                        _state.value = FolderUiState.Success(folders)
                    }
                    .onCompletion { cause ->
                        Log.d(TAG, "Flow.onCompletion [thread=${Thread.currentThread().name}] cause=$cause")
                    }
                    .collect { /* onEach 已处理 */ }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Flow 异常: ${e.message}", e)
                _state.value = FolderUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderListViewModel(
                AndroidMediaRepository(application.contentResolver, application.cacheDir),
                application
            ) as T
        }
    }
}
