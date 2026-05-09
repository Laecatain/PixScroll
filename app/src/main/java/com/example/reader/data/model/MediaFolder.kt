package com.example.reader.data.model

import android.net.Uri

data class MediaFolder(
    val folderName: String,
    val folderPath: String,
    val coverImageUri: Uri?,
    val mediaCount: Int
)
