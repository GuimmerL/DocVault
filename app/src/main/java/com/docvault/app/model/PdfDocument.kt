package com.docvault.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PdfDocument(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var filename: String, // Exact original filename preserved
    var relativePath: String, // e.g. "Manuals/PNOZ_s4.pdf"
    var categoryId: String,
    var categoryName: String,
    var fileSize: Long,
    val dateAdded: Long = System.currentTimeMillis(),
    var pageCount: Int = 0,
    var lastIndexedModified: Long = 0L,
    var sourceUrl: String = "",
    var tags: MutableList<String> = mutableListOf(), // e.g. ["PNOZ", "1002158", "24V"]
    var indexedText: String = ""
) {
    val formattedFileSize: String
        get() {
            if (fileSize <= 0) return "0 B"
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$fileSize B"
            }
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            return sdf.format(Date(dateAdded))
        }

    fun matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase(Locale.ROOT)
        if (title.lowercase(Locale.ROOT).contains(q)) return true
        if (filename.lowercase(Locale.ROOT).contains(q)) return true
        if (categoryName.lowercase(Locale.ROOT).contains(q)) return true
        if (tags.any { it.lowercase(Locale.ROOT).contains(q) }) return true
        return false
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("filename", filename)
            put("relativePath", relativePath)
            put("categoryId", categoryId)
            put("categoryName", categoryName)
            put("fileSize", fileSize)
            put("dateAdded", dateAdded)
            put("pageCount", pageCount)
            put("lastIndexedModified", lastIndexedModified)
            put("sourceUrl", sourceUrl)
            val tagsArr = JSONArray()
            tags.forEach { tagsArr.put(it) }
            put("tags", tagsArr)
            put("indexedText", indexedText)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): PdfDocument {
            val tagsList = mutableListOf<String>()
            val tagsObj = json.opt("tags")
            if (tagsObj is JSONArray) {
                for (i in 0 until tagsObj.length()) {
                    val t = tagsObj.optString(i, "").trim()
                    if (t.isNotBlank()) tagsList.add(t)
                }
            } else if (tagsObj is String && tagsObj.isNotBlank()) {
                tagsObj.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }.forEach { tagsList.add(it) }
            }

            val catId = json.optString("categoryId").let {
                if (it.isNotBlank()) it
                else {
                    val oldCat = json.optString("category", "OTHER")
                    when (oldCat.uppercase(Locale.ROOT)) {
                        "MANUAL" -> Category.DEFAULT_MANUAL.id
                        "DATASHEET" -> Category.DEFAULT_DATASHEET.id
                        "FLYER" -> Category.DEFAULT_FLYER.id
                        "CATALOG" -> Category.DEFAULT_CATALOG.id
                        else -> Category.DEFAULT_OTHER.id
                    }
                }
            }

            val catName = json.optString("categoryName").let {
                if (it.isNotBlank()) it
                else json.optString("category", "Other")
            }

            return PdfDocument(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", "Untitled Document"),
                filename = json.optString("filename", "document.pdf"),
                relativePath = json.optString("relativePath", ""),
                categoryId = catId,
                categoryName = catName,
                fileSize = json.optLong("fileSize", 0L),
                dateAdded = json.optLong("dateAdded", System.currentTimeMillis()),
                pageCount = json.optInt("pageCount", 0),
                lastIndexedModified = json.optLong("lastIndexedModified", 0L),
                sourceUrl = json.optString("sourceUrl", ""),
                tags = tagsList,
                indexedText = json.optString("indexedText", "")
            )
        }
    }
}
