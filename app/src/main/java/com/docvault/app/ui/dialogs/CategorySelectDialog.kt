package com.docvault.app.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.Category
import com.docvault.app.utils.FileUtils

object CategorySelectDialog {

    fun show(
        context: Context,
        suggestedTitle: String,
        suggestedFilename: String,
        fileSizeBytes: Long,
        onConfirm: (title: String, category: Category, tags: List<String>, subfolder: String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_category_select, null)

        val tvInfo = view.findViewById<TextView>(R.id.tvDetectedInfo)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etDocumentTitle)
        val etSubfolder = view.findViewById<TextInputEditText>(R.id.etDocumentSubfolder)
        val radioGroup = view.findViewById<RadioGroup>(R.id.radioGroupCategories)

        val sizeStr = if (fileSizeBytes > 0) " (${FileUtils.formatFileSize(fileSizeBytes)})" else ""
        tvInfo.text = "$suggestedFilename$sizeStr"
        etTitle.setText(suggestedTitle)

        val categories = DocVaultApp.instance.repository.getCategories()
        radioGroup.removeAllViews()

        categories.forEachIndexed { index, cat ->
            val rb = RadioButton(context).apply {
                id = index + 1000
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
            .setPositiveButton("Download & Save") { dialog, _ ->
                val finalTitle = etTitle.text?.toString()?.trim().let {
                    if (it.isNullOrBlank()) suggestedTitle else it
                }
                val subfolder = etSubfolder.text?.toString()?.trim().orEmpty()

                val checkedId = radioGroup.checkedRadioButtonId
                val selectedIndex = if (checkedId >= 1000) checkedId - 1000 else 0
                val selectedCategory = if (selectedIndex in categories.indices) {
                    categories[selectedIndex]
                } else {
                    categories.first()
                }

                val tags = if (subfolder.isNotBlank()) listOf(subfolder) else emptyList()

                dialog.dismiss()
                onConfirm(finalTitle, selectedCategory, tags, subfolder)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
