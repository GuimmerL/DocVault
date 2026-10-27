package com.docvault.app.ui.viewer

import androidx.pdf.PdfDocument
import androidx.pdf.viewer.fragment.PdfViewerFragment

class DocVaultPdfViewerFragment : PdfViewerFragment() {
    var onDocumentLoaded: (() -> Unit)? = null
    var onDocumentError: ((Throwable) -> Unit)? = null

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        onDocumentLoaded?.invoke()
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        onDocumentError?.invoke(error)
    }
}
