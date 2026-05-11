package com.example.reader.util

import android.net.Uri
import com.example.reader.data.model.MediaFolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FolderCache {

    fun saveFolders(cacheDir: File, folders: List<MediaFolder>) {
        try {
            val json = JSONArray()
            for (f in folders) {
                val obj = JSONObject()
                obj.put("id", f.id)
                obj.put("name", f.folderName)
                obj.put("path", f.folderPath)
                obj.put("coverUri", f.coverImageUri?.toString() ?: "")
                obj.put("count", f.mediaCount)
                obj.put("hasImages", f.hasImages)
                obj.put("hasVideos", f.hasVideos)
                json.put(obj)
            }
            File(cacheDir, "folders_cache.json").writeText(json.toString())
        } catch (_: Exception) { }
    }

    fun loadFolders(cacheDir: File): List<MediaFolder> {
        try {
            val file = File(cacheDir, "folders_cache.json")
            if (!file.exists()) return emptyList()
            val json = JSONArray(file.readText())
            val result = mutableListOf<MediaFolder>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val uriStr = obj.optString("coverUri", "")
                result.add(
                    MediaFolder(
                        id = obj.getLong("id"),
                        folderName = obj.getString("name"),
                        folderPath = obj.getString("path"),
                        coverImageUri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null,
                        mediaCount = obj.getInt("count"),
                        hasImages = obj.optBoolean("hasImages", true),
                        hasVideos = obj.optBoolean("hasVideos", true)
                    )
                )
            }
            return result
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun saveHiddenParents(cacheDir: File, parents: Set<Long>) {
        try {
            val json = JSONArray()
            for (p in parents) json.put(p)
            File(cacheDir, "hidden_cache.json").writeText(json.toString())
        } catch (_: Exception) { }
    }

    fun loadHiddenParents(cacheDir: File): Set<Long> {
        try {
            val file = File(cacheDir, "hidden_cache.json")
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
}
