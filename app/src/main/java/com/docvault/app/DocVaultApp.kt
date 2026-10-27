package com.docvault.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.docvault.app.repository.DocumentRepository

class DocVaultApp : Application() {

    lateinit var repository: DocumentRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize PDFBox for offline text extraction and search indexing
        PDFBoxResourceLoader.init(applicationContext)
        // Initialize document repository
        repository = DocumentRepository(applicationContext)
    }

    companion object {
        lateinit var instance: DocVaultApp
            private set
    }
}
