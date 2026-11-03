package com.docvault.app.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import android.util.Base64
import com.docvault.app.model.Category
import com.docvault.app.model.PdfDocument
import com.docvault.app.model.SearchResult
import com.docvault.app.utils.FileUtils
import com.docvault.app.utils.EncryptedVaultStore
import com.docvault.app.utils.VaultPathPolicy
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    // Keep the working copy app-private. Users can import/export with SAF without broad storage access.
    val rootVaultDir: File by lazy { File(context.filesDir, "DocVault").apply { mkdirs() } }

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
    private val readableFiles = mutableMapOf<String, File>()
    private val unlockedProtectedCategories = mutableSetOf<String>()
    private val unlockedPasswords = mutableMapOf<String, CharArray>()
    private val protectedMetadata = mutableMapOf<String, MutableList<PdfDocument>>()
    private val protectedRoot: File by lazy { File(context.filesDir, "protected_vault").apply { mkdirs() } }
    private val selectedVaultTree: Uri? get() = context.getSharedPreferences("docvault_settings", 0).getString("vault_tree_uri", null)?.let(Uri::parse)

    init {
        // A force-stop/crash must not leave previously decrypted PDFs available on disk.
        File(context.cacheDir, "protected_pdf").deleteRecursively()
        File(context.cacheDir, "protected_verify").deleteRecursively()
        // Clear thumbnails created by older versions before protected thumbnails were memory-only.
        File(context.cacheDir, "docvault_thumbnails").deleteRecursively()
        ensureCategoryFolders()
        loadMetadata()
        for (categoryDirectory in protectedRoot.listFiles().orEmpty()) {
            if (!categoryDirectory.isDirectory) continue
            for (encrypted in categoryDirectory.listFiles().orEmpty().filter { it.name.endsWith(".dvindex") }) {
                File(indexDir, encrypted.name.removeSuffix(".dvindex") + ".json").delete()
            }
        }
        for (doc in synchronized(lock) { protectedMetadata.values.flatten().toList() }) {
            File(indexDir, "${doc.id}.json").delete()
        }
    }

    suspend fun syncInBackground(): Int = withContext(Dispatchers.IO) {
        syncWithFileSystem().also { rebuildMissingIndexes() }
    }

    suspend fun importFromSelectedVaultFolder(): Int = importFromSelectedTree()

    suspend fun exportToSelectedTree(): Int = withContext(Dispatchers.IO) {
        val uri = selectedVaultTree ?: error("Choose a vault folder first")
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Selected folder is unavailable")
        var exported = 0
        val records = synchronized(lock) { documentsList.filter { doc -> categoriesList.none { it.id == doc.categoryId && it.isPasswordProtected } }.map { it.copy(tags = it.tags.toMutableList()) } }
        for (doc in records) {
            val source = resolveVaultPath(doc.relativePath) ?: continue
            if (!source.isFile) continue
            val category = getCategoryById(doc.categoryId) ?: continue
            var directory = tree.findFile(category.folderName)?.takeIf { it.isDirectory } ?: tree.createDirectory(category.folderName) ?: continue
            val segments = doc.relativePath.split(File.separatorChar).drop(1).dropLast(1)
            for (segment in segments) directory = directory.findFile(segment)?.takeIf { it.isDirectory } ?: directory.createDirectory(segment) ?: break
            val destinationName = doc.filename
            var safeDestinationName = destinationName
            var suffix = 1
            while (directory.findFile(safeDestinationName) != null) {
                val stem = destinationName.substringBeforeLast('.', destinationName)
                safeDestinationName = "$stem (DocVault $suffix).pdf"
                suffix++
            }
            val destination = directory.createFile("application/pdf", safeDestinationName) ?: continue
            try {
                context.contentResolver.openOutputStream(destination.uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Unable to write exported PDF")
                exported++
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }
        exported
    }

    private fun selectedTreeDocument(relativePath: String): DocumentFile? {
        val uri = selectedVaultTree ?: return null
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (relativePath.isBlank() || File(relativePath).isAbsolute || relativePath.split('/', '\\').any { !isSafePathSegment(it) }) return null
        var current = tree
        for (segment in relativePath.split('/', '\\')) current = current.findFile(segment) ?: return null
        return current.takeIf { it.isFile }
    }

    private suspend fun importFromSelectedTree(): Int = withContext(Dispatchers.IO) {
        val uri = selectedVaultTree ?: return@withContext 0
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return@withContext 0
        val existing = synchronized(lock) { documentsList.map { it.relativePath }.toMutableSet() }
        var imported = 0

        fun documentAt(path: String): DocumentFile? {
            if (path.isBlank() || File(path).isAbsolute || path.split('/', '\\').any { !isSafePathSegment(it) }) return null
            var current = tree
            for (segment in path.split('/', '\\')) current = current.findFile(segment) ?: return null
            return current.takeIf { it.isFile }
        }

        // Preserve existing names/tags/categories on migration from the former public Documents/DocVault.
        val legacyMetadata = tree.findFile(".docvault_metadata.json")?.let { metadataFile ->
            try {
                context.contentResolver.openInputStream(metadataFile.uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
            } catch (e: Exception) { Log.w("DocumentRepository", "Could not read selected folder metadata", e); null }
        }
        if (legacyMetadata != null) {
            legacyMetadata.optJSONArray("categories")?.let { cats ->
                for (i in 0 until cats.length()) {
                    val importedCategory = Category.fromJsonObject(cats.getJSONObject(i))
                    if (!isSafeIdentifier(importedCategory.id) || !isSafePathSegment(importedCategory.folderName) || importedCategory.name.isBlank()) continue
                    synchronized(lock) {
                        if (categoriesList.none { it.id == importedCategory.id }) {
                            // Preserve protection flags; never silently downgrade a protected category.
                            categoriesList.add(importedCategory)
                        }
                    }
                }
            }
            legacyMetadata.optJSONArray("documents")?.let { docs ->
                for (i in 0 until docs.length()) {
                    val old = try { PdfDocument.fromJsonObject(docs.getJSONObject(i)) } catch (_: Exception) { continue }
                    if (!isSafeIdentifier(old.id) || !isSafeIdentifier(old.categoryId) || !isSafePathSegment(old.filename)) continue
                    val category = getCategoryById(old.categoryId) ?: continue
                    if (category.isPasswordProtected) continue
                    if (old.relativePath.startsWith(".protected/")) continue
                    val source = documentAt(old.relativePath) ?: continue
                    val target = resolveVaultPath(old.relativePath) ?: continue
                    if (target.exists() || old.relativePath in existing) continue
                    target.parentFile?.mkdirs()
                    try {
                        val input = context.contentResolver.openInputStream(source.uri) ?: continue
                        input.use { ins -> target.outputStream().use { out -> ins.copyTo(out) } }
                        val verified = PdfDocument(title = old.title, filename = old.filename, relativePath = old.relativePath, categoryId = category.id, categoryName = category.name, fileSize = target.length())
                        if (!indexDocumentQuick(verified, target)) { target.delete(); continue }
                        old.relativePath = target.relativeTo(rootVaultDir).path
                        old.pageCount = verified.pageCount
                        old.fileSize = target.length()
                        synchronized(lock) { documentsList.add(old); existing.add(old.relativePath) }
                        imported++
                    } catch (e: Exception) { target.delete(); Log.w("DocumentRepository", "Unable to migrate ${old.filename}", e) }
                }
            }
        }

        // Also import PDFs not represented in metadata; infer a category from the selected folder tree.
        fun visit(directory: DocumentFile, segments: List<String>) {
            for (child in directory.listFiles()) {
                val name = child.name?.takeIf { isSafePathSegment(it) } ?: continue
                if (child.isDirectory) { visit(child, segments + name); continue }
                if (!child.isFile || !name.endsWith(".pdf", ignoreCase = true)) continue
                val categoryMatch = synchronized(lock) {
                    segments.indices.firstNotNullOfOrNull { index ->
                        categoriesList.firstOrNull { it.folderName.equals(segments[index], true) || it.name.equals(segments[index], true) }
                            ?.let { index to it }
                    }
                } ?: continue
                val category = categoryMatch.second
                if (category.isPasswordProtected) continue
                val relative = (listOf(category.folderName) + segments.drop(categoryMatch.first + 1) + name).joinToString(File.separator)
                if (relative in existing) continue
                val target = resolveVaultPath(relative) ?: continue
                if (target.exists()) continue
                try {
                    target.parentFile?.mkdirs()
                    val input = context.contentResolver.openInputStream(child.uri) ?: continue
                    input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
                    val header = ByteArray(5); target.inputStream().use { it.read(header) }
                    if (!String(header).startsWith("%PDF")) { target.delete(); continue }
                    val doc = PdfDocument(title = FileUtils.cleanTitleFromFilename(name), filename = name, relativePath = relative, categoryId = category.id, categoryName = category.name, fileSize = target.length(), sourceUrl = "Imported from selected folder")
                    if (indexDocumentQuick(doc, target)) {
                        synchronized(lock) { documentsList.add(doc); existing.add(relative) }
                        imported++
                    } else target.delete()
                } catch (e: Exception) { target.delete(); Log.w("DocumentRepository", "Unable to import selected-folder PDF", e) }
            }
        }
        visit(tree, emptyList())
        if (imported > 0 && !saveMetadata()) error("Unable to save selected-folder imports")
        imported
    }

    private fun ensureCategoryFolders() {
        synchronized(lock) {
            if (categoriesList.isEmpty()) {
                categoriesList.addAll(Category.getDefaultCategories())
            }
            for (cat in categoriesList) {
                val folder = resolveVaultPath(cat.folderName) ?: continue
                if (!folder.exists()) folder.mkdirs()
            }
        }
    }

    fun loadMetadata() {
        synchronized(lock) {
            categoriesList.clear()
            documentsList.clear()
            protectedMetadata.clear()
            if (metaFile.exists()) {
                try {
                    val rootObj = JSONObject(metaFile.readText())
                    rootObj.optJSONArray("categories")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val category = Category.fromJsonObject(arr.getJSONObject(i))
                            if (isSafeIdentifier(category.id) && isSafePathSegment(category.folderName) && categoriesList.none { it.id == category.id }) categoriesList.add(category)
                        }
                    }
                    if (categoriesList.isEmpty()) categoriesList.addAll(Category.getDefaultCategories())
                    rootObj.optJSONArray("documents")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val doc = PdfDocument.fromJsonObject(arr.getJSONObject(i))
                            if (!isSafeIdentifier(doc.id) || !isSafeIdentifier(doc.categoryId) || !isSafePathSegment(doc.filename) || !doc.filename.endsWith(".pdf", ignoreCase = true)) continue
                            val category = categoriesList.firstOrNull { it.id == doc.categoryId } ?: continue
                            if (category.isPasswordProtected) {
                                if (!doc.relativePath.startsWith(".protected/") && resolveVaultPath(doc.relativePath) == null) continue
                                // Legacy versions left protected metadata in this file. Keep in memory
                                // temporarily so unlock can migrate it, but never write it back here.
                                protectedMetadata.getOrPut(category.id) { mutableListOf() }.add(doc)
                            } else {
                                val file = resolveVaultPath(doc.relativePath) ?: continue
                                if (file.isFile) documentsList.add(doc)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DocumentRepository", "Error reading vault metadata", e)
                }
            } else {
                categoriesList.addAll(Category.getDefaultCategories())
            }
        }
    }

    fun saveMetadata(): Boolean = synchronized(lock) {
        try {
            val rootObj = JSONObject()
            val categories = JSONArray().apply {
                categoriesList.forEach { category ->
                    val onDisk = if (category.isPasswordProtected) category.copy(name = "Protected category", folderName = category.id, icon = "🔒", colorHex = "#475569") else category
                    put(onDisk.toJsonObject())
                }
            }
            val documents = JSONArray().apply {
                documentsList.filter { doc -> categoriesList.none { it.id == doc.categoryId && it.isPasswordProtected } }
                    .forEach { put(it.toJsonObject()) }
            }
            rootObj.put("categories", categories)
            rootObj.put("documents", documents)
            writeAtomically(metaFile, rootObj.toString(2).toByteArray(Charsets.UTF_8))
            saveProtectedMetadata()
            val missing = protectedMetadata.any { (id, docs) -> docs.isNotEmpty() && unlockedPasswords[id] != null && !protectedMetadataFile(id).isFile }
            if (missing) error("Protected metadata was not persisted")
            true
        } catch (e: Exception) {
            Log.e("DocumentRepository", "Error saving vault metadata", e)
            false
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val parent = destination.parentFile ?: error("Metadata destination has no parent")
        if (!parent.exists() && !parent.mkdirs()) error("Unable to create metadata directory")
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (!temporary.renameTo(destination)) error("Unable to replace ${destination.name}")
            }
        } finally { temporary.delete() }
    }

    private fun protectedMetadataFile(categoryId: String) = File(File(protectedRoot, categoryId), "metadata.dvmeta")

    private fun saveProtectedMetadata() {
        synchronized(lock) {
            for ((categoryId, documents) in protectedMetadata) {
                val password = unlockedPasswords[categoryId]?.copyOf() ?: continue
                try {
                    val directory = File(protectedRoot, categoryId)
                    val saltFile = File(directory, "_salt")
                    if (!saltFile.isFile) continue
                    val salt = Base64.decode(saltFile.readText(), Base64.NO_WRAP)
                    val protectedCategory = categoriesList.firstOrNull { it.id == categoryId } ?: continue
                    val json = JSONObject().apply {
                        put("format", 2)
                        put("category", protectedCategory.toJsonObject())
                        put("documents", JSONArray().apply { documents.forEach { put(it.toJsonObject()) } })
                    }
                    val encrypted = EncryptedVaultStore.encryptBytes(json.toString().toByteArray(Charsets.UTF_8), password, salt)
                    writeAtomically(protectedMetadataFile(categoryId), encrypted)
                } finally { password.fill(0.toChar()) }
            }
        }
    }

    private fun loadLegacyProtectedMetadataFromSelectedTree(categoryId: String): MutableList<PdfDocument> {
        val uri = selectedVaultTree ?: return mutableListOf()
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return mutableListOf()
        val file = tree.findFile(".docvault_metadata.json") ?: return mutableListOf()
        val root = try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
        } catch (e: Exception) { Log.w("DocumentRepository", "Could not read legacy metadata", e); null } ?: return mutableListOf()
        root.optJSONArray("categories")?.let { categories ->
            for (i in 0 until categories.length()) {
                val legacy = try { Category.fromJsonObject(categories.getJSONObject(i)) } catch (_: Exception) { continue }
                if (legacy.id == categoryId && isSafePathSegment(legacy.folderName)) {
                    synchronized(lock) {
                        categoriesList.firstOrNull { it.id == categoryId }?.let { current ->
                            current.name = legacy.name
                            current.folderName = legacy.folderName
                            current.icon = legacy.icon
                            current.colorHex = legacy.colorHex
                            current.isHidden = legacy.isHidden
                        }
                    }
                    break
                }
            }
        }
        val records = root.optJSONArray("documents") ?: return mutableListOf()
        return buildList {
            for (i in 0 until records.length()) {
                val doc = try { PdfDocument.fromJsonObject(records.getJSONObject(i)) } catch (_: Exception) { continue }
                if (doc.categoryId != categoryId || !isSafeIdentifier(doc.id) || !isSafePathSegment(doc.filename)) continue
                if (doc.relativePath.startsWith(".protected/")) {
                    val encrypted = File(File(protectedRoot, categoryId), "${doc.id}.dvpdf")
                    if (encrypted.isFile) doc.relativePath = ".protected/$categoryId/${encrypted.name}"
                }
                add(doc)
            }
        }.toMutableList()
    }

    private fun loadProtectedMetadata(categoryId: String, password: CharArray): MutableList<PdfDocument> {
        val directory = File(protectedRoot, categoryId)
        val salt = Base64.decode(File(directory, "_salt").readText(), Base64.NO_WRAP)
        val plain = EncryptedVaultStore.decryptBytes(protectedMetadataFile(categoryId).readBytes(), password, salt)
        return try {
            val payload = String(plain, Charsets.UTF_8)
            val root = JSONObject(payload)
            if (root.optInt("format") >= 2) {
                val savedCategory = Category.fromJsonObject(root.getJSONObject("category"))
                synchronized(lock) {
                    categoriesList.firstOrNull { it.id == categoryId }?.let { current ->
                        current.name = savedCategory.name
                        current.folderName = savedCategory.folderName
                        current.icon = savedCategory.icon
                        current.colorHex = savedCategory.colorHex
                        current.isHidden = savedCategory.isHidden
                    }
                }
                val docs = root.getJSONArray("documents")
                MutableList(docs.length()) { i -> PdfDocument.fromJsonObject(docs.getJSONObject(i)) }
            } else {
                // Compatibility with an earlier in-development encrypted array format.
                val docs = JSONArray(payload)
                MutableList(docs.length()) { i -> PdfDocument.fromJsonObject(docs.getJSONObject(i)) }
            }
        } finally { plain.fill(0) }
    }

    private fun isSafePathSegment(value: String): Boolean = VaultPathPolicy.isSafeSegment(value)

    private fun isSafeIdentifier(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,100}"))

    private fun resolveVaultPath(relativePath: String): File? = VaultPathPolicy.resolve(rootVaultDir, relativePath)

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

                // Never register an existing password-protected category by scanning its disk folder.
                if (matchedCat?.isPasswordProtected == true) continue

                // If user created a new custom category folder externally, register it!
                if (matchedCat == null) {
                    matchedCat = addCategory(dirName, icon = "📁", colorHex = "#0D9488")
                }

                // Recursively walk this directory and all subdirectories
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && file.name.lowercase(Locale.ROOT).endsWith(".pdf") && resolveVaultPath(file.relativeTo(rootVaultDir).path) != null) {
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
                    if (changed) saveMetadata()
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
            return categoriesList.map { it.copy() }
        }
    }

    fun getCategoryById(id: String): Category? = synchronized(lock) {
        categoriesList.firstOrNull { it.id == id }
    }

    fun setCategoryHidden(categoryId: String, hidden: Boolean): Boolean {
        synchronized(lock) {
            val category = categoriesList.firstOrNull { it.id == categoryId } ?: return false
            val previous = category.isHidden
            category.isHidden = hidden
            if (!saveMetadata()) { category.isHidden = previous; return false }
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
            if (!saveMetadata()) {
                categoriesList.removeAt(target)
                categoriesList.add(index, item)
                return false
            }
            return true
        }
    }

    fun addCategory(name: String, icon: String = "📁", colorHex: String = "#2563EB"): Category {
        synchronized(lock) {
            require(name.isNotBlank()) { "Category name is required" }
            val safeFolderName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            require(isSafePathSegment(safeFolderName)) { "Category name must be a safe folder name" }
            require(categoriesList.none { it.name.equals(name.trim(), ignoreCase = true) }) { "A category with this name already exists" }
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
            val folder = resolveVaultPath(newCat.folderName) ?: error("Invalid category folder")
            if (!folder.exists() && !folder.mkdirs()) error("Unable to create category folder")
            if (!saveMetadata()) {
                categoriesList.remove(newCat)
                folder.delete()
                error("Unable to save category metadata")
            }
            return newCat
        }
    }

    fun renameCategory(categoryId: String, newName: String): Boolean {
        synchronized(lock) {
            val cat = categoriesList.firstOrNull { it.id == categoryId } ?: return false
            if (newName.isBlank() || categoriesList.any { it.id != categoryId && it.name.equals(newName.trim(), ignoreCase = true) }) return false
            val priorName = cat.name
            val priorDocNames = documentsList.filter { it.categoryId == categoryId }.associate { it.id to it.categoryName }
            cat.name = newName.trim()
            for (doc in documentsList) if (doc.categoryId == categoryId) doc.categoryName = cat.name
            if (!saveMetadata()) {
                cat.name = priorName
                for (doc in documentsList) priorDocNames[doc.id]?.let { doc.categoryName = it }
                return false
            }
            return true
        }
    }

    fun deleteCategory(categoryId: String): Boolean {
        synchronized(lock) {
            val category = categoriesList.firstOrNull { it.id == categoryId } ?: return false
            if (category.isSystem || category.isPasswordProtected) return false
            val other = categoriesList.firstOrNull { it.id == Category.DEFAULT_OTHER.id } ?: return false
            val sourceDir = resolveVaultPath(category.folderName) ?: return false
            val targetDir = resolveVaultPath(other.folderName) ?: return false
            if (!targetDir.exists() && !targetDir.mkdirs()) return false
            val moves = mutableListOf<Triple<PdfDocument, File, File>>()
            for (doc in documentsList.filter { it.categoryId == categoryId }) {
                val from = resolveVaultPath(doc.relativePath) ?: return false
                var to = File(targetDir, doc.filename)
                var n = 1
                while (to.exists() && from.canonicalFile != to.canonicalFile) {
                    to = File(targetDir, "${doc.filename.substringBeforeLast('.', doc.filename)} ($n).pdf")
                    n++
                }
                moves.add(Triple(doc, from, to))
            }
            val completed = mutableListOf<Triple<PdfDocument, File, File>>()
            for (move in moves) {
                if (move.second.canonicalFile != move.third.canonicalFile && !move.second.renameTo(move.third)) {
                    completed.asReversed().forEach { it.third.renameTo(it.second) }
                    return false
                }
                completed.add(move)
            }
            val snapshots = moves.map { (doc, _, to) -> doc to (doc.relativePath to doc.filename) }
            for ((doc, from, to) in moves) {
                doc.relativePath = to.relativeTo(rootVaultDir).path
                doc.filename = to.name
                doc.categoryId = other.id
                doc.categoryName = other.name
            }
            categoriesList.remove(category)
            if (!saveMetadata()) {
                categoriesList.add(category)
                snapshots.forEach { (doc, pair) -> doc.relativePath = pair.first; doc.filename = pair.second; doc.categoryId = categoryId; doc.categoryName = category.name }
                completed.asReversed().forEach { it.third.renameTo(it.second) }
                return false
            }
            // Only remove the now-empty category folder; never recursive-delete unknown files.
            if (sourceDir.isDirectory && sourceDir.list()?.isEmpty() == true) sourceDir.delete()
            return true
        }
    }

    // Document Management
    fun getAllDocuments(): List<PdfDocument> {
        synchronized(lock) {
            return (documentsList + protectedMetadata.values.flatten()).filter { isDocumentVisible(it) }.map { it.copy(tags = it.tags.toMutableList()) }
        }
    }

    fun getDocuments(
        categoryId: String? = null,
        query: String? = null,
        sortOrder: SortOrder = SortOrder.NEWEST
    ): List<PdfDocument> {
        synchronized(lock) {
            var list = (documentsList + protectedMetadata.values.flatten()).filter { isDocumentVisible(it) }.map { it.copy(tags = it.tags.toMutableList()) }

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

    fun getDocumentById(id: String): PdfDocument? = synchronized(lock) {
        val document = documentsList.firstOrNull { it.id == id && isDocumentVisible(it) }
            ?: protectedMetadata.values.flatten().firstOrNull { it.id == id && isDocumentVisible(it) }
        document?.copy(tags = document.tags.toMutableList())
    }

    fun isCategoryUnlocked(categoryId: String): Boolean = synchronized(lock) {
        !getCategoryById(categoryId).let { it?.isPasswordProtected == true } || unlockedProtectedCategories.contains(categoryId)
    }

    fun protectCategory(categoryId: String, password: CharArray): Boolean {
        val category = synchronized(lock) { categoriesList.firstOrNull { it.id == categoryId } } ?: return false
        if (category.isPasswordProtected || password.isEmpty()) return false
        val salt = EncryptedVaultStore.newSalt()
        val vaultDir = File(protectedRoot, categoryId)
        if (!vaultDir.exists() && !vaultDir.mkdirs()) return false
        val verifierPlain = File(cacheDir(), "${categoryId}_verify.bin")
        val verifier = File(vaultDir, "_verifier.dv")
        val saltFile = File(vaultDir, "_salt")
        val staged = mutableListOf<Pair<PdfDocument, File>>()
        val sourceByDoc = mutableListOf<Pair<PdfDocument, File>>()
        val stagedIndexes = mutableListOf<Pair<File, File>>()
        val originalDocs = synchronized(lock) { documentsList.filter { it.categoryId == categoryId }.map { it.copy(tags = it.tags.toMutableList()) } }
        try {
            verifierPlain.writeText("DocVault protected category")
            EncryptedVaultStore.encrypt(verifierPlain, verifier, password, salt)
            verifierPlain.delete()
            writeAtomically(saltFile, Base64.encodeToString(salt, Base64.NO_WRAP).toByteArray(Charsets.UTF_8))
            val docs = originalDocs
            for (doc in docs) {
                val legacyIndex = File(indexDir, "${doc.id}.json")
                if (legacyIndex.isFile) {
                    val indexEncrypted = File(vaultDir, "${doc.id}.dvindex")
                    writeAtomically(indexEncrypted, EncryptedVaultStore.encryptBytes(legacyIndex.readBytes(), password, salt))
                    stagedIndexes.add(legacyIndex to indexEncrypted)
                }
                val source = resolveVaultPath(doc.relativePath) ?: error("Unsafe path for ${doc.filename}")
                if (!source.isFile) error("Missing source PDF ${doc.filename}")
                val encrypted = File(vaultDir, "${doc.id}.dvpdf")
                EncryptedVaultStore.encrypt(source, encrypted, password, salt)
                staged.add(doc to encrypted)
                sourceByDoc.add(doc to source)
            }
            val newMetadata = docs.map { doc ->
                doc.copy(relativePath = ".protected/$categoryId/${doc.id}.dvpdf", tags = doc.tags.toMutableList())
            }.toMutableList()
            val metaPlain = JSONArray().apply { newMetadata.forEach { put(it.toJsonObject()) } }.toString().toByteArray(Charsets.UTF_8)
            writeAtomically(protectedMetadataFile(categoryId), EncryptedVaultStore.encryptBytes(metaPlain, password, salt))
            // Only delete plaintext after every ciphertext and encrypted metadata is durable.
            for ((_, source) in sourceByDoc) if (!source.delete()) error("Could not remove plaintext source ${source.name}")
            for ((plainIndex, _) in stagedIndexes) if (!plainIndex.delete()) error("Could not remove plaintext search index")
            synchronized(lock) {
                for (newDoc in newMetadata) {
                    val index = documentsList.indexOfFirst { it.id == newDoc.id }
                    if (index >= 0) documentsList[index] = newDoc
                }
                protectedMetadata[categoryId] = newMetadata
                documentsList.removeAll { it.categoryId == categoryId }
                category.isPasswordProtected = true
                    unlockedPasswords[categoryId] = password.copyOf()
                unlockedProtectedCategories.add(categoryId)
            }
            if (!saveMetadata()) error("Could not persist protected-category state")
            synchronized(lock) {
                protectedMetadata[categoryId]?.forEach { doc ->
                    readableFiles.remove(doc.id)?.delete()
                }
            }
            File(context.cacheDir, "protected_pdf/$categoryId").deleteRecursively()
            synchronized(lock) { protectedMetadata[categoryId]?.forEach { indexCache.remove(it.id) } }
            return true
        } catch (error: Exception) {
            Log.e("DocumentRepository", "Unable to protect category; restoring plaintext where possible", error)
            for ((doc, encrypted) in staged) {
                val source = sourceByDoc.firstOrNull { it.first.id == doc.id }?.second ?: continue
                if (!source.exists()) {
                    try { EncryptedVaultStore.decrypt(encrypted, source, password, salt) } catch (restoreError: Exception) {
                        Log.e("DocumentRepository", "Unable to restore ${doc.filename}", restoreError)
                    }
                }
                encrypted.delete()
            }
            for ((plainIndex, encryptedIndex) in stagedIndexes) {
                if (!plainIndex.exists() && encryptedIndex.exists()) {
                    try {
                        val bytes = EncryptedVaultStore.decryptBytes(encryptedIndex.readBytes(), password, salt)
                        writeAtomically(plainIndex, bytes); bytes.fill(0)
                    } catch (restoreError: Exception) { Log.e("DocumentRepository", "Unable to restore search index", restoreError) }
                }
                encryptedIndex.delete()
            }
            verifier.delete(); saltFile.delete(); protectedMetadataFile(categoryId).delete()
            synchronized(lock) {
                unlockedProtectedCategories.remove(categoryId)
                unlockedPasswords.remove(categoryId)?.fill(0.toChar())
                category.isPasswordProtected = false
                protectedMetadata.remove(categoryId)
                documentsList.removeAll { it.categoryId == categoryId }
                documentsList.addAll(originalDocs)
            }
            saveMetadata()
            return false
        } finally { verifierPlain.delete() }
    }

    fun unlockCategory(categoryId: String, password: CharArray): Boolean {
        val category = synchronized(lock) { categoriesList.firstOrNull { it.id == categoryId } } ?: return false
        if (!category.isPasswordProtected) return true
        val vaultDir = File(protectedRoot, categoryId)
        val saltFile = File(vaultDir, "_salt")
        val verifier = File(vaultDir, "_verifier.dv")
        if (!saltFile.isFile || !verifier.isFile) return false
        val salt = try { Base64.decode(saltFile.readText(), Base64.NO_WRAP) } catch (_: Exception) { return false }
        val plain = File(cacheDir(), "${categoryId}_verify.bin")
        return try {
            EncryptedVaultStore.decrypt(verifier, plain, password, salt)
            val valid = plain.readText() == "DocVault protected category"
            plain.delete()
            if (!valid) return false
            val fromEncryptedMetadata = protectedMetadataFile(categoryId).isFile
            val metadata = if (fromEncryptedMetadata) {
                loadProtectedMetadata(categoryId, password)
            } else {
                synchronized(lock) { protectedMetadata[categoryId]?.toMutableList() }?.takeIf { it.isNotEmpty() }
                    ?: loadLegacyProtectedMetadataFromSelectedTree(categoryId)
            }
            val hasLegacyPublicEntries = !fromEncryptedMetadata && metadata.isNotEmpty()
            if (hasLegacyPublicEntries) {
                for (doc in metadata) {
                    val priorCiphertext = File(vaultDir, "${doc.id}.dvpdf")
                    if (!priorCiphertext.isFile) {
                        val localSource = resolveVaultPath(doc.relativePath)?.takeIf { it.isFile }
                        if (localSource != null && !doc.relativePath.startsWith(".protected/")) {
                            EncryptedVaultStore.encrypt(localSource, priorCiphertext, password, salt)
                            if (!localSource.delete()) error("Could not remove legacy plaintext ${localSource.name}")
                        } else {
                            val externalSource = selectedTreeDocument(doc.relativePath) ?: error("Missing protected document ${doc.filename}")
                            val stagedSource = File(context.cacheDir, "legacy_${UUID.randomUUID()}.pdf")
                            try {
                                context.contentResolver.openInputStream(externalSource.uri)?.use { input -> stagedSource.outputStream().use { input.copyTo(it) } } ?: error("Unable to read ${doc.filename}")
                                EncryptedVaultStore.encrypt(stagedSource, priorCiphertext, password, salt)
                                // Leave the user-selected source copy untouched; remind them separately if it contains plaintext.
                            } finally { stagedSource.delete() }
                        }
                    }
                    doc.relativePath = ".protected/$categoryId/${priorCiphertext.name}"
                    doc.indexedText = ""
                }
                for (doc in metadata) {
                    val legacyIndex = File(indexDir, "${doc.id}.json")
                    if (!legacyIndex.isFile) continue
                    val encryptedIndex = File(vaultDir, "${doc.id}.dvindex")
                    if (!encryptedIndex.exists()) writeAtomically(encryptedIndex, EncryptedVaultStore.encryptBytes(legacyIndex.readBytes(), password, salt))
                    if (!legacyIndex.delete()) error("Unable to remove legacy plaintext index ${legacyIndex.name}")
                }
            }
            synchronized(lock) {
                unlockedProtectedCategories.add(categoryId)
                unlockedPasswords.remove(categoryId)?.fill(0.toChar())
                unlockedPasswords[categoryId] = password.copyOf()
                protectedMetadata[categoryId] = metadata
            }
            // Upgrade legacy plaintext metadata to encrypted form after successful unlock.
            if (!saveMetadata()) {
                lockCategory(categoryId)
                return false
            }
            true
        } catch (_: Exception) {
            plain.delete()
            false
        }
    }

    fun lockCategory(categoryId: String) {
        synchronized(lock) {
            unlockedProtectedCategories.remove(categoryId)
            unlockedPasswords.remove(categoryId)?.fill(0.toChar())
            protectedMetadata[categoryId]?.forEach { doc ->
                indexCache.remove(doc.id)
                readableFiles.remove(doc.id)?.delete()
            }
            if (protectedMetadataFile(categoryId).isFile) protectedMetadata.remove(categoryId)
        }
        File(context.cacheDir, "protected_pdf/$categoryId").deleteRecursively()
    }

    private fun isDocumentVisible(document: PdfDocument): Boolean {
        val category = categoriesList.firstOrNull { it.id == document.categoryId }
        return category?.isPasswordProtected != true || unlockedProtectedCategories.contains(category.id)
    }

    private fun cacheDir(): File = File(context.cacheDir, "protected_verify").apply { mkdirs() }

    suspend fun getReadableFile(document: PdfDocument): File? = withContext(Dispatchers.IO) {
        val category = synchronized(lock) { categoriesList.firstOrNull { it.id == document.categoryId } }
        if (category?.isPasswordProtected == true && !isCategoryUnlocked(category.id)) return@withContext null
        val resolved = getFile(document)
        resolved.takeIf { it.isFile && (category?.isPasswordProtected != true || it.extension.equals("pdf", true)) }
    }

    fun getFile(document: PdfDocument): File {
        val category = synchronized(lock) { categoriesList.firstOrNull { it.id == document.categoryId } }
        if (category?.isPasswordProtected == true) {
            val vaultDir = File(protectedRoot, category.id)
            val protectedRelative = document.relativePath.substringAfterLast('/')
            val encryptedName = protectedRelative.takeIf { isSafePathSegment(it) && it.endsWith(".dvpdf") } ?: "${document.id}.dvpdf"
            val encrypted = File(vaultDir, encryptedName)
            val unlockState = synchronized(lock) {
                if (category.id in unlockedProtectedCategories) unlockedPasswords[category.id]?.copyOf() else null
            } ?: return encrypted
            val plain = File(context.cacheDir, "protected_pdf/${category.id}/${document.id}.pdf")
            if (!encrypted.isFile) { unlockState.fill(0.toChar()); return File(context.cacheDir, "__missing_protected_file__") }
            if (!plain.exists() || plain.lastModified() < encrypted.lastModified()) {
                try {
                    val salt = Base64.decode(File(vaultDir, "_salt").readText(), Base64.NO_WRAP)
                    val password = unlockState
                    EncryptedVaultStore.decrypt(encrypted, plain, password, salt)
                    password.fill(0.toChar())
                } catch (_: Exception) { unlockState.fill(0.toChar()); plain.delete(); return encrypted }
            } else unlockState.fill(0.toChar())
            if (plain.exists() && synchronized(lock) { category.id in unlockedProtectedCategories }) synchronized(lock) { readableFiles[document.id] = plain }
            return if (plain.exists()) plain else encrypted
        }
        return resolveVaultPath(document.relativePath) ?: File(rootVaultDir, "__invalid_path__")
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
        val canonicalCategory = getCategoryById(category.id) ?: error("Category no longer exists")
        require(!canonicalCategory.isPasswordProtected || isCategoryUnlocked(canonicalCategory.id)) { "Unlock this protected category before adding documents" }
        require(isSafePathSegment(canonicalCategory.folderName)) { "Invalid category folder" }
        val baseCatDir = resolveVaultPath(canonicalCategory.folderName) ?: error("Invalid category folder")
        val cleanSub = subfolder.trim()
        require(cleanSub.isEmpty() || isSafePathSegment(cleanSub)) { "Subfolder must be a single safe folder name" }
        val targetDir = if (cleanSub.isNotEmpty()) resolveVaultPath("${canonicalCategory.folderName}/$cleanSub") ?: error("Invalid subfolder") else baseCatDir
        if (!targetDir.exists() && !targetDir.mkdirs()) error("Unable to create destination folder")
        require(targetDir.canonicalFile.toPath().startsWith(rootVaultDir.canonicalFile.toPath())) { "Destination is outside DocVault" }

        // PRESERVE ORIGINAL FILENAME (no UUID prefix!)
        var safeFilename = FileUtils.sanitizeFilename(originalFilename)
        require(isSafePathSegment(safeFilename) && safeFilename.lowercase(Locale.ROOT).endsWith(".pdf")) { "Invalid PDF filename" }
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

        val tempFile = File(targetDir, ".${UUID.randomUUID()}.importing")
        try {
            tempFile.outputStream().use { out -> inputStream.copyTo(out) }
            val verified = PdfDocument(title = "", filename = safeFilename, relativePath = "", categoryId = canonicalCategory.id, categoryName = canonicalCategory.name, fileSize = tempFile.length())
            if (!indexDocumentQuick(verified, tempFile)) throw IllegalArgumentException("The selected file is not a readable PDF")
            if (category.isPasswordProtected) {
                require(unlockedProtectedCategories.contains(category.id)) { "Unlock this protected category before adding documents" }
                val password = synchronized(lock) { unlockedPasswords[category.id]?.copyOf() }
                    ?: throw IllegalStateException("Unlock this protected category before adding documents")
                try {
                    val protectedDir = File(protectedRoot, canonicalCategory.id)
                    val salt = Base64.decode(File(protectedDir, "_salt").readText(), Base64.NO_WRAP)
                    val encrypted = File(protectedDir, "${UUID.randomUUID()}.dvpdf")
                    EncryptedVaultStore.encrypt(tempFile, encrypted, password, salt)
                    val fileSize = encrypted.length()
                    val docTitle = if (title.isNotBlank()) title else FileUtils.cleanTitleFromFilename(safeFilename)
                    val tags = (initialTags + listOfNotNull(cleanSub.takeIf { it.isNotBlank() })).distinct().toMutableList()
                    val doc = PdfDocument(title = docTitle, filename = safeFilename, relativePath = ".protected/${canonicalCategory.id}/${encrypted.name}", categoryId = canonicalCategory.id, categoryName = canonicalCategory.name, fileSize = fileSize, sourceUrl = sourceUrl, tags = tags, pageCount = verified.pageCount)
                    synchronized(lock) { protectedMetadata.getOrPut(canonicalCategory.id) { mutableListOf() }.add(doc) }
                    if (!saveMetadata()) { encrypted.delete(); synchronized(lock) { protectedMetadata[canonicalCategory.id]?.removeAll { it.id == doc.id } }; error("Unable to save protected document metadata") }
                    queueIndexing(doc)
                    return@withContext doc
                } finally { password.fill(0.toChar()) }
            }
            if (!tempFile.renameTo(destFile)) throw IllegalStateException("Unable to move imported PDF into the vault")
        } finally { tempFile.delete() }

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
            categoryId = canonicalCategory.id,
            categoryName = canonicalCategory.name,
            fileSize = fileSize,
            dateAdded = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
            tags = combinedTags
        )

        synchronized(lock) {
            documentsList.add(0, doc)
            if (!saveMetadata()) {
                documentsList.removeAll { it.id == doc.id }
                destFile.delete()
                error("Unable to save document metadata")
            }
        }
        queueIndexing(doc)
        doc
    }

    private suspend fun rebuildMissingIndexes(): Int = withContext(Dispatchers.IO) {
        var rebuilt = 0
        for (doc in getAllDocuments()) {
            val protected = getCategoryById(doc.categoryId)?.isPasswordProtected == true
            val indexFile = if (protected) File(protectedRoot, "${doc.categoryId}/${doc.id}.dvindex") else File(indexDir, "${doc.id}.json")
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

                val isProtected = synchronized(lock) { categoriesList.firstOrNull { it.id == doc.categoryId }?.isPasswordProtected == true }
                if (isProtected) {
                    val password = synchronized(lock) { unlockedPasswords[doc.categoryId]?.copyOf() } ?: return
                    try {
                        val vaultDir = File(protectedRoot, doc.categoryId)
                        val salt = Base64.decode(File(vaultDir, "_salt").readText(), Base64.NO_WRAP)
                        val target = File(vaultDir, "${doc.id}.dvindex")
                        writeAtomically(target, EncryptedVaultStore.encryptBytes(pageDataList.toString().toByteArray(Charsets.UTF_8), password, salt))
                    } finally { password.fill(0.toChar()) }
                } else {
                    writeAtomically(File(indexDir, "${doc.id}.json"), pageDataList.toString().toByteArray(Charsets.UTF_8))
                }
                synchronized(lock) { indexCache.remove(doc.id) }

                val protected = synchronized(lock) { categoriesList.firstOrNull { it.id == doc.categoryId }?.isPasswordProtected == true }
                doc.indexedText = if (protected) "" else fullTextSnippet.take(500).toString()
                doc.fileSize = file.length()
                doc.lastIndexedModified = file.lastModified()
                if (protected) saveMetadata()
            }
        } catch (e: Exception) {
            Log.e("DocumentRepository", "Failed to extract text from PDF: ${file.name}", e)
        }
    }

    // Tag Operations
    fun addTag(docId: String, tag: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: protectedMetadata.values.flatten().firstOrNull { it.id == docId } ?: return false
            val cleanTag = tag.trim().removePrefix("#")
            if (cleanTag.isNotBlank() && !doc.tags.contains(cleanTag)) {
                doc.tags.add(cleanTag)
                if (!saveMetadata()) { doc.tags.remove(cleanTag); return false }
                return true
            }
            return false
        }
    }

    fun removeTag(docId: String, tag: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: protectedMetadata.values.flatten().firstOrNull { it.id == docId } ?: return false
            val normalized = tag.trim().removePrefix("#")
            val removed = doc.tags.remove(normalized)
            if (removed && !saveMetadata()) { doc.tags.add(normalized); return false }
            return removed
        }
    }

    fun setTags(docId: String, newTags: List<String>): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == docId } ?: protectedMetadata.values.flatten().firstOrNull { it.id == docId } ?: return false
            val previous = doc.tags.toList()
            doc.tags.clear()
            doc.tags.addAll(newTags.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct())
            if (!saveMetadata()) { doc.tags.clear(); doc.tags.addAll(previous); return false }
            return true
        }
    }

    fun getAllUniqueTags(): List<String> {
        synchronized(lock) {
            return (documentsList + protectedMetadata.values.flatten().filter { isDocumentVisible(it) }).flatMap { it.tags }.distinct().sorted()
        }
    }

    fun updateCategory(docId: String, newCategory: Category): Boolean {
        val doc = synchronized(lock) {
            documentsList.firstOrNull { it.id == docId } ?: protectedMetadata.values.flatten().firstOrNull { it.id == docId }
        } ?: return false
        val oldCategory = synchronized(lock) { categoriesList.firstOrNull { it.id == doc.categoryId } } ?: return false
        val targetCategory = synchronized(lock) { categoriesList.firstOrNull { it.id == newCategory.id } } ?: return false
        if (targetCategory.isPasswordProtected) return false // Use the explicit protect flow to encrypt a category.
        val wasProtected = oldCategory.isPasswordProtected
        if (wasProtected && !isCategoryUnlocked(oldCategory.id)) return false
        val oldFile = getFile(doc)
        val targetDir = resolveVaultPath(targetCategory.folderName) ?: return false
        if (!targetDir.exists() && !targetDir.mkdirs()) return false
        var newFile = File(targetDir, doc.filename)
        var suffix = 1
        while (newFile.exists() && oldFile.canonicalFile != newFile.canonicalFile) {
            val base = doc.filename.substringBeforeLast('.', doc.filename)
            newFile = File(targetDir, "$base ($suffix).pdf")
            suffix++
        }
        if (!oldFile.isFile) return false
        if (oldFile.canonicalFile != newFile.canonicalFile && !oldFile.renameTo(newFile)) return false
        val oldPath = doc.relativePath
        val oldName = doc.filename
        val oldCatId = doc.categoryId
        val oldCatName = doc.categoryName
        val encryptedOriginal = if (wasProtected) {
            val name = doc.relativePath.substringAfterLast('/')
            File(File(protectedRoot, oldCategory.id), if (name.endsWith(".dvpdf")) name else "${doc.id}.dvpdf")
        } else null
        synchronized(lock) {
            doc.relativePath = newFile.relativeTo(rootVaultDir).path
            doc.filename = newFile.name
            doc.categoryId = targetCategory.id
            doc.categoryName = targetCategory.name
            if (wasProtected) {
                protectedMetadata[oldCategory.id]?.removeAll { it.id == doc.id }
                documentsList.add(doc)
            }
            if (!saveMetadata()) {
                if (wasProtected) documentsList.removeAll { it.id == doc.id }
                doc.relativePath = oldPath; doc.filename = oldName; doc.categoryId = oldCatId; doc.categoryName = oldCatName
                if (wasProtected) protectedMetadata.getOrPut(oldCategory.id) { mutableListOf() }.add(doc)
                if (oldFile.canonicalFile != newFile.canonicalFile) newFile.renameTo(oldFile)
                return false
            }
        }
        if (wasProtected) {
            encryptedOriginal?.delete()
            File(File(protectedRoot, oldCategory.id), "${doc.id}.dvindex").delete()
            File(context.cacheDir, "protected_pdf/${oldCategory.id}/${doc.id}.pdf").delete()
        }
        return true
    }

    fun updateTitle(id: String, newTitle: String): Boolean {
        synchronized(lock) {
            val doc = documentsList.firstOrNull { it.id == id } ?: protectedMetadata.values.flatten().firstOrNull { it.id == id } ?: return false
            val previous = doc.title
            doc.title = newTitle.trim()
            if (!saveMetadata()) { doc.title = previous; return false }
            return true
        }
    }

    fun deleteDocument(id: String): Boolean = synchronized(lock) {
        val doc = documentsList.firstOrNull { it.id == id } ?: protectedMetadata.values.flatten().firstOrNull { it.id == id } ?: return false
        val category = categoriesList.firstOrNull { it.id == doc.categoryId }
        val protected = category?.isPasswordProtected == true
        if (protected && !unlockedProtectedCategories.contains(category.id)) return false
        val encryptedName = doc.relativePath.substringAfterLast('/')
        val payload = if (protected) File(File(protectedRoot, category!!.id), if (encryptedName.endsWith(".dvpdf")) encryptedName else "${doc.id}.dvpdf") else resolveVaultPath(doc.relativePath)
        val plainIndex = File(indexDir, "${doc.id}.json")
        val protectedIndex = if (protected) File(protectedRoot, "${category!!.id}/${doc.id}.dvindex") else null
        val stagedDelete = mutableListOf<Pair<File, File>>()
        fun stage(file: File?): Boolean {
            if (file == null || !file.exists()) return true
            val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.delete")
            if (!file.renameTo(temp)) return false
            stagedDelete.add(file to temp)
            return true
        }
        if (!stage(payload) || !stage(plainIndex) || !stage(protectedIndex)) {
            stagedDelete.asReversed().forEach { (original, temp) -> temp.renameTo(original) }
            return false
        }
        documentsList.removeAll { it.id == id }
        val protectedList = protectedMetadata.values.firstOrNull { list -> list.any { it.id == id } }
        val protectedPosition = protectedList?.indexOfFirst { it.id == id } ?: -1
        protectedList?.removeAll { it.id == id }
        indexCache.remove(id)
        if (!saveMetadata()) {
            if (protected && protectedList != null) protectedList.add((protectedPosition.coerceAtLeast(0)).coerceAtMost(protectedList.size), doc) else documentsList.add(doc)
            stagedDelete.asReversed().forEach { (original, temp) -> temp.renameTo(original) }
            return false
        }
        stagedDelete.forEach { (_, temp) -> temp.delete() }
        if (protected) File(context.cacheDir, "protected_pdf/${category!!.id}/${doc.id}.pdf").delete()
        true
    }

    private fun loadCachedIndex(doc: PdfDocument): List<IndexedPage> {
        val category = synchronized(lock) { categoriesList.firstOrNull { it.id == doc.categoryId } }
        val protected = category?.isPasswordProtected == true
        val indexFile = if (protected) File(protectedRoot, "${category!!.id}/${doc.id}.dvindex") else File(indexDir, "${doc.id}.json")
        if (!indexFile.isFile) return emptyList()
        val modified = indexFile.lastModified()
        synchronized(lock) { indexCache[doc.id]?.takeIf { it.modified == modified }?.let { return it.pages } }
        return try {
            val payload = if (protected) {
                val password = synchronized(lock) { unlockedPasswords[category!!.id]?.copyOf() } ?: return emptyList()
                try {
                    val salt = Base64.decode(File(protectedRoot, "${category.id}/_salt").readText(), Base64.NO_WRAP)
                    val bytes = EncryptedVaultStore.decryptBytes(indexFile.readBytes(), password, salt)
                    try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
                } finally { password.fill(0.toChar()) }
            } else indexFile.readText()
            val jsonArray = JSONArray(payload)
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
                val highlightQuery = SearchQueryMatcher.matchingHighlight(q, page.lowerText) ?: continue
                val matchIndex = page.lowerText.indexOf(highlightQuery)
                if (matchIndex < 0) continue
                val start = maxOf(0, matchIndex - 180)
                val end = minOf(text.length, matchIndex + highlightQuery.length + 220)
                var snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
                if (start > 0) snippet = "…$snippet"
                if (end < text.length) snippet = "$snippet…"
                val phraseBonus = if (page.lowerText.contains(q)) 100 else 0
                ranked.add((terms.size * 20 + phraseBonus) to SearchResult(doc, page.number, snippet, highlightQuery))
            }
        }
        results.addAll(ranked.sortedByDescending { it.first }.map { it.second })
        results
    }
}
