package com.docvault.app.repository

import java.util.Locale

/** Keeps search-result highlights aligned with the viewer's exact substring matching. */
object SearchQueryMatcher {
    fun matchingHighlight(query: String, pageText: String): String? {
        val phrase = query.trim().lowercase(Locale.ROOT)
        if (phrase.isEmpty()) return null
        val page = pageText.lowercase(Locale.ROOT)
        if (page.contains(phrase)) return phrase
        val terms = phrase.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        if (terms.isEmpty() || !terms.all(page::contains)) return null
        return terms.firstOrNull { page.contains(it) }
    }
}
