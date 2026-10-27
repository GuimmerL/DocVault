package com.docvault.app.ui.viewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ext.SdkExtensions
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.ui.dialogs.EditTagsDialog

class OfficialPdfViewerActivity : AppCompatActivity() {
    private var documentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_official_pdf_viewer)
        documentId = intent.getStringExtra(EXTRA_DOC_ID)
        val document = documentId?.let { DocVaultApp.instance.repository.getDocumentById(it) }
        val file = document?.let { DocVaultApp.instance.repository.getFile(it) }
        if (document == null || file == null || !file.exists()) {
            Toast.makeText(this, "Document file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!isPdfViewerSupported()) {
            getSharedPreferences("docvault_settings", 0).edit()
                .putBoolean("use_experimental_pdf_viewer", false).apply()
            Toast.makeText(this, "Experimental PDF viewer is not supported on this device. Standard viewer enabled.", Toast.LENGTH_LONG).show()
            openLegacyViewer()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val loadHandler = Handler(Looper.getMainLooper())
        var loadFinished = false
        val fallback = {
            if (!loadFinished && !isFinishing) {
                loadFinished = true
                getSharedPreferences("docvault_settings", 0).edit()
                    .putBoolean("use_experimental_pdf_viewer", false).apply()
                Toast.makeText(this, "Experimental PDF viewer failed. Standard viewer enabled for future PDFs.", Toast.LENGTH_LONG).show()
                openLegacyViewer()
            }
        }
        val viewer = DocVaultPdfViewerFragment().apply {
            onDocumentLoaded = {
                loadFinished = true
                loadHandler.removeCallbacksAndMessages(null)
            }
            onDocumentError = { fallback() }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.officialPdfContainer, viewer, "ANDROIDX_PDF_VIEWER")
            .commitNow()
        viewer.documentUri = uri
        loadHandler.postDelayed(fallback, 12_000L)
        findViewById<ImageButton>(R.id.btnOfficialPdfTags).setOnClickListener {
            val current = documentId?.let { DocVaultApp.instance.repository.getDocumentById(it) } ?: return@setOnClickListener
            EditTagsDialog.show(this, current) { newTags ->
                if (DocVaultApp.instance.repository.setTags(current.id, newTags)) {
                    current.tags = newTags.toMutableList()
                    Toast.makeText(this, "Tags saved", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isPdfViewerSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    private fun openLegacyViewer() {
        val legacyIntent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_DOC_ID, documentId)
        }
        startActivity(legacyIntent)
        finish()
    }

    companion object {
        const val EXTRA_DOC_ID = "extra_doc_id"
    }
}
