package com.docvault.app.ui.dialogs

import android.content.Context
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.docvault.app.ui.library.DocumentAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

object SettingsDialog {
    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun show(
        context: Context,
        currentMode: DocumentAdapter.ViewMode,
        onModeChanged: (DocumentAdapter.ViewMode) -> Unit,
        onCategoriesChanged: () -> Unit = {},
        onThemeChanged: () -> Unit = {}
    ) {
        val prefs = context.getSharedPreferences("docvault_settings", 0)
        val themeValues = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        val themeLabels = arrayOf("Follow OS", "Light theme", "Dark theme")
        val viewLabels = arrayOf("Standard list", "Compact list", "Thumbnail grid")
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
        }
        fun addHeading(text: String) {
            content.addView(TextView(context).apply {
                this.text = text
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(context, 12), 0, dp(context, 4))
            })
        }
        fun addRadioGroup(labels: Array<String>, checked: Int, onSelected: (Int) -> Unit) {
            val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
            labels.forEachIndexed { index, label ->
                group.addView(RadioButton(context).apply {
                    text = label
                    textSize = 16f
                    minHeight = dp(context, 48)
                    isChecked = index == checked
                    setOnClickListener { onSelected(index) }
                })
            }
            content.addView(group)
        }
        addHeading("Document view")
        addRadioGroup(viewLabels, currentMode.ordinal) { index ->
            onModeChanged(DocumentAdapter.ViewMode.values()[index])
        }
        addHeading("PDF viewer (experimental)")
        val viewerLabels = arrayOf("Standard viewer (recommended)", "New AndroidX viewer (experimental)")
        val useExperimentalViewer = prefs.getBoolean("use_experimental_pdf_viewer", false)
        addRadioGroup(viewerLabels, if (useExperimentalViewer) 1 else 0) { index ->
            val enabled = index == 1
            prefs.edit().putBoolean("use_experimental_pdf_viewer", enabled).apply()
            Toast.makeText(context, if (enabled) "Experimental PDF viewer enabled" else "Standard PDF viewer enabled", Toast.LENGTH_SHORT).show()
        }
        addHeading("Theme")
        val savedTheme = prefs.getInt("theme_mode", themeValues[0])
        addRadioGroup(themeLabels, themeValues.indexOf(savedTheme).coerceAtLeast(0)) { index ->
            prefs.edit().putInt("theme_mode", themeValues[index]).apply()
            AppCompatDelegate.setDefaultNightMode(themeValues[index])
            onThemeChanged()
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("DocVault Settings")
            .setView(content)
            .setPositiveButton("Clear thumbnail cache") { _, _ ->
                val cache = File(context.cacheDir, "docvault_thumbnails")
                val count = cache.listFiles()?.count { it.delete() } ?: 0
                Toast.makeText(context, "Cleared $count thumbnail(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
