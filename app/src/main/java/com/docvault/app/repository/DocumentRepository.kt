package com.docvault.app.repository

import android.content.Context
import android.os.Environment
import android.util.Log
import android.util.Base64
import com.docvault.app.model.Category
import com.docvault.app.model.PdfDocument
import com.docvault.app.model.SearchResult
import com.docvault.app.utils.FileUtils
import com.docvault.app.utils.EncryptedVaultStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

enum class SortOrder {
    NEWEST,
    OLDEST,
    TITLE_AZ,
    TITLE_ZA,
    SIZE_LARGEST,
    SIZE_SMALLEST
}

class DocumentRepository(private val context: Context) {

    // Persistent storage location: Documents/DocVault on external storage
    val rootVaultDir: File by lazy {
        try {
            val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val publicVault = File(publicDocs, "DocVault")
            if (publicVault.exists() || publicVault.mkdirs()) {
                publicVault
            } else {
                val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocVault")
                if (!fallback.exists()) fallback.mkdirs()
                fallback
            }
        } catch (e: Exception) {
            val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocVault")
            if (!fallback.exists()) fallback.mkdirs()
            fallback
        }
    }

    private val indexDir: File by lazy {
        val dir = File(context.filesDir, "search_index")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    // Metadata is stored directly inside the vault directory so transferring the folder transfers metadata!
    private val metaFile: File by lazy {
        File(rootVaultDir, ".docvault_metadata.json")
    }

    private data class IndexedPage(val number: Int, val text: String, val lowerText: String)
    private data class CachedIndex(val modified: Long, val pages: List<IndexedPage>)

    private val categoriesList = mutableListOf<Category>()
    private val documentsList = mutableListOf<PdfDocument>()
    // Parsed and lower-cased page indexes stay in memory after the first search.
    // This avoids reading/parsing every JSON file on every keystroke.
    private val indexCache = mutableMapOf<String, CachedIndex>()
    private val lock = Any()
    private val indexingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val unlockedProtectedCategories = mutableSetOf<String>()
    private val unlockedPasswords = mutableMapOf<String, CharArray>()
    private val protectedRoot: File by lazy { File(context.filesDir, "protected_vault").apply { mkdirs() } }

    init {
        // Metadata is lightweight; defer the recursive filesystem scan until the UI is visible.
        ensureCategoryFolders()
        loadMetadata()
    }

    suspend fun syncInBackground(): Int = withContext(Dispatchers.IO) {
        val added = syncWithFileSystem()
        rebuildMissingIndexes()
        added
    }

    private fun ensureCategoryFolders() {
        synchronized(lock) {
            if (categoriesList.isEmpty()) {
                categoriesList.addAll(Category.getDefaultCategories())
            }
            for (cat in categoriesList) {
                val folder = File(rootVaultDir, cat.folderName)
                if (!folder.exists()) folder.mkdirs()
            }
        }
    }

    fun loadMetadata() {
        synchronized(lock) {
            categoriesList.clear()
            documentsList.clear()

            // 1. Try reading .docvault_metadata.json inside vault root
            if (metaFile.exists()) {
                try {
                    val rootObj = JSONObject(metaFile.readText())

                    // Categories
                    val catArr = rootObj.optJSONArray("categories")
                    if (catArr != null && catArr.length() > 0) {
                        for (i in 0 until catArr.length()) {
                            categoriesList.add(Category.fromJsonObject(catArr.getJSONObject(i)))
                        }
                    }

                    // Documents
                    val docArr = rootObj.optJSONArray("documents")
                    if (docArr != null) {
                        for (i in 0 until docArr.length()) {
                            val doc = PdfDocument.fromJsonObject(docArr.getJSONObject(i))
                            val file = getFile(doc)
                            if (file.exists()) {
                                documentsList.add(doc)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DocumentRepository", "Error reading .docvault_metadata.json", e)
                }
            }

            // Ensure default categories exist if none loaded
            if (categoriesList.isEmpty()) {
                categoriesList.addAll(Category.getDefaultCategories())
            }
        }
    }

    fun saveMetadata() {
        synchronized(lock) {
            try {
                val rootObj = JSONObject()

                // Categories
                val catArr = JSONArray()
                for (cat in categoriesList) {
                    catArr.put(cat.toJsonObject())
                }
                rootObj.put("categories", catArr)

                // Documents
                val docArr = JSONArray()
                for (doc in documentsList) {
                    docArr.put(doc.toJsonObject())
                }
                rootObj.put("documents", docArr)

                metaFile.writeText(rootObj.toString(2))
            } catch (e: Exception) {
                Log.e("DocumentRepository", "Error saving metadata to vault", e)
            }
        }
    }

    /**
     * Scans the file system under DocVault/.
     * Automatically discovers any PDF added outside the app (e.g. copied via USB/SD card/PC).
     * If the PDF is in a subfolder (e.g. Flyer/One-Pager/xxx.pdf), the subfolder name
     * is automatically extracted and assigned as a tag!
     */
    fun syncWithFileSystem(): Int {
        var addedCount = 0
        synchronized(lock) {
            ensureCategoryFolders()

            val existingDocsByPath = documentsList.associateBy { it.relativePath }.toMutableMap()
            val diskPdfs = mutableListOf<Triple<File, Category, List<String>>>()

            // Scan all top-level folders in rootVaultDir
            val topDirs = rootVaultDir.listFiles { f -> f.isDirectory } ?: emptyArray()

            for (dir in topDirs) {
                val dirName = dir.name
                if (dirName.startsWith(".")) continue

                // Match category (handles singular/plural like Flyer vs Flyers, Manual vs Manuals)
                var matchedCat = categoriesList.firstOrNull { cat ->
                    cat.folderName.equals(dirName, ignoreCase = true) ||
                    cat.name.equals(dirName, ignoreCase = true) ||
                    cat.folderName.removeSuffix("s").equals(dirName.removeSuffix("s"), ignoreCase = true) ||
                    cat.name.removeSuffix("s").equals(dirName.removeSuffix("s"), ignoreCase = true)
                }

                // If user created a new custom category folder externally, register it!
                if (matchedCat == null) {
                    matchedCat = addCategory(dirName, icon = "📁", colorHex = "#0D9488")
                }

                // Recursively walk this directory and all subdirectories
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && file.name.lowercase(Locale.ROOT).endsWith(".pdf")) {
                        // Extract all subfolders between dir and file as tags!
                        val parent = file.parentFile
                        val subfolderTags = mutableListOf<String>()
                        if (parent != null && parent != dir) {
                            val rel = parent.relativeTo(dir).path
                            rel.split(File.separatorChar).forEach { seg ->
                                val cleanSeg = seg.trim()
                                if (cleanSeg.isNotBlank() && cleanSeg != ".") {
                                    subfolderTags.add(cleanSeg)
                                }
                            }
                        }
                        diskPdfs.add(Triple(file, matchedCat, subfolderTags))
                    }
                }
            }

            // Also scan root folder for unclassified PDFs
            val rootPdfs = rootVaultDir.listFiles { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".pdf") }
            val otherCat = categoriesList.firstOrNull { it.id == Category.DEFAULT_OTHER.id } ?: categoriesList.first()
            rootPdfs?.forEach { file ->
                diskPdfs.add(Triple(file, otherCat, emptyList()))
            }

            for ((file, cat, subfolderTags) in diskPdfs) {
                val relPath = file.relativeTo(rootVaultDir).path
                val existingDoc = existingDocsByPath[relPath]

                if (existingDoc == null) {
                    val originalName = file.name
                    val title = FileUtils.cleanTitleFromFilename(originalName)

                    val doc = PdfDocument(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        filename = originalName,
                        relativePath = relPath,
                        categoryId = cat.id,
                        categoryName = cat.name,
                        fileSize = file.length(),
                        dateAdded = file.lastModified(),
                        sourceUrl = "Local file system",
                        tags = subfolderTags.toMutableList()
                    )

                    indexDocumentQuick(doc, file)
                    documentsList.add(doc)
                    existingDocsByPath[relPath] = doc
                    addedCount++
                } else {
                    // Update category if folder changed
                    if (existingDoc.categoryId != cat.id) {
                        existingDoc.categoryId = cat.id
                        existingDoc.categoryName = cat.name
                    }
                    // Add any subfolder tags that aren't already attached
                    var changed = false
                    for (tag in subfolderTags) {
                        if (!existingDoc.tags.contains(tag)) {
                            existingDoc.tags.add(tag)
                            changed = true
                        }
                    }
                    if (changed) {
                        saveMetadata()
                    }
                }
            }

            // Remove documents whose files were deleted from disk
            val iterator = documentsList.iterator()
            while (iterator.hasNext()) {
                val doc = iterator.next()
                val f = getFile(doc)
                if (!f.exists()) {
                    iterator.remove()
                }
            }

            saveMetadata()
        }
        return addedCount
    }

    // Categories Management
    fun getCategories(): List<Category> {
        synchronized(lock) {
            return categoriesList.toList()
        }
    }

    fun getCategoryById(id: String): Category? {
        synchronized(lock) {
            return categoriesList.firstOrNull { it.id == id }
        }
    }

    fun setCategoryHidden(categoryId: String, hidden: Boolean): Boolean {
        synchronized(lock) {
            val category = categoriesList.firstOrNull { it.id == categoryId } ?: return false
            category.isHidden = hidden
            saveMetadata()
            return true
        }
    }

    fun moveCategory(categoryId: String, direction: Int): Boolean {
        synchronized(lock) {
            val index = categoriesList.indexOfFirst { it.id == categoryId }
            val target = index + direction
            if (index < 0 || target !in categoriesList.indices) return false
            val item = categoriesList.removeAt(index)
            categoriesList.add(target, item)
            saveMetadata()
            return true
        }
    }

    fun addCategory(name: String, icon: String = "📁", colorHex: String = "#2563EB"): Category {
        synchronized(lock) {
            val safeFolderName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val newCat = Category(
                id = "cat_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                folderName = safeFolderName,
                isSystem = false,
                colorHex = colorHex,
                icon = icon
            )
            categoriesList.add(newCat)

            // Create folder on disk
            val folder = File(rootVaultDir, newCat.folderName)
            if (!folder.exists()) folder.mkdirs()

            saveMetadata()
            return newCat
        }
    }

    fun renameCategory(categoryId: String, newName: String): Boolean {
        synchronized(lock) {
            val cat = categoriesList.firstOrNull { it.id == categoryId } ?: return false
            cat.name = newName.trim()
            // Update documents belonging to this category
            for (doc in documentsList) {
                if (doc.categoryId == categoryId) {
                    doc.categoryName = cat.name
                }
            }
            saveMetadata()
            return true
        }
    }

    fun deleteCategory(categoryId: String): Boolean {
        synchronized(lock) {
            val cat = categoriesList.firstOrNull { it.id == categoryId } ?: return false
            if (cat.isSystem) return false // Do not delete default system categories

            val otherCat = categoriesList.firstOrNull { it.id == Category.DEFAULT_OTHER.id } ?: categoriesList.first()
            val sourceDir = File(rootVaultDir, cat.folderName)
            val targetDir = File(rootVaultDir, otherCat.folderName)
            if (!targetDir.exists()) targetDir.mkdirs()

            // Move PDFs to Other so deleting a category never loses documents and
            // a later filesystem sync cannot recreate the deleted category.
            for (doc in documentsList.filter { it.categoryId == categoryId }.toList()) {
                val oldFile = getFile(doc)
                var newFile = File(targetDir, doc.filename)
                var counter = 1
                while (oldFile.exists() && newFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                    val base = doc.filename.substringBeforeLast('.', doc.filename)
                    newFile = File(targetDir, "$base ($counter).pdf")
                    counter++
                }
                if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                    newFile.parentFile?.mkdirs()
                    if (oldFile.renameTo(newFile)) {
                        doc.relativePath = newFile.relativeTo(rootVaultDir).path
                        doc.categoryId = otherCat.id
                        doc.categoryName = otherCat.name
                        doc.filename = newFile.name
                    }
                } else {
                    doc.categoryId = otherCat.id
                    doc.categoryName = otherCat.name
                }
            }

            categoriesList.remove(cat)
            if (sourceDir.exists()) sourceDir.deleteRecursively()
            saveMetadata()
            return true
        }
    }

    // Document Management
    fun getAllDocuments(): List<PdfDocument> {
        synchronized(lock) {
            return documentsList.filter { isDocumentVisible(it) }.toList()
        }
    }

    fun getDocuments(
        categoryId: String? = null,
        query: String? = null,
        sortOrder: SortOrder = SortOrder.NEWEST
    ): List<PdfDocument> {
        synchronized(lock) {
            var list = documentsList.filter { isDocumentVisible(it) }.toList()

            // Filter by category
            if (!categoryId.isNullOrBlank()) {
                list = list.filter { it.categoryId == categoryId }
            }

            // Filter by query (title, filename, tags, part numbers)
            if (!query.isNullOrBlank()) {
                list = list.filter { it.matchesQuery(query) }
            }

            // Sort
            list = when (sortOrder) {
                SortOrder.NEWEST -> list.sortedByDescending { it.dateAdded }
                SortOrder.OLDEST -> list.sortedBy { it.dateAdded }
                SortOrder.TITLE_AZ -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
                SortOrder.TITLE_ZA -> list.sortedByDescending { it.title.lowercase(Locale.ROOT) }
                SortOrder.SIZE_LARGEST -> list.sortedByDescending { it.fileSize }
                SortOrder.SIZE_SMALLEST -> list.sortedBy { it.fileSize }
            }

            return list
        }
    }

    fun getDocumentById(id: String): PdfDocument? {
        synchronized(lock) {
            return documentsList.firstOrNull { it.id == id }
        }
    }

    fun isCategoryUnlocked(categoryId: String): Boolean = synchronized(lock) {
        !getCategoryById(categoryId).let { it?.isPasswordProtected == true } || unlockedProtectedCategories.contains(categoryId)
    }

    fun protectCategory(categoryId: String, password: CharArray): Boolean {
        val category = getCategoryById(categoryId) ?: return false
        if (category.isPasswordProtected || password.isEmpty()) return false
        val salt = EncryptedVaultStore.newSalt()
        val vaultDir = File(protectedRoot, categoryId).apply { mkdirs() }
        val verifierPlain = File(cacheDir(), "${categoryId}_verify.bin").apply { writeText("DocVault protected category") }
        val verifier = File(vaultDir, "_verifier.dv")
        return try {
            EncryptedVaultStore.encrypt(verifierPlain, verifier, password, salt)
            verifierPlain.delete()
            File(vaultDir, "_salt").writeText(Base64.encodeToString(salt, Base64.NO_WRAP))
            val categoryDocs = synchronized(lock) { documentsList.filter { it.categoryId == categoryId && !it.relativePath.startsWith(".protected/") }.toList() }
            val encryptedPairs = mutableListOf<Pair<PdfDocument, File>>()
            for (doc in categoryDocs) {
                val source = File(rootVaultDir, doc.relativePath)
                if (!source.exists()) continue
                val encrypted = File(vaultDir, "${doc.id}.dvpdf")
                EncryptedVaultStore.encrypt(source, encrypted, password, salt)
                encryptedPairs.add(doc to source)
            }
            for ((doc, source) in encryptedPairs) {
                source.delete()
                doc.relativePath = ".protected/$categoryId/${doc.id}.dvpdf"
            }
            category.isPasswordProtected = true
            saveMetadata()
            true
        } catch (error: Exception) {
            Log.e("DocumentRepository", "Unable to protect category", error)
            false
        }
    }

    fun unlockCategory(categoryId: String, password: CharArray): Boolean {
        val category = getCategoryById(categoryId) ?: return false
        if (!category.isPasswordProtected) return true
        val vaultDir = File(protectedRoot, categoryId)
        val saltFile = File(vaultDir, "_salt")
        val verifier = File(vaultDir, "_verifier.dv")
        if (!saltFile.exists() || !verifier.exists()) return false
        val salt = try { Base64.decode(saltFile.readText(), Base64.NO_WRAP) } catch (_: Exception) { return false }
        val plain = File(cacheDir(), "${categoryId}_verify.bin")
        return try {
            EncryptedVaultStore.decrypt(verifier, plain, password, salt)
            val valid = plain.readText() == "DocVault protected category"
            plain.delete()
            if (valid) synchronized(lock) {
                unlockedProtectedCategories.add(categoryId)
                unlockedPasswords[categoryId] = password.copyOf()
            }
            valid
        } catch (_: Exception) {
            plain.delete()
            false
        }
    }

    fun lockCategory(categoryId: String) {
        synchronized(lock) {
            unlockedProtectedCategories.remove(categoryId)
            unlockedPasswords.remove(categoryId)?.fill(0.toChar())
        }
        File(context.cacheDir, "protected_pdf/$categoryId").deleteRecursively()
    }

    private fun isDocumentVisible(document: PdfDocument): Boolean {
        val category = categoriesList.firstOrNull { it.id == document.categoryId }
        return category?.isPasswordProtected != true || unlockedProtectedCategories.contains(category.id)
    }

    private fun cacheDir(): File = File(context.cacheDir, "protected_verify").apply { mkdirs() }

    fun getFile(document: PdfDocument): File {
        val category = synchronized(lock) { categoriesList.firstOrNull { it.id == document.categoryId } }
        if (category?.isPasswordProtected == true) {
            val vaultDir = File(protectedRoot, category.id)
            val encrypted = File(vaultDir, "${document.id}.dvpdf")
            if (!unlockedProtectedCategories.contains(category.id)) return encrypted
            val plain = File(context.cacheDir, "protected_pdf/${category.id}/${document.id}.pdf")
            if (encrypted.exists() && (!plain.exists() || plain.lastModified() < encrypted.lastModified())) {
                try {
                    val salt = Base64.decode(File(vaultDir, "_salt").readText(), Base64.NO_WRAP)
                    val password = synchronized(lock) { unlockedPasswords[category.id]?.copyOf() } ?: return encrypted
                    EncryptedVaultStore.decrypt(encrypted, plain, password, salt)
                    password.fill(0.toChar())
                } catch (_: Exception) { return encrypted }
            }
            return if (plain.exists()) plain else encrypted
        }
        return File(rootVaultDir, document.relativePath)
    }
    /**
     * Adds a document preserving its ORIGINAL filename and organizing into category/subfolder!
     */
    suspend fun addDocument(
        title: String,
        category: Category,
        inputStream: InputStream,
        originalFilename: String,
        sourceUrl: String = "",
        initialTags: List<String> = emptyList(),
        subfolder: String = ""
    ): PdfDocument = withContext(Dispatchers.IO) {
        val baseCatDir = File(rootVaultDir, category.folderName)
        val targetDir = if (subfolder.isNotBlank()) {
            val cleanSub = subfolder.replace(Regex("[\\\\:*?\"<>|]"), "_").trim()
            File(baseCatDir, cleanSub)
        } else {
            baseCatDir
        }
        if (!targetDir.exists()) targetDir.mkdirs()

        // PRESERVE ORIGINAL FILENAME (no UUID prefix!)
        var safeFilename = FileUtils.sanitizeFilename(originalFilename)
        var destFile = File(targetDir, safeFilename)

        // Handle collision only if identical file already exists in same folder
        if (destFile.exists()) {
            val baseName = safeFilename.substringBeforeLast(".pdf")
            var counter = 1
            while (destFile.exists()) {
                safeFilename = "$baseName ($counter).pdf"
                destFile = File(targetDir, safeFilename)
                counter++
            }
        }

        destFile.outputStream().use { out ->
            inputStream.copyTo(out)
        }

        val fileSize = destFile.length()
        val docTitle = if (title.isNotBlank()) title else FileUtils.cleanTitleFromFilename(safeFilename)
        val relPath = destFile.relativeTo(rootVaultDir).path

        val combinedTags = initialTags.toMutableList()
        if (subfolder.isNotBlank()) {
            val cleanSubTag = subfolder.trim()
            if (!combinedTags.contains(cleanSubTag)) {
                combinedTags.add(cleanSubTag)
            }
        }

        val doc = PdfDocument(
            id = UUID.randomUUID().toString(),
            title = docTitle,
            filename = safeFilename,
            relativePath = relPath,
            categoryId = category.id,
            categoryName = category.name,
            fileSize = fileSize,
            dateAdded = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
            tags = combinedTags
        )

        if (!indexDocumentQuick(doc, destFile)) {
            destFile.delete()
            throw IllegalArgumentException("The selected file is not a readable PDF")
        }
        synchronized(lock) {
            documentsList.add(0, doc)
            saveMetadata()
        }
        queueIndexing(doc)
        doc
    }

    private suspend fun rebuildMissingIndexes(): Int = withContext(Dispatchers.IO) {
        var rebuilt = 0
        for (doc in getAllDocuments()) {
            val indexFile = File(indexDir, "${doc.id}.json")
            val file = getFile(doc)
            if (file.exists() && (!indexFile.exists() || doc.lastIndexedModified != file.lastModified() || doc.fileSize != file.length())) {
                indexDocument(doc, file)
                rebuilt++
            }
        }
        if (rebuilt > 0) saveMetadata()
        rebuilt
    }

    fun queueIndexing(doc: PdfDocument) {
        indexingScope.launch {
            indexDocument(doc, getFile(doc))
            saveMetadata()
        }
    }

    private fun indexDocumentQuick(doc: PdfDocument, file: File): Boolean {
        return try {
            PDDocument.load(file).use { pdDoc ->
                doc.pageCount = pdDoc.numberOfPages
            }
            true
        } catch (e: Exception) {
            Log.w("DocumentRepository", "Unreadable PDF: ${file.name}", e)
            false
        }
    }

    private fun indexDocument(doc: PdfDocument, file: File) {
        try {
            PDDocument.load(file).use { pdDoc ->
                doc.pageCount = pdDoc.numberOfPages
                val stripper = PDFTextStripper()
                val pageDataList = JSONArray()

                val fullTextSnippet = StringBuilder()
                for (p in 1..pdDoc.numberOfPages) {
                    stripper.startPage = p
                    stripper.endPage = p
                    val pageText = stripper.getText(pdDoc) ?: ""
                    val pageObj = JSONObject().apply {
                        put("page", p)
                        put("text", pageText)
                    }
                    pageDataList.put(pageObj)

                    if (fullTextSnippet.length < 1000 && pageText.isNotBlank()) {
                        fullTextSnippet.append(pageText.trim()).append(" ")
                    }
                }

                val indexFile = File(indexDir, "${doc.id}.json")
                indexFile.writeText(pageDataList.toString())
                synchronized(lock) { indexCache.remove(doc.id) }

                doc.indexedText = fullTextSnippet.take(500).toString()
                doc.fileSize = file.length()
                doc.lastIndexedModified = file.lastModified()
            }
        } catch (e: Exception) {
            Log.e("DocumentRepository", "Failed to extract text from PDF: ${file.name}", e)
        }
    }

    // Tag Operations
    fun addTag(docId: String, tag: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: return false
            val cleanTag = tag.trim().removePrefix("#")
            if (cleanTag.isNotBlank() && !doc.tags.contains(cleanTag)) {
                doc.tags.add(cleanTag)
                saveMetadata()
                return true
            }
            return false
        }
    }

    fun removeTag(docId: String, tag: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: return false
            val removed = doc.tags.remove(tag.trim().removePrefix("#"))
            if (removed) saveMetadata()
            return removed
        }
    }

    fun setTags(docId: String, newTags: List<String>): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: return false
            doc.tags.clear()
            doc.tags.addAll(newTags.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct())
            saveMetadata()
            return true
        }
    }

    fun getAllUniqueTags(): List<String> {
        synchronized(lock) {
            return documentsList.flatMap { it.tags }.distinct().sorted()
        }
    }

    fun updateCategory(docId: String, newCategory: Category): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: return false
            val oldFile = getFile(doc)

            // Move file to new category folder
            val targetDir = File(rootVaultDir, newCategory.folderName)
            if (!targetDir.exists()) targetDir.mkdirs()
            val newFile = File(targetDir, doc.filename)

            if (oldFile.exists() && oldFile != newFile) {
                oldFile.renameTo(newFile)
            }

            doc.categoryId = newCategory.id
            doc.categoryName = newCategory.name
            doc.relativePath = newFile.relativeTo(rootVaultDir).path

            saveMetadata()
            return true
        }
    }

    fun updateTitle(id: String, newTitle: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == id } ?: return false
            doc.title = newTitle.trim()
            saveMetadata()
            return true
        }
    }

    fun deleteDocument(id: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == id } ?: return false
            val file = getFile(doc)
            if (file.exists()) {
                file.delete()
            }
            val indexFile = File(indexDir, "${doc.id}.json")
            if (indexFile.exists()) {
                indexFile.delete()
            }
            documentsList.remove(doc)
            saveMetadata()
            return true
        }
    }

    private fun loadCachedIndex(doc: PdfDocument): List<IndexedPage> {
        val indexFile = File(indexDir, "${doc.id}.json")
        if (!indexFile.exists()) return emptyList()
        val modified = indexFile.lastModified()
        synchronized(lock) {
            indexCache[doc.id]?.takeIf { it.modified == modified }?.let { return it.pages }
        }
        return try {
            val jsonArray = JSONArray(indexFile.readText())
            val pages = buildList {
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val text = obj.optString("text", "")
                    add(IndexedPage(obj.optInt("page", i + 1), text, text.lowercase(Locale.ROOT)))
                }
            }
            synchronized(lock) { indexCache[doc.id] = CachedIndex(modified, pages) }
            pages
        } catch (e: Exception) {
            Log.e("DocumentRepository", "Error reading index for ${doc.id}", e)
            emptyList()
        }
    }

    suspend fun searchFullText(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return@withContext results
        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        val ranked = mutableListOf<Pair<Int, SearchResult>>()

        for (doc in getAllDocuments()) {
            var pages = loadCachedIndex(doc)
            if (pages.isEmpty()) {
                val file = getFile(doc)
                if (file.exists()) {
                    indexDocument(doc, file)
                    pages = loadCachedIndex(doc)
                }
            }
            if (pages.isEmpty() && terms.all { doc.matchesQuery(it) }) {
                val metadataScore = if (doc.title.lowercase(Locale.ROOT).contains(q)) 100 else 50
                ranked.add(metadataScore to SearchResult(doc, 1, "Document match: ${doc.title} (${doc.categoryName})", q))
                continue
            }
            for (page in pages) {
                val text = page.text
                if (!terms.all { page.lowerText.contains(it) }) continue
                val matchIndex = page.lowerText.indexOf(q).takeIf { it >= 0 } ?: page.lowerText.indexOf(terms.first())
                if (matchIndex < 0) continue
                val start = maxOf(0, matchIndex - 180)
                val end = minOf(text.length, matchIndex + terms.first().length + 220)
                var snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
                if (start > 0) snippet = "…$snippet"
                if (end < text.length) snippet = "$snippet…"
                val phraseBonus = if (page.lowerText.contains(q)) 100 else 0
                ranked.add((terms.size * 20 + phraseBonus) to SearchResult(doc, page.number, snippet, q))
            }
        }
        results.addAll(ranked.sortedByDescending { it.first }.map { it.second })
        results
    }
}
