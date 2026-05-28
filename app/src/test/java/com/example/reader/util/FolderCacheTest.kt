package com.example.reader.util

import com.example.reader.data.model.MediaFolder
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderCacheTest {

    @Test
    fun `save and load preserves cover metadata`() {
        val cacheDir = Files.createTempDirectory("folder-cache-test").toFile()
        val folder = MediaFolder(
            id = 7L,
            folderName = "Videos",
            folderPath = "/storage/emulated/0/Movies",
            coverImageUri = null,
            mediaCount = 3,
            hasImages = false,
            hasVideos = true,
            coverMimeType = "video/mp4",
            coverPath = "/storage/emulated/0/Movies/clip.mp4",
            coverDateModified = 1234L,
            coverSize = 5678L,
            coverThumbnailPath = "/cache/thumbnails/clip.jpg"
        )

        FolderCache.saveFolders(cacheDir, listOf(folder))

        val loaded = FolderCache.loadFolders(cacheDir).single()
        assertEquals(folder.id, loaded.id)
        assertNull(loaded.coverImageUri)
        assertEquals("video/mp4", loaded.coverMimeType)
        assertEquals("/storage/emulated/0/Movies/clip.mp4", loaded.coverPath)
        assertEquals(1234L, loaded.coverDateModified)
        assertEquals(5678L, loaded.coverSize)
        assertEquals("/cache/thumbnails/clip.jpg", loaded.coverThumbnailPath)
        assertTrue(loaded.coverIsVideo)
    }

    @Test
    fun `save and load preserves escaped folder strings`() {
        val cacheDir = Files.createTempDirectory("folder-cache-test").toFile()
        val folder = MediaFolder(
            id = 8L,
            folderName = "Quotes \" and slash \\",
            folderPath = "/storage/emulated/0/Line\nBreak",
            coverImageUri = null,
            mediaCount = 1,
            coverMimeType = "image/jpeg",
            coverPath = "/storage/emulated/0/Line\nBreak/cover \"one\".jpg",
            coverThumbnailPath = "/cache/thumb\\cover.jpg"
        )

        FolderCache.saveFolders(cacheDir, listOf(folder))

        val loaded = FolderCache.loadFolders(cacheDir).single()
        assertEquals(folder.folderName, loaded.folderName)
        assertEquals(folder.folderPath, loaded.folderPath)
        assertEquals(folder.coverPath, loaded.coverPath)
        assertEquals(folder.coverThumbnailPath, loaded.coverThumbnailPath)
        assertFalse(loaded.coverIsVideo)
    }

    @Test
    fun `load old cache defaults cover metadata`() {
        val cacheDir = Files.createTempDirectory("folder-cache-test").toFile()
        File(cacheDir, "folders_cache.json").writeText(
            """
            [
              {
                "id": 1,
                "name": "Camera",
                "path": "/storage/emulated/0/DCIM/Camera",
                "coverUri": "",
                "count": 5,
                "hasImages": true,
                "hasVideos": false
              }
            ]
            """.trimIndent()
        )

        val loaded = FolderCache.loadFolders(cacheDir).single()

        assertEquals(1L, loaded.id)
        assertEquals("Camera", loaded.folderName)
        assertNull(loaded.coverImageUri)
        assertEquals("", loaded.coverMimeType)
        assertEquals("", loaded.coverPath)
        assertEquals(0L, loaded.coverDateModified)
        assertEquals(0L, loaded.coverSize)
        assertNull(loaded.coverThumbnailPath)
        assertFalse(loaded.coverIsVideo)
    }
}
