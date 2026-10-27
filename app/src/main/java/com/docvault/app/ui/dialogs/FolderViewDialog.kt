package com.docvault.app.ui.dialogs

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.PdfDocument
import com.docvault.app.ui.viewer.OfficialPdfViewerActivity
import com.docvault.app.ui.viewer.PdfViewerActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object FolderViewDialog {
    fun show(context: Context) {
        val documents = DocVaultApp.instance.repository.getAllDocuments()
            .sortedBy { it.relativePath.lowercase() }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
        }
        if (documents.isEmpty()) {
            list.addView(TextView(context).apply { text = "No documents found"; setPadding(0, 20, 0, 20) })
        } else {
            var previousDirectory = ""
            documents.forEach { document ->
                val directory = document.relativePath.substringBeforeLast('/', "")
                if (directory != previousDirectory) {
                    previousDirectory = directory
                    list.addView(TextView(context).apply {
                        text = if (directory.isBlank()) "DocVault/" else "DocVault/$directory/"
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, 14, 0, 5)
                    })
                }
                list.addView(TextView(context).apply {
                    text = "    ${document.filename}"
                    textSize = 15f
                    setPadding(0, 7, 0, 7)
                    setOnClickListener { openDocument(context, document) }
                })
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Folder view")
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openDocument(context: Context, document: PdfDocument) {
        val experimental = context.getSharedPreferences("docvault_settings", 0)
            .getBoolean("use_experimental_pdf_viewer", false)
        val intent = if (experimental) {
            Intent(context, OfficialPdfViewerActivity::class.java).putExtra(OfficialPdfViewerActivity.EXTRA_DOC_ID, document.id)
        } else {
            Intent(context, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_DOC_ID, document.id)
        }
        context.startActivity(intent)
    }
}
