package com.example.reader.data.repository

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeMediaRepository : MediaRepository {

    var folders: List<MediaFolder> = emptyList()
    var mediaItems: Map<Long, List<MediaItem>> = emptyMap()
    var searchResults: List<MediaItem> = emptyList()
    var foldersError: Throwable? = null
    var mediaError: Throwable? = null
    var searchError: Throwable? = null

    override fun getAllFolders(
        sortMode: SortMode,
        sortOrder: SortOrder,
        includeHidden: Boolean
    ): Flow<List<MediaFolder>> {
        foldersError?.let { throw it }
        return flowOf(folders)
    }

    override fun getMediaByFolder(
        parentId: Long,
        sortMode: SortMode,
        sortOrder: SortOrder
    ): Flow<List<MediaItem>> {
        mediaError?.let { throw it }
        return flowOf(mediaItems[parentId] ?: emptyList())
    }

    override fun searchMedia(query: String): Flow<List<MediaItem>> {
        searchError?.let { throw it }
        return flowOf(searchResults)
    }
}
