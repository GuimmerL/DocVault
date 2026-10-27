package com.docvault.app.ui.dialogs

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.docvault.app.DocVaultApp
import com.docvault.app.model.Category

object CategoryMenuDialog {
    fun show(context: Context, selectedId: String?, onSelected: (String?) -> Unit) {
        val categories = DocVaultApp.instance.repository.getCategories().filter { !it.isHidden }
        val labels = arrayOf("All") + categories.map { "${it.icon}  ${it.name}" }
        val checked = if (selectedId == null) 0 else categories.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it + 1 }
        MaterialAlertDialogBuilder(context)
            .setTitle("Categories")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onSelected(if (which == 0) null else categories[which - 1].id)
                dialog.dismiss()
            }
            .setPositiveButton("Manage categories") { _, _ ->
                ManageCategoriesDialog.show(context) { onSelected(null) }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
