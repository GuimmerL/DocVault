package com.docvault.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.docvault.app.model.PdfDocument
import java.io.File

object ShareHelper {

    fun shareDocument(context: Context, document: PdfDocument, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "Error: File not found on device", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "[DocVault - ${document.categoryName}] ${document.title}"
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Attached is the ${document.categoryName} document:\n\n" +
                            "• Title: ${document.title}\n" +
                            "• Category: ${document.categoryName}\n" +
                            "• File: ${document.filename} (${document.formattedFileSize})\n" +
                            (if (document.tags.isNotEmpty()) "• Tags: ${document.tags.joinToString(", ") { "#$it" }}\n\n" else "\n") +
                            "Sent from DocVault."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share / Email PDF via:")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openExternal(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, "Open PDF with:")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to open PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
