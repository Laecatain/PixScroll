package com.example.reader.ui.common

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** 网格/列表缩略图专用 AsyncImage 封装。.size(300) 避免解码全分辨率。 */
@Composable
fun AsyncGridImage(
    uri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(300)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}
