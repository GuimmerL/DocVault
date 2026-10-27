package com.docvault.app.model

import org.json.JSONObject

data class Category(
    val id: String,
    var name: String,
    var folderName: String,
    val isSystem: Boolean = false,
    var colorHex: String = "#2563EB",
    var icon: String = "📁",
    var isHidden: Boolean = false,
    var isPasswordProtected: Boolean = false
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("folderName", folderName)
            put("isSystem", isSystem)
            put("colorHex", colorHex)
            put("icon", icon)
            put("isHidden", isHidden)
            put("isPasswordProtected", isPasswordProtected)
        }
    }

    companion object {
        val DEFAULT_MANUAL = Category("cat_manual", "Manual", "Manuals", true, "#2563EB", "📘")
        val DEFAULT_DATASHEET = Category("cat_datasheet", "Datasheet", "Datasheets", true, "#059669", "📊")
        val DEFAULT_FLYER = Category("cat_flyer", "Flyer", "Flyers", true, "#D97706", "📄")
        val DEFAULT_CATALOG = Category("cat_catalog", "Catalog", "Catalogs", true, "#7C3AED", "📚")
        val DEFAULT_OTHER = Category("cat_other", "Other", "Other", true, "#475569", "📁")

        fun getDefaultCategories(): MutableList<Category> = mutableListOf(
            DEFAULT_MANUAL,
            DEFAULT_DATASHEET,
            DEFAULT_FLYER,
            DEFAULT_CATALOG,
            DEFAULT_OTHER
        )

        fun fromJsonObject(json: JSONObject): Category {
            return Category(
                id = json.optString("id", "cat_${System.currentTimeMillis()}"),
                name = json.optString("name", "Custom"),
                folderName = json.optString("folderName", "Custom"),
                isSystem = json.optBoolean("isSystem", false),
                colorHex = json.optString("colorHex", "#2563EB"),
                icon = json.optString("icon", "📁"),
                isHidden = json.optBoolean("isHidden", false),
                isPasswordProtected = json.optBoolean("isPasswordProtected", false)
            )
        }
    }
}
