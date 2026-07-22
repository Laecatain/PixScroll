package com.example.reader.ui.folderlist

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.reader.ReaderApp
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.MediaRepository
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
import com.example.reader.util.FolderCache
import com.example.reader.util.ThumbnailManager
import com.example.reader.util.saveFolderSortMode
import com.example.reader.util.saveFolderSortOrder
import com.example.reader.util.settingsFlow
import com.example.reader.util.FolderCoverStrategy
import com.example.reader.util.VideoCoverStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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
    var folderCoverStrategy by mutableStateOf(FolderCoverStrategy.LATEST)
        private set
    var videoCoverStrategy by mutableStateOf(VideoCoverStrategy.EXACT_1S)
        private set

    // 首次启动时跳过 Loading 直接显示缓存
    private var skipNextLoading = false

    private val _state: MutableStateFlow<FolderUiState>
    val state: StateFlow<FolderUiState>

    private val thumbnailManager = application?.let { ThumbnailManager(it) }
    private var loadJob: Job? = null


    val imageFolders: StateFlow<List<MediaFolder>>
    val videoFolders: StateFlow<List<MediaFolder>>

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

        imageFolders = _state.map { s ->
            if (s is FolderUiState.Success) s.folders.filter { it.hasImages } else emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        videoFolders = _state.map { s ->
            if (s is FolderUiState.Success) s.folders.filter { it.hasVideos } else emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        if (application != null) {
            // ① 读取首页排序偏好并首次加载
            viewModelScope.launch {
                val initial = application.settingsFlow().first()
                sortMode = try {
                    SortMode.valueOf(initial.folderSortMode)
                } catch (_: IllegalArgumentException) { SortMode.DATE }
                sortOrder = try {
                    SortOrder.valueOf(initial.folderSortOrder)
                } catch (_: IllegalArgumentException) { SortOrder.DESC }
                folderCoverStrategy = try {
                    FolderCoverStrategy.valueOf(initial.folderCoverStrategy)
                } catch (_: IllegalArgumentException) { FolderCoverStrategy.LATEST }
                videoCoverStrategy = try {
                    VideoCoverStrategy.valueOf(initial.videoCoverStrategy)
                } catch (_: IllegalArgumentException) { VideoCoverStrategy.EXACT_1S }
                loadFolders()
            }
            // ② 响应外部排序变更（设置页面写入时自动同步）
            viewModelScope.launch {
                application.settingsFlow().drop(1).collect { settings ->
                    val newMode = try {
                        SortMode.valueOf(settings.folderSortMode)
                    } catch (_: IllegalArgumentException) { SortMode.DATE }
                    val newOrder = try {
                        SortOrder.valueOf(settings.folderSortOrder)
                    } catch (_: IllegalArgumentException) { SortOrder.DESC }
                    val newCoverStrategy = try {
                        FolderCoverStrategy.valueOf(settings.folderCoverStrategy)
                    } catch (_: IllegalArgumentException) { FolderCoverStrategy.LATEST }
                    val newVideoStrategy = try {
                        VideoCoverStrategy.valueOf(settings.videoCoverStrategy)
                    } catch (_: IllegalArgumentException) { VideoCoverStrategy.EXACT_1S }
                    if (newMode != sortMode || newOrder != sortOrder || 
                        newCoverStrategy != folderCoverStrategy || newVideoStrategy != videoCoverStrategy) {
                        sortMode = newMode
                        sortOrder = newOrder
                        folderCoverStrategy = newCoverStrategy
                        videoCoverStrategy = newVideoStrategy
                        loadFolders()
                    }
                }
            }
            // ③ 响应 MediaStore 变化（新拍照、删除文件等），debounce 1s 合并连续信号
            viewModelScope.launch {
                repository.mediaStoreChanges
                    .debounce(1000)
                    .collect {
                        Log.d(TAG, "MediaStore changed, auto-refreshing folders")
                        loadFolders()
                    }
            }
        } else {
            // 无 Application（测试）: 默认排序首次加载
            loadFolders()
        }
    }

    fun updateSortMode(mode: SortMode) {
        sortMode = mode
        application?.let { app ->
            viewModelScope.launch { app.saveFolderSortMode(mode.name) }
        }
        loadFolders()
    }

    fun updateSortOrder(order: SortOrder) {
        sortOrder = order
        application?.let { app ->
            viewModelScope.launch { app.saveFolderSortOrder(order.name) }
        }
        loadFolders()
    }

    fun toggleSortOrder() {
        val newOrder = if (sortOrder == SortOrder.DESC) SortOrder.ASC else SortOrder.DESC
        sortOrder = newOrder
        application?.let { app ->
            viewModelScope.launch { app.saveFolderSortOrder(newOrder.name) }
        }
        loadFolders()
    }

    /** 重试加载（错误恢复） */
    fun retry() {
        skipNextLoading = false
        loadFolders()
    }

    private fun loadFolders() {
        loadJob?.cancel()
        if (!skipNextLoading && _state.value !is FolderUiState.Success) {
            _state.value = FolderUiState.Loading
        }
        skipNextLoading = false
        loadJob = viewModelScope.launch {
            try {
                repository.getAllFolders(sortMode = sortMode, sortOrder = sortOrder, folderCoverStrategy = folderCoverStrategy)
                    .onStart { Log.d(TAG, "Flow.onStart [thread=${Thread.currentThread().name}]") }
                    .onEach { folders ->
                        Log.d(TAG, "Flow.onEach: ${folders.size} 个文件夹 [thread=${Thread.currentThread().name}]")
                        _state.value = FolderUiState.Success(folders)
                        refreshCoverThumbnailCache(folders)
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

    private fun refreshCoverThumbnailCache(folders: List<MediaFolder>) {
        val app = application ?: return
        val videoFolders = folders.filter { folder ->
            folder.coverIsVideo &&
                folder.coverPath.isNotEmpty() &&
                folder.coverThumbnailPath == null
        }
        if (videoFolders.isEmpty()) return

        val videoFolderIds = videoFolders.map { it.id }.toSet()

        viewModelScope.launch {
            try {
                val thumbnailManager = ThumbnailManager(app)
                val updatedFolders = folders.map { folder ->
                    if (folder.id in videoFolderIds) {
                        val thumbnail = thumbnailManager.generateThumbnail(
                            folder.coverPath,
                            folder.coverDateModified,
                            folder.coverSize,
                            videoCoverStrategy
                        )
                        if (thumbnail != null) {
                            folder.copy(coverThumbnailPath = thumbnail.absolutePath)
                        } else {
                            folder
                        }
                    } else {
                        folder
                    }
                }
                if (updatedFolders != folders) {
                    val thumbnailsById = updatedFolders.associate { folder ->
                        folder.id to folder.coverThumbnailPath
                    }
                    _state.update { current ->
                        if (current is FolderUiState.Success) {
                            val currentFolders = current.folders.map { folder ->
                                val thumbnailPath = thumbnailsById[folder.id]
                                if (folder.coverThumbnailPath == null && thumbnailPath != null) {
                                    folder.copy(coverThumbnailPath = thumbnailPath)
                                } else {
                                    folder
                                }
                            }
                            FolderUiState.Success(currentFolders)
                        } else {
                            current
                        }
                    }
                    // Read updated state after atomic update
                    val s = _state.value
                    if (s is FolderUiState.Success) {
                        FolderCache.saveFolders(app.cacheDir, s.folders)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "缩略图缓存刷新失败: ${e.message}", e)
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderListViewModel(
                (application as ReaderApp).mediaRepository,
                application
            ) as T
        }
    }
}
