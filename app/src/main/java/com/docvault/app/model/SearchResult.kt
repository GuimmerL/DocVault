package com.docvault.app.model

data class SearchResult(
    val document: PdfDocument,
    val pageNumber: Int,
    val snippet: String,
    val matchWord: String
)
