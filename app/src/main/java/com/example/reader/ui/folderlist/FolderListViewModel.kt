package com.example.reader.ui.folderlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FolderListState(
    val folders: List<MediaFolder> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class FolderListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application.contentResolver)

    private val _state = MutableStateFlow(FolderListState())
    val state: StateFlow<FolderListState> = _state.asStateFlow()

    init {
        loadFolders()
    }

    fun loadFolders() {
        viewModelScope.launch {
            try {
                repository.getAllFolders().collect { folders ->
                    _state.value = FolderListState(folders = folders, isLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = FolderListState(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }
}
