package com.docvault.app.utils

import android.graphics.RectF
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.util.Locale

class TextMatchStripper(private val targetWord: String) : PDFTextStripper() {
    val boundingBoxes = mutableListOf<RectF>()

    init {
        sortByPosition = true
    }

    @Throws(IOException::class)
    override fun writeString(text: String, textPositions: List<TextPosition>) {
        val lowerText = text.lowercase(Locale.ROOT)
        val lowerTarget = targetWord.lowercase(Locale.ROOT)
        if (lowerTarget.isBlank()) return

        var index = lowerText.indexOf(lowerTarget)
        while (index >= 0) {
            val endIndex = index + lowerTarget.length
            if (endIndex <= textPositions.size) {
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = Float.MIN_VALUE
                var maxY = Float.MIN_VALUE

                for (i in index until endIndex) {
                    val pos = textPositions[i]
                    val x = pos.xDirAdj
                    val y = pos.yDirAdj
                    val w = pos.widthDirAdj
                    val h = pos.heightDir

                    minX = minOf(minX, x)
                    minY = minOf(minY, y - h)
                    maxX = maxOf(maxX, x + w)
                    maxY = maxOf(maxY, y)
                }

                if (minX < maxX && minY < maxY) {
                    boundingBoxes.add(RectF(minX, minY, maxX, maxY))
                }
            }
            index = lowerText.indexOf(lowerTarget, index + 1)
        }
    }
}

object PdfHighlightHelper {

    fun findMatchesOnPage(file: File, pageNumber: Int, query: String): List<RectF> {
        val q = query.trim()
        if (q.isBlank() || !file.exists()) return emptyList()

        return try {
            PDDocument.load(file).use { pdDoc ->
                if (pageNumber < 1 || pageNumber > pdDoc.numberOfPages) return emptyList()

                val stripper = TextMatchStripper(q)
                stripper.startPage = pageNumber
                stripper.endPage = pageNumber
                stripper.getText(pdDoc) // Triggers writeString with TextPosition list
                stripper.boundingBoxes
            }
        } catch (e: Exception) {
            Log.e("PdfHighlightHelper", "Error extracting highlight coordinates", e)
            emptyList()
        }
    }

    fun findMatchingPages(file: File, query: String): List<Int> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank() || !file.exists()) return emptyList()

        val matchingPages = mutableListOf<Int>()
        try {
            PDDocument.load(file).use { pdDoc ->
                val stripper = PDFTextStripper()
                for (p in 1..pdDoc.numberOfPages) {
                    stripper.startPage = p
                    stripper.endPage = p
                    val text = stripper.getText(pdDoc)?.lowercase(Locale.ROOT) ?: ""
                    if (text.contains(q)) {
                        matchingPages.add(p)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PdfHighlightHelper", "Error scanning matching pages", e)
        }
        return matchingPages
    }
}
