package com.example.reader.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.util.ThumbnailManager

@Composable
fun FolderGridCard(
    folder: MediaFolder,
    onClick: () -> Unit,
    thumbnailManager: ThumbnailManager? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f),
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (folder.coverIsVideo && folder.coverPath.isNotEmpty()) {
                    AsyncGridImage(
                        item = folder.toCoverMediaItem(),
                        contentDescription = folder.folderName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        thumbnailManager = thumbnailManager
                    )
                } else {
                    AsyncGridImage(
                        uri = folder.coverImageUri,
                        contentDescription = folder.folderName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = folder.folderName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.mediaCount} 个媒体",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun MediaFolder.toCoverMediaItem(): MediaItem {
    return MediaItem(
        uri = coverImageUri,
        name = folderName,
        mimeType = coverMimeType,
        size = coverSize,
        dateModified = coverDateModified,
        folderPath = coverPath,
        parentId = id,
        thumbnailPath = coverThumbnailPath
    )
}
