package com.example.reader.util

import android.net.Uri
import com.example.reader.data.model.MediaFolder
import org.json.JSONArray
import java.io.File

object FolderCache {

    private const val FOLDERS_CACHE_FILE = "folders_cache.json"
    private const val HIDDEN_CACHE_FILE = "hidden_cache.json"

    fun saveFolders(cacheDir: File, folders: List<MediaFolder>) {
        try {
            File(cacheDir, FOLDERS_CACHE_FILE).writeText(encodeFolders(folders))
        } catch (_: Exception) { }
    }

    fun loadFolders(cacheDir: File): List<MediaFolder> {
        return try {
            val file = File(cacheDir, FOLDERS_CACHE_FILE)
            if (!file.exists()) return emptyList()
            decodeFolders(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHiddenParents(cacheDir: File, parents: Set<Long>) {
        try {
            val json = JSONArray()
            for (p in parents) json.put(p)
            File(cacheDir, HIDDEN_CACHE_FILE).writeText(json.toString())
        } catch (_: Exception) { }
    }

    fun loadHiddenParents(cacheDir: File): Set<Long> {
        try {
            val file = File(cacheDir, HIDDEN_CACHE_FILE)
            if (!file.exists()) return emptySet()
            val json = JSONArray(file.readText())
            val result = mutableSetOf<Long>()
            for (i in 0 until json.length()) {
                result.add(json.getLong(i))
            }
            return result
        } catch (_: Exception) {
            return emptySet()
        }
    }

    private fun encodeFolders(folders: List<MediaFolder>): String {
        return folders.joinToString(prefix = "[", postfix = "]") { folder ->
            listOf(
                jsonNumber("id", folder.id),
                jsonString("name", folder.folderName),
                jsonString("path", folder.folderPath),
                jsonString("coverUri", folder.coverImageUri?.toString() ?: ""),
                jsonNumber("count", folder.mediaCount),
                jsonBoolean("hasImages", folder.hasImages),
                jsonBoolean("hasVideos", folder.hasVideos),
                jsonString("coverMimeType", folder.coverMimeType),
                jsonString("coverPath", folder.coverPath),
                jsonNumber("coverDateModified", folder.coverDateModified),
                jsonNumber("coverSize", folder.coverSize),
                jsonString("coverThumbnailPath", folder.coverThumbnailPath ?: "")
            ).joinToString(prefix = "{", postfix = "}")
        }
    }

    private fun decodeFolders(json: String): List<MediaFolder> {
        return parseJsonObjectArray(json).mapNotNull { obj ->
            val id = obj["id"]?.toLongOrNull() ?: return@mapNotNull null
            val name = obj["name"] ?: return@mapNotNull null
            val path = obj["path"] ?: ""
            val uriStr = obj["coverUri"] ?: ""
            val thumbnailPath = obj["coverThumbnailPath"] ?: ""
            MediaFolder(
                id = id,
                folderName = name,
                folderPath = path,
                coverImageUri = parseUriOrNull(uriStr),
                mediaCount = obj["count"]?.toIntOrNull() ?: 0,
                hasImages = parseBoolean(obj["hasImages"], defaultValue = true),
                hasVideos = parseBoolean(obj["hasVideos"], defaultValue = true),
                coverMimeType = obj["coverMimeType"] ?: "",
                coverPath = obj["coverPath"] ?: "",
                coverDateModified = obj["coverDateModified"]?.toLongOrNull() ?: 0L,
                coverSize = obj["coverSize"]?.toLongOrNull() ?: 0L,
                coverThumbnailPath = thumbnailPath.takeIf { it.isNotEmpty() }
            )
        }
    }

    private fun jsonString(name: String, value: String): String {
        return "\"$name\":\"${escapeJson(value)}\""
    }

    private fun jsonNumber(name: String, value: Long): String {
        return "\"$name\":$value"
    }

    private fun jsonNumber(name: String, value: Int): String {
        return "\"$name\":$value"
    }

    private fun jsonBoolean(name: String, value: Boolean): String {
        return "\"$name\":$value"
    }

    private fun escapeJson(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun parseUriOrNull(value: String): Uri? {
        if (value.isEmpty()) return null
        return try {
            Uri.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBoolean(value: String?, defaultValue: Boolean): Boolean {
        return when (value?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> defaultValue
        }
    }

    private fun parseJsonObjectArray(json: String): List<Map<String, String>> {
        val objects = mutableListOf<Map<String, String>>()
        var index = skipWhitespace(json, 0)
        if (index >= json.length || json[index] != '[') return emptyList()
        index++

        while (index < json.length) {
            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return objects
                json[index] == ']' -> return objects
                json[index] == ',' -> index++
                json[index] == '{' -> {
                    val (obj, nextIndex) = parseJsonObject(json, index)
                    objects.add(obj)
                    index = nextIndex
                }
                else -> return objects
            }
        }
        return objects
    }

    private fun parseJsonObject(json: String, startIndex: Int): Pair<Map<String, String>, Int> {
        val values = linkedMapOf<String, String>()
        var index = startIndex + 1

        while (index < json.length) {
            index = skipWhitespace(json, index)
            when {
                index >= json.length -> return values to index
                json[index] == '}' -> return values to index + 1
                json[index] == ',' -> {
                    index++
                }
                json[index] == '"' -> {
                    val (key, afterKey) = parseJsonString(json, index)
                    index = skipWhitespace(json, afterKey)
                    if (index >= json.length || json[index] != ':') return values to index
                    index = skipWhitespace(json, index + 1)
                    val (value, afterValue) = parseJsonValue(json, index)
                    values[key] = value
                    index = afterValue
                }
                else -> return values to index
            }
        }
        return values to index
    }

    private fun parseJsonValue(json: String, startIndex: Int): Pair<String, Int> {
        if (startIndex >= json.length) return "" to startIndex
        if (json[startIndex] == '"') return parseJsonString(json, startIndex)

        var index = startIndex
        while (index < json.length && json[index] != ',' && json[index] != '}') {
            index++
        }
        return json.substring(startIndex, index).trim() to index
    }

    private fun parseJsonString(json: String, startIndex: Int): Pair<String, Int> {
        val result = StringBuilder()
        var index = startIndex + 1

        while (index < json.length) {
            val char = json[index]
            when {
                char == '"' -> return result.toString() to index + 1
                char == '\\' && index + 1 < json.length -> {
                    val escaped = json[index + 1]
                    when (escaped) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        '/' -> result.append('/')
                        'b' -> result.append('\b')
                        'f' -> result.append('')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hexStart = index + 2
                            val hexEnd = hexStart + 4
                            if (hexEnd <= json.length) {
                                json.substring(hexStart, hexEnd).toIntOrNull(16)?.let { code ->
                                    result.append(code.toChar())
                                    index += 4
                                }
                            }
                        }
                        else -> result.append(escaped)
                    }
                    index += 2
                }
                else -> {
                    result.append(char)
                    index++
                }
            }
        }
        return result.toString() to index
    }

    private fun skipWhitespace(value: String, startIndex: Int): Int {
        var index = startIndex
        while (index < value.length && value[index].isWhitespace()) {
            index++
        }
        return index
    }
}
