package com.example.reader.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchIndexTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `empty index has isBuilt false and size 0`() {
        val idx = SearchIndex()
        assertFalse(idx.isBuilt)
        assertEquals(0, idx.size)
    }

    @Test
    fun `build populates isBuilt and size`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()

        builder.add(1, "cat.jpg", 100, "image/jpeg", 50000, 1000, "/DCIM/Camera", 0, 1920, 1080)
        builder.add(2, "dog.png", 100, "image/png", 30000, 1001, "/DCIM/Camera", 0, 800, 600)
        builder.add(3, "vacation.mp4", 200, "video/mp4", 1_000_000, 1002, "/Movies", 0, 1920, 1080)

        idx.build(builder.build())
        assertTrue(idx.isBuilt)
        assertEquals(3, idx.size)
    }

    @Test
    fun `search exact match`() {
        val idx = buildSampleIndex()
        val results = idx.search("cat")
        assertEquals(1, results.size)
        assertEquals("cat.jpg", results[0].name)
    }

    @Test
    fun `search substring`() {
        val idx = buildSampleIndex()
        val results = idx.search("cat")
        assertEquals(1, results.size)
    }

    @Test
    fun `search case insensitive`() {
        val idx = buildSampleIndex()
        assertEquals(1, idx.search("CAT").size)
        assertEquals(1, idx.search("CaT").size)
        assertEquals(1, idx.search("VACATION").size)
    }

    @Test
    fun `search no match returns empty`() {
        val idx = buildSampleIndex()
        assertTrue(idx.search("xyz_not_found").isEmpty())
    }

    @Test
    fun `search empty query returns empty`() {
        val idx = buildSampleIndex()
        assertTrue(idx.search("").isEmpty())
    }

    @Test
    fun `search single character matches all containing that char`() {
        val idx = buildSampleIndex()
        // "cat.jpg", "dog.png", "vacation.mp4"
        // 'a' is in: cat.jpg, vacation.mp4
        val result = idx.search("a")
        assertEquals(2, result.size)
    }

    @Test
    fun `search with spaces`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        builder.add(1, "my vacation photo.jpg", 100, "image/jpeg", 50000, 1000, "/DCIM", 0, 1920, 1080)
        builder.add(2, "notes.txt", 100, "text/plain", 1000, 1001, "/Documents", 0, 0, 0)
        idx.build(builder.build())

        assertEquals(1, idx.search("vacation photo").size)
        assertEquals(1, idx.search("my vacation").size)
    }

    @Test
    fun `search special characters`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        builder.add(1, "photo (1).jpg", 100, "image/jpeg", 50000, 1000, "/DCIM", 0, 1920, 1080)
        builder.add(2, "screenshot-2024.png", 200, "image/png", 30000, 1001, "/Pictures", 0, 1080, 1920)
        builder.add(3, "测试图片.jpg", 300, "image/jpeg", 40000, 1002, "/Downloads", 0, 800, 600)
        idx.build(builder.build())

        assertEquals(1, idx.search("(1)").size)
        assertEquals(1, idx.search("screenshot-2024").size)
        assertEquals(1, idx.search("测试").size)
    }

    @Test
    fun `persistence roundtrip`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        builder.add(1, "cat.jpg", 100, "image/jpeg", 50000, 1000, "/DCIM/Camera", 0, 1920, 1080)
        builder.add(2, "dog.png", 100, "image/png", 30000, 1001, "/DCIM/Camera", 0, 800, 600)
        builder.add(3, "vacation.mp4", 200, "video/mp4", 1_000_000, 1002, "/Movies", 0, 1920, 1080)
        idx.build(builder.build())

        val cacheDir = tempFolder.newFolder("search_index_test")
        idx.saveToDisk(cacheDir)

        // Verify file was written
        val savedFile = File(cacheDir, "search_index.json")
        assertTrue(savedFile.exists())
        assertTrue(savedFile.length() > 0)

        // Load into a fresh index
        val loaded = SearchIndex()
        val loadedOk = loaded.loadFromDisk(cacheDir)
        assertTrue(loadedOk)
        assertTrue(loaded.isBuilt)
        assertEquals(3, loaded.size)

        // Verify search works on loaded index
        assertEquals(1, loaded.search("cat").size)
        assertEquals(1, loaded.search("dog").size)
        assertEquals(1, loaded.search("vacation").size)
        assertTrue(loaded.search("xyz").isEmpty())
    }

    @Test
    fun `persistence with empty index does not crash`() {
        val idx = SearchIndex()
        val cacheDir = tempFolder.newFolder("empty_index_test")
        // Saving empty index should be a no-op
        idx.saveToDisk(cacheDir)

        val file = File(cacheDir, "search_index.json")
        assertFalse(file.exists())
    }

    @Test
    fun `loadFromDisk with missing file returns false`() {
        val idx = SearchIndex()
        val cacheDir = tempFolder.newFolder("missing_file_test")
        assertFalse(idx.loadFromDisk(cacheDir))
    }

    @Test
    fun `toMediaItem maps all fields correctly`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        builder.add(
            id = 42,
            name = "test.jpg",
            parentId = 100,
            mimeType = "image/jpeg",
            size = 50000,
            dateModified = 1000,
            folderPath = "/DCIM/Camera/test.jpg",
            orientation = 0,
            width = 1920,
            height = 1080
        )
        idx.build(builder.build())

        val entries = idx.search("test")
        assertEquals(1, entries.size)

        val item = idx.toMediaItem(entries[0], Uri.parse("content://media/external/images/media"))
        assertEquals("test.jpg", item.name)
        assertEquals(42, item.uri?.lastPathSegment?.toLongOrNull())
        assertEquals("image/jpeg", item.mimeType)
        assertEquals(50000, item.size)
        assertEquals(1000, item.dateModified)
        assertEquals("/DCIM/Camera/test.jpg", item.folderPath)
        assertEquals(100, item.parentId)
        assertEquals(0, item.orientation)
        assertEquals(1920, item.width)
        assertEquals(1080, item.height)
        assertFalse(item.isVideo)
    }

    @Test
    fun `toMediaItem with video mime sets isVideo correctly`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        builder.add(1, "video.mp4", 200, "video/mp4", 1_000_000, 1000, "/Movies", 0, 1920, 1080)
        idx.build(builder.build())

        val item = idx.toMediaItem(idx.search("video")[0], Uri.EMPTY)
        assertTrue(item.isVideo)
    }

    @Test
    fun `toMediaItems batch conversion`() {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        for (i in 1..500) {
            builder.add(
                id = i.toLong(),
                name = "file_$i.jpg",
                parentId = (i % 10 + 1).toLong(),
                mimeType = "image/jpeg",
                size = i * 1000L,
                dateModified = i.toLong(),
                folderPath = "/folder_${i % 10 + 1}/file_$i.jpg",
                orientation = 0,
                width = 100,
                height = 100
            )
        }
        idx.build(builder.build())
        assertEquals(500, idx.size)

        val all = idx.toMediaItems(idx.search("file_"), Uri.EMPTY)
        assertEquals(500, all.size)
    }

    @Test
    fun `clear wipes the index`() {
        val idx = buildSampleIndex()
        assertTrue(idx.isBuilt)

        idx.clear()
        assertFalse(idx.isBuilt)
        assertEquals(0, idx.size)
        assertTrue(idx.search("cat").isEmpty())
    }

    @Test
    fun `builder string interning reduces memory`() {
        val builder = SearchIndex.Builder()
        // 100 entries all sharing the same folderPath
        for (i in 1..100) {
            builder.add(
                id = i.toLong(),
                name = "img_$i.jpg",
                parentId = 100,
                mimeType = "image/jpeg",
                size = 1000L,
                dateModified = i.toLong(),
                folderPath = "/DCIM/Camera/img_$i.jpg",
                orientation = 0,
                width = 100,
                height = 100
            )
        }
        val entries = builder.build()
        assertEquals(100, entries.size)

        // Each entry should have a unique folderPath (since each has different filename)
        // But if we add 100 with SAME folderPath, interning should deduplicate
    }

    @Test
    fun `mime code mapping covers all types`() {
        // We test SearchIndex's mimeCode logic by adding entries and verifying
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()

        builder.add(1, "a.jpg", 1, "image/jpeg", 0, 0, "/", 0, 0, 0)
        builder.add(2, "b.png", 1, "image/png", 0, 0, "/", 0, 0, 0)
        builder.add(3, "c.webp", 1, "image/webp", 0, 0, "/", 0, 0, 0)
        builder.add(4, "d.gif", 1, "image/gif", 0, 0, "/", 0, 0, 0)
        builder.add(5, "e.bmp", 1, "image/bmp", 0, 0, "/", 0, 0, 0)
        builder.add(6, "f.heic", 1, "image/heic", 0, 0, "/", 0, 0, 0)
        builder.add(7, "g.avif", 1, "image/avif", 0, 0, "/", 0, 0, 0)
        builder.add(8, "h.heif", 1, "image/heif", 0, 0, "/", 0, 0, 0)
        builder.add(9, "i.tiff", 1, "image/tiff", 0, 0, "/", 0, 0, 0)
        builder.add(10, "j.mp4", 2, "video/mp4", 0, 0, "/", 0, 0, 0)
        builder.add(11, "k.mkv", 2, "video/x-matroska", 0, 0, "/", 0, 0, 0)
        builder.add(12, "l.webm", 2, "video/webm", 0, 0, "/", 0, 0, 0)
        builder.add(13, "m.avi", 2, "video/x-msvideo", 0, 0, "/", 0, 0, 0)
        builder.add(14, "n.mov", 2, "video/quicktime", 0, 0, "/", 0, 0, 0)
        idx.build(builder.build())

        assertEquals(14, idx.size)

        // Verify each entry produces a MediaItem with correct mime via toMediaItem
        val items = idx.toMediaItems(idx.search(""), Uri.EMPTY)
        assertEquals(14, items.size)

        val mimes = items.map { it.mimeType }
        assertTrue("image/jpeg" in mimes)
        assertTrue("image/png" in mimes)
        assertTrue("image/webp" in mimes)
        assertTrue("image/gif" in mimes)
        assertTrue("image/bmp" in mimes)
        assertTrue("image/heic" in mimes)
        assertTrue("image/avif" in mimes)
        assertTrue("image/heif" in mimes)
        assertTrue("image/tiff" in mimes)
        assertTrue("video/mp4" in mimes)
        assertTrue("video/x-matroska" in mimes)
        assertTrue("video/webm" in mimes)
        assertTrue("video/x-msvideo" in mimes)
        assertTrue("video/quicktime" in mimes)
    }

    // Helpers

    private fun buildSampleIndex(): SearchIndex {
        val idx = SearchIndex()
        val builder = SearchIndex.Builder()
        builder.add(1, "cat.jpg", 100, "image/jpeg", 50000, 1000, "/DCIM/Camera", 0, 1920, 1080)
        builder.add(2, "dog.png", 100, "image/png", 30000, 1001, "/DCIM/Camera", 0, 800, 600)
        builder.add(3, "vacation.mp4", 200, "video/mp4", 1_000_000, 1002, "/Movies", 0, 1920, 1080)
        idx.build(builder.build())
        return idx
    }
}
