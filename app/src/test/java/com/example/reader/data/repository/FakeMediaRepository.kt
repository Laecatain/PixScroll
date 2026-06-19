package com.example.reader.data.repository

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf

class FakeMediaRepository : MediaRepository {

    private val _mediaStoreChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val mediaStoreChanges: SharedFlow<Unit> = _mediaStoreChanges
    fun emitMediaStoreChange() { _mediaStoreChanges.tryEmit(Unit) }

    var folders: List<MediaFolder> = emptyList()
    var mediaItems: Map<Long, List<MediaItem>> = emptyMap()
    var searchResults: List<MediaItem> = emptyList()
    var searchFolderResults: List<MediaFolder> = emptyList()
    var foldersError: Throwable? = null
    var mediaError: Throwable? = null
    var searchError: Throwable? = null
    var folderSearchError: Throwable? = null

    var searchMediaCallCount = 0
    var searchFoldersCallCount = 0
    var lastSearchMediaQuery: String? = null
    var lastSearchFoldersQuery: String? = null

    override fun getAllFolders(
        sortMode: SortMode,
        sortOrder: SortOrder,
        includeHidden: Boolean
    ): Flow<List<MediaFolder>> {
        foldersError?.let { throw it }
        val sorted = when (sortMode) {
            SortMode.NAME -> folders.sortedBy { it.folderName.lowercase() }
            SortMode.DATE -> folders.sortedByDescending { it.coverDateModified }
            SortMode.SIZE -> folders.sortedByDescending { it.mediaCount }
        }.let { if (sortOrder == SortOrder.ASC) it.reversed() else it }
        return flowOf(sorted)
    }

    override fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder,
        mediaType: Int?
    ): Flow<List<MediaItem>> {
        mediaError?.let { throw it }
        var items = mediaItems[parentId] ?: emptyList()
        if (mediaType != null) {
            items = items.filter { it.mediaType == mediaType }
        }
        val sorted = when (sortMode) {
            SortMode.NAME -> items.sortedBy { it.name.lowercase() }
            SortMode.DATE -> items.sortedByDescending { it.dateModified }
            SortMode.SIZE -> items.sortedByDescending { it.size }
        }.let { if (sortOrder == SortOrder.ASC) it.reversed() else it }
        return flowOf(sorted)
    }

    override fun searchMedia(query: String): Flow<List<MediaItem>> {
        searchMediaCallCount++
        lastSearchMediaQuery = query
        searchError?.let { throw it }
        return flowOf(searchResults)
    }

    override fun searchFolders(query: String): Flow<List<MediaFolder>> {
        searchFoldersCallCount++
        lastSearchFoldersQuery = query
        folderSearchError?.let { throw it }
        return flowOf(searchFolderResults)
    }
}
