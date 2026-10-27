package com.docvault.app.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.Category

object ImportCategoryDialog {

    fun show(
        context: Context,
        fileCount: Int,
        onConfirm: (category: Category, subfolder: String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_import_category, null)

        val tvCount = view.findViewById<TextView>(R.id.tvImportCount)
        val radioGroup = view.findViewById<RadioGroup>(R.id.radioGroupImportCategories)
        val etSubfolder = view.findViewById<EditText>(R.id.etImportSubfolder)

        val fileWord = if (fileCount == 1) "file" else "files"
        tvCount.text = "Importing $fileCount PDF $fileWord into DocVault folder. Select category destination:"

        val categories = DocVaultApp.instance.repository.getCategories()
        radioGroup.removeAllViews()

        categories.forEachIndexed { index, cat ->
            val rb = RadioButton(context).apply {
                id = index + 2000
                text = "${cat.icon}  ${cat.name} (DocVault/${cat.folderName}/)"
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            radioGroup.addView(rb)
            if (index == 0) {
                rb.isChecked = true
            }
        }

        MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(true)
            .setPositiveButton("Import") { dialog, _ ->
                val checkedId = radioGroup.checkedRadioButtonId
                val selectedIndex = if (checkedId >= 2000) checkedId - 2000 else 0
                val selectedCategory = if (selectedIndex in categories.indices) {
                    categories[selectedIndex]
                } else {
                    categories.first()
                }
                val subfolder = etSubfolder.text?.toString()?.trim().orEmpty()

                dialog.dismiss()
                onConfirm(selectedCategory, subfolder)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
