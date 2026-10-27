package com.docvault.app.utils

import android.net.Uri
import java.io.File
import java.net.URLDecoder
import java.util.Locale

object FileUtils {

    fun sanitizeFilename(name: String): String {
        var clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (!clean.lowercase(Locale.ROOT).endsWith(".pdf")) {
            clean += ".pdf"
        }
        return clean
    }

    fun cleanTitleFromFilename(filename: String): String {
        var title = filename
        if (title.lowercase(Locale.ROOT).endsWith(".pdf")) {
            title = title.substring(0, title.length - 4)
        }
        title = title.replace('_', ' ').replace('-', ' ').trim()
        // Capitalize words
        return title.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
    }

    fun extractFilenameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path ?: ""
            val lastSegment = path.substringAfterLast('/', "")
            if (lastSegment.isNotBlank()) {
                val decoded = URLDecoder.decode(lastSegment, "UTF-8")
                sanitizeFilename(decoded)
            } else {
                "downloaded_document.pdf"
            }
        } catch (e: Exception) {
            "document_${System.currentTimeMillis()}.pdf"
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
