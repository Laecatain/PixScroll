package com.example.reader.util

import com.example.reader.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Phase 3 batch backfill: pre-generates video thumbnails on folder enter.
 * Runs with limitedParallelism(4) for faster backfill, caps at 50 videos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailBackfillManager(
    private val thumbnailManager: ThumbnailManager
) {
    companion object {
        private const val MAX_TOTAL_THUMBNAILS = 200
        private val backfillDispatcher = Dispatchers.IO.limitedParallelism(4)
    }

    /**
     * Backfill thumbnails for videos that don't have one yet.
     * Processes in chunks of 5, capped at MAX_TOTAL_THUMBNAILS.
     * Calls onBatchComplete for each chunk of results.
     */
    suspend fun backfill(
        allItems: List<MediaItem>,
        strategy: VideoCoverStrategy = VideoCoverStrategy.EXACT_1S,
        onBatchComplete: (Map<Int, String>) -> Unit
    ) {
        val videoItems = allItems.withIndex()
            .filter { (_, item) ->
                item.isVideo && item.thumbnailPath == null && item.folderPath.isNotBlank()
            }
            .take(MAX_TOTAL_THUMBNAILS)

        videoItems.chunked(5).forEach { chunk ->
            val results = chunk.mapNotNull { (index, item) ->
                val thumbFile = thumbnailManager.generateThumbnail(
                    item.folderPath, item.dateModified, item.size, strategy
                )
                thumbFile?.let { index to it.absolutePath }
            }
            if (results.isNotEmpty()) {
                onBatchComplete(results.toMap())
            }
        }
    }
}
