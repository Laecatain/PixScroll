package com.example.reader.ui.folderlist

import com.example.reader.data.model.MediaFolder

/** 文件夹列表 UI 状态机。缓存命中时直接进入 Success，不经过 Loading。 */
sealed interface FolderUiState {
    data class Success(val folders: List<MediaFolder>) : FolderUiState
    data object Loading : FolderUiState
    data class Error(val message: String) : FolderUiState
}
