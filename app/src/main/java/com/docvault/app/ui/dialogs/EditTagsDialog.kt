package com.docvault.app.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.docvault.app.R
import com.docvault.app.model.PdfDocument

object EditTagsDialog {

    fun show(
        context: Context,
        document: PdfDocument,
        onSave: (List<String>) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_tags, null)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupTags)
        val etTagInput = view.findViewById<EditText>(R.id.etTagInput)
        val btnAddTag = view.findViewById<MaterialButton>(R.id.btnAddTag)

        val workingTags = document.tags.toMutableList()

        fun refreshChips() {
            chipGroup.removeAllViews()
            for (tag in workingTags) {
                val chip = Chip(context).apply {
                    text = "#$tag"
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        workingTags.remove(tag)
                        refreshChips()
                    }
                }
                chipGroup.addView(chip)
            }
        }

        refreshChips()

        btnAddTag.setOnClickListener {
            val text = etTagInput.text?.toString()?.trim()?.removePrefix("#").orEmpty()
            if (text.isNotBlank() && !workingTags.contains(text)) {
                workingTags.add(text)
                etTagInput.setText("")
                refreshChips()
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Tags & Part Numbers")
            .setMessage("Add searchable keywords, part numbers, or context for '${document.title}':")
            .setView(view)
            .setPositiveButton("Save") { dialog, _ ->
                onSave(workingTags)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
