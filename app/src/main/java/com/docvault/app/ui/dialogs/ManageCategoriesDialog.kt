package com.docvault.app.ui.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.docvault.app.DocVaultApp

object ManageCategoriesDialog {
    private val buttonColor = Color.rgb(229, 231, 235)
    private val textColor = Color.rgb(31, 41, 55)

    fun show(context: Context, onCategoriesChanged: () -> Unit) {
        val repo = DocVaultApp.instance.repository
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }
        scroll.addView(list)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Manage categories")
            .setMessage("Use the buttons beside each category to hide, show, reorder, or delete it.")
            .setView(scroll)
            .setPositiveButton("+ Add category") { _, _ -> showAddCategoryDialog(context, onCategoriesChanged) }
            .setNegativeButton("Close", null)
            .create()

        fun reopen(action: () -> Unit) {
            dialog.dismiss()
            action()
            onCategoriesChanged()
            show(context, onCategoriesChanged)
        }

        repo.getCategories().forEachIndexed { index, category ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4, 10, 4, 10)
            }
            val label = TextView(context).apply {
                text = "${category.icon}  ${category.name}  (${if (category.isHidden) "Hidden" else "Visible"}${if (category.isSystem) ", Default" else ""})"
                textSize = 15f
                setTextColor(textColor)
                setPadding(4, 2, 4, 6)
            }
            row.addView(label)
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            fun actionButton(text: String, enabled: Boolean = true, action: () -> Unit) {
                val button = MaterialButton(context).apply {
                    this.text = text
                    this.isEnabled = enabled
                    this.textSize = 11f
                    this.setTextColor(textColor)
                    this.backgroundTintList = ColorStateList.valueOf(buttonColor)
                    this.cornerRadius = 10
                    this.setOnClickListener { action() }
                }
                actions.addView(button, LinearLayout.LayoutParams(0, 42, 1f).apply { marginEnd = 4 })
            }
            actionButton(if (category.isHidden) "Show" else "Hide") {
                reopen { repo.setCategoryHidden(category.id, !category.isHidden) }
            }
            actionButton("↑", index > 0) {
                reopen { repo.moveCategory(category.id, -1) }
            }
            actionButton("↓", index < repo.getCategories().lastIndex) {
                reopen { repo.moveCategory(category.id, 1) }
            }
            if (!category.isSystem) {
                actionButton("Delete") {
                    reopen { repo.deleteCategory(category.id) }
                }
            }
            row.addView(actions)
            list.addView(row)
        }
        dialog.show()
    }

    fun showAddCategoryDialog(context: Context, onAdded: () -> Unit) {
        val input = EditText(context).apply {
            hint = "e.g. Certificates, Wiring Diagrams"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Create New Category")
            .setMessage("A matching folder will be created in DocVault.")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    DocVaultApp.instance.repository.addCategory(name, icon = "📁", colorHex = "#0D9488")
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
