package com.example.reader.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 媒体文件尺寸缓存（URI → width/height），JSON 文件持久化，纯内存读取。 */
object MediaDimensionsCache {

    private const val FILE_NAME = "media_dim_cache.json"

    fun load(cacheDir: File): MutableMap<String, DimensionRecord> {
        val file = File(cacheDir, FILE_NAME)
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONArray(file.readText())
            val map = mutableMapOf<String, DimensionRecord>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                map[obj.getString("u")] = DimensionRecord(
                    width = obj.getInt("w"),
                    height = obj.getInt("h")
                )
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun save(cacheDir: File, records: Map<String, DimensionRecord>) {
        try {
            val json = JSONArray()
            for ((uri, record) in records) {
                json.put(JSONObject().apply {
                    put("u", uri)
                    put("w", record.width)
                    put("h", record.height)
                })
            }
            File(cacheDir, FILE_NAME).writeText(json.toString())
        } catch (_: Exception) { }
    }
}

data class DimensionRecord(val width: Int, val height: Int)
