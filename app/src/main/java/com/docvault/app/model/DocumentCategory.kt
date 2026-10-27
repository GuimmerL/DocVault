package com.docvault.app.model

import com.docvault.app.R

enum class DocumentCategory(
    val displayName: String,
    val badgeBgRes: Int,
    val badgeTextColorRes: Int,
    val iconPrefix: String
) {
    MANUAL("Manual", R.drawable.bg_badge_manual, R.color.cat_manual, "📘"),
    DATASHEET("Datasheet", R.drawable.bg_badge_datasheet, R.color.cat_datasheet, "📊"),
    FLYER("Flyer", R.drawable.bg_badge_flyer, R.color.cat_flyer, "📄"),
    CATALOG("Catalog", R.drawable.bg_badge_catalog, R.color.cat_catalog, "📚"),
    OTHER("Other", R.drawable.bg_badge_other, R.color.cat_other, "📁");

    companion object {
        fun fromString(value: String?): DocumentCategory {
            if (value.isNullOrBlank()) return OTHER
            return entries.firstOrNull { 
                it.name.equals(value, ignoreCase = true) || 
                it.displayName.equals(value, ignoreCase = true) 
            } ?: OTHER
        }
    }
}
