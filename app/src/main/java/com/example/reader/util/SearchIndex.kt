package com.example.reader.util

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.example.reader.data.model.MediaItem
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File

/**
 * Thread-safe in-memory search index for [MediaItem].
 *
 * Built during [getAllFolders] cursor traversal (single pass, piggybacking
 * on the unavoidable full MediaStore scan). Once built, [search] runs entirely
 * in memory — zero ContentResolver queries needed.
 *
 * Thread-safety: all reads go through a single `@Volatile` read of the
 * [entries] reference. The backing list is never mutated after [build],
 * so readers see a consistent snapshot without locking.
 */
class SearchIndex {

    companion object {
        private const val CACHE_FILE = "search_index.json"

        // MIME lookup table — index 0 reserved for unknown/empty
        private val MIME_TABLE = listOf(
            /*  0 */ "",
            /*  1 */ "image/jpeg",
            /*  2 */ "image/png",
            /*  3 */ "image/webp",
            /*  4 */ "image/gif",
            /*  5 */ "image/bmp",
            /*  6 */ "image/heic",
            /*  7 */ "image/avif",
            /*  8 */ "image/heif",
            /*  9 */ "image/tiff",
            /* 10 */ "video/mp4",
            /* 11 */ "video/x-matroska",
            /* 12 */ "video/webm",
            /* 13 */ "video/x-msvideo",
            /* 14 */ "video/quicktime",
        )

        private fun mimeCode(mime: String): Byte = when {
            mime.startsWith("image/jpeg") -> 1
            mime.startsWith("image/png") -> 2
            mime.startsWith("image/webp") -> 3
            mime.startsWith("image/gif") -> 4
            mime.startsWith("image/bmp") -> 5
            mime.startsWith("image/heic") -> 6
            mime.startsWith("image/avif") -> 7
            mime.startsWith("image/heif") -> 8
            mime.startsWith("image/tiff") || mime.startsWith("image/tif") -> 9
            mime.startsWith("video/mp4") -> 10
            mime.startsWith("video/x-matroska") || mime.startsWith("video/mkv") -> 11
            mime.startsWith("video/webm") -> 12
            mime.startsWith("video/x-msvideo") || mime.startsWith("video/avi") -> 13
            mime.startsWith("video/quicktime") || mime.startsWith("video/mov") -> 14
            else -> 0
        }

        private fun mimeFromCode(code: Byte): String =
            MIME_TABLE.getOrElse(code.toInt()) { "" }
    }

    // ── Public state ──

    @Volatile
    private var entries: List<SearchableMediaEntry> = emptyList()

    val isBuilt: Boolean get() = entries.isNotEmpty()
    val size: Int get() = entries.size

    // ── Build ──

    /**
     * Atomically swaps the entire index. Called once after a full MediaStore
     * scan completes. The list must be fully constructed (no mutability leaks).
     */
    fun build(newEntries: List<SearchableMediaEntry>) {
        entries = newEntries
    }

    /** Wipes the index. */
    fun clear() {
        entries = emptyList()
    }

    // ── Search (pure in-memory) ──

    /**
     * Searches filenames containing [query] (case-insensitive substring match).
     * Returns matching entries in insertion order.
     */
    fun search(query: String): List<SearchableMediaEntry> {
        val lowerQuery = query.lowercase()
        val snapshot = entries  // single @Volatile read
        if (lowerQuery.isEmpty() || snapshot.isEmpty()) return emptyList()
        return snapshot.filter { it.nameLower.contains(lowerQuery) }
    }

    // ── Conversion to MediaItem ──

    fun toMediaItem(
        entry: SearchableMediaEntry,
        unifiedUri: Uri
    ): MediaItem {
        val mime = mimeFromCode(entry.mimeTypeCode)
        val isVideo = mime.startsWith("video/")
        return MediaItem(
            uri = ContentUris.withAppendedId(unifiedUri, entry.id),
            name = entry.name,
            mimeType = mime,
            size = entry.size,
            dateModified = entry.dateModified,
            folderPath = entry.folderPath,
            parentId = entry.parentId,
            orientation = entry.orientation,
            mediaType = if (isVideo) MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            width = entry.width,
            height = entry.height
        )
    }

    fun toMediaItems(
        entries: List<SearchableMediaEntry>,
        unifiedUri: Uri
    ): List<MediaItem> =
        entries.map { toMediaItem(it, unifiedUri) }

    // ── Persistence ──

    /**
     * Saves the current index to disk as a compact JSON array-of-arrays.
     * Atomic write via temp-file-then-rename.
     */
    fun saveToDisk(cacheDir: File) {
        val snapshot = entries
        if (snapshot.isEmpty()) return
        try {
            val arr = JSONArray()
            for (e in snapshot) {
                arr.put(
                    JSONArray().apply {
                        put(e.id)
                        put(e.name)
                        put(e.parentId)
                        put(e.mimeTypeCode.toInt())
                        put(e.size)
                        put(e.dateModified)
                        put(e.folderPath)
                        put(e.orientation)
                        put(e.width)
                        put(e.height)
                    }
                )
            }
            val file = File(cacheDir, CACHE_FILE)
            val tmp = File(cacheDir, "$CACHE_FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (_: Exception) { }
    }

    /**
     * Loads a previously saved index from disk.
     * Returns true if data was loaded, false otherwise.
     */
    fun loadFromDisk(cacheDir: File): Boolean {
        return try {
            val file = File(cacheDir, CACHE_FILE)
            if (!file.exists()) return false
            val json = file.readText()
            val arr = JSONTokener(json).nextValue() as? JSONArray ?: return false
            val list = mutableListOf<SearchableMediaEntry>()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 10) continue
                list.add(
                    SearchableMediaEntry(
                        id = row.getLong(0),
                        name = row.optString(1, ""),
                        nameLower = row.optString(1, "").lowercase(),
                        parentId = row.getLong(2),
                        mimeTypeCode = row.optInt(3, 0).toByte(),
                        size = row.getLong(4),
                        dateModified = row.getLong(5),
                        folderPath = row.optString(6, ""),
                        orientation = row.optInt(7, 0),
                        width = row.optInt(8, 0),
                        height = row.optInt(9, 0)
                    )
                )
            }
            if (list.isNotEmpty()) {
                entries = list
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ── Builder (used during cursor traversal) ──

    class Builder {
        private val list = mutableListOf<SearchableMediaEntry>()

        // String interning — same folderPath string shared across entries
        private val folderPathPool = HashMap<String, String>()
        private val namePool = HashMap<String, String>()

        fun add(
            id: Long,
            name: String,
            parentId: Long,
            mimeType: String,
            size: Long,
            dateModified: Long,
            folderPath: String,
            orientation: Int,
            width: Int,
            height: Int
        ) {
            val internedName = namePool.getOrPut(name) { name }
            val internedPath = folderPathPool.getOrPut(folderPath) { folderPath }
            list.add(
                SearchableMediaEntry(
                    id = id,
                    name = internedName,
                    nameLower = internedName.lowercase(),
                    parentId = parentId,
                    mimeTypeCode = mimeCode(mimeType),
                    size = size,
                    dateModified = dateModified,
                    folderPath = internedPath,
                    orientation = orientation,
                    width = width,
                    height = height
                )
            )
        }

        fun build(): List<SearchableMediaEntry> = list.toList()

        fun size(): Int = list.size
    }
}

/**
 * Compact in-memory representation of a single media file.
 *
 * Strings are interned (via [SearchIndex.Builder]) so that identical
 * [folderPath] and [name] values share backing char arrays.
 *
 * [nameLower] is pre-computed at build time to avoid re-lowercasing
 * on every search.
 */
data class SearchableMediaEntry(
    val id: Long,
    val name: String,
    val nameLower: String,
    val parentId: Long,
    val mimeTypeCode: Byte,
    val size: Long,
    val dateModified: Long,
    val folderPath: String,
    val orientation: Int,
    val width: Int,
    val height: Int
)
