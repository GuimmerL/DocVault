package com.docvault.app.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.Category
import com.docvault.app.model.PdfDocument
import com.docvault.app.repository.SortOrder
import com.docvault.app.ui.dialogs.CategoryMenuDialog
import com.docvault.app.ui.dialogs.EditTagsDialog
import com.docvault.app.ui.dialogs.ImportCategoryDialog
import com.docvault.app.ui.dialogs.ManageCategoriesDialog
import com.docvault.app.ui.dialogs.SettingsDialog
import com.docvault.app.ui.viewer.OfficialPdfViewerActivity
import com.docvault.app.ui.viewer.PdfViewerActivity
import com.docvault.app.utils.FileUtils
import com.docvault.app.utils.ShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {

    private lateinit var rvDocuments: RecyclerView
    private lateinit var adapter: DocumentAdapter
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvStats: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var chipGroupCategories: ChipGroup
    private lateinit var tvSelectedCategory: MaterialAutoCompleteTextView

    private var selectedCategoryId: String? = null
    private var currentQuery: String? = null
    private var currentSortOrder: SortOrder = SortOrder.NEWEST
    private var viewMode: DocumentAdapter.ViewMode = DocumentAdapter.ViewMode.LIST
    private var vaultSyncJob: kotlinx.coroutines.Job? = null
    private var syncInProgress = false

    private fun thumbnailColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 180).coerceIn(2, 4)
    }

    private fun applyLayoutForMode(mode: DocumentAdapter.ViewMode) {
        rvDocuments.layoutManager = if (mode == DocumentAdapter.ViewMode.THUMBNAIL) {
            GridLayoutManager(requireContext(), thumbnailColumns())
        } else {
            LinearLayoutManager(requireContext())
        }
    }

    private fun startBackgroundSync() {
        if (syncInProgress) return
        syncInProgress = true
        vaultSyncJob = viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                DocVaultApp.instance.repository.syncInBackground()
            }
            syncInProgress = false
            buildCategoryChips()
            loadDocuments()
            if (added > 0) Toast.makeText(requireContext(), "Vault synced ($added new found)", Toast.LENGTH_SHORT).show()
        }
    }

    // Mass Import Launcher via Storage Access Framework
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handleImportUris(uris)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_library, container, false)
        initViews(root)
        return root
    }

    override fun onResume() {
        super.onResume()
        buildCategoryChips()
        refreshCategoryDropdown()
        updateSelectedCategoryIndicator()
        loadDocuments()
        startBackgroundSync()
    }

    private fun initViews(root: View) {
        rvDocuments = root.findViewById(R.id.rvDocuments)
        layoutEmptyState = root.findViewById(R.id.layoutEmptyState)
        tvStats = root.findViewById(R.id.tvLibraryStats)
        etSearch = root.findViewById(R.id.etSearch)
        btnClearSearch = root.findViewById(R.id.btnClearSearch)
        chipGroupCategories = root.findViewById(R.id.chipGroupCategories)
        tvSelectedCategory = root.findViewById(R.id.tvSelectedCategory)
        tvSelectedCategory.setOnItemClickListener { _, _, position, _ ->
            val categories = listOf<Category?>(null) + DocVaultApp.instance.repository.getCategories().filter { !it.isHidden }
            selectedCategoryId = categories.getOrNull(position)?.id
            updateSelectedCategoryIndicator()
            loadDocuments()
        }
        refreshCategoryDropdown()

        val savedMode = requireContext().getSharedPreferences("docvault_settings", 0).getInt("view_mode", 0)
        viewMode = DocumentAdapter.ViewMode.values().getOrElse(savedMode) { DocumentAdapter.ViewMode.LIST }

        adapter = DocumentAdapter(
            onViewClick = { doc -> openViewer(doc) },
            onShareClick = { doc ->
                val file = DocVaultApp.instance.repository.getFile(doc)
                ShareHelper.shareDocument(requireContext(), doc, file)
            },
            onEditTagsClick = { doc -> showEditTagsDialog(doc) },
            onChangeCategoryClick = { doc -> showChangeCategoryDialog(doc) },
            onRenameClick = { doc -> showRenameDialog(doc) },
            onOpenExternalClick = { doc ->
                val file = DocVaultApp.instance.repository.getFile(doc)
                ShareHelper.openExternal(requireContext(), file)
            },
            onDeleteClick = { doc -> showDeleteConfirmation(doc) }
        )

        adapter.setViewMode(viewMode)
        root.findViewById<MaterialButton>(R.id.btnMenu).setOnClickListener {
            (activity as? com.docvault.app.MainActivity)?.openDrawer()
        }
        applyLayoutForMode(viewMode)
        rvDocuments.adapter = adapter

        root.findViewById<MaterialButton>(R.id.btnViewMode).setOnClickListener { button ->
            viewMode = when (viewMode) {
                DocumentAdapter.ViewMode.LIST -> DocumentAdapter.ViewMode.COMPACT
                DocumentAdapter.ViewMode.COMPACT -> DocumentAdapter.ViewMode.THUMBNAIL
                DocumentAdapter.ViewMode.THUMBNAIL -> DocumentAdapter.ViewMode.LIST
            }
            requireContext().getSharedPreferences("docvault_settings", 0).edit().putInt("view_mode", viewMode.ordinal).apply()
            adapter.setViewMode(viewMode)
            applyLayoutForMode(viewMode)
            button.contentDescription = when (viewMode) {
                DocumentAdapter.ViewMode.LIST -> "List view"
                DocumentAdapter.ViewMode.COMPACT -> "Compact list view"
                DocumentAdapter.ViewMode.THUMBNAIL -> "Thumbnail view"
            }
            loadDocuments()
        }

        // Rescan / Sync Vault Button
        root.findViewById<MaterialButton>(R.id.btnSyncVault).setOnClickListener {
            startBackgroundSync()
            Toast.makeText(requireContext(), "Vault scan started", Toast.LENGTH_SHORT).show()
        }

        // Search Bar Listener
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                currentQuery = if (text.isNotBlank()) text else null
                btnClearSearch.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
                loadDocuments()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        // Sort Button
        root.findViewById<MaterialButton>(R.id.btnSort).setOnClickListener { v ->
            showSortMenu(v)
        }

        // Import Actions
        val openPicker = {
            importLauncher.launch(arrayOf("application/pdf"))
        }
        root.findViewById<ExtendedFloatingActionButton>(R.id.fabImport).setOnClickListener { openPicker() }
        root.findViewById<MaterialButton>(R.id.btnHeaderImport).setOnClickListener { openPicker() }
        root.findViewById<MaterialButton>(R.id.btnEmptyImport).setOnClickListener { openPicker() }

        buildCategoryChips()
    }

    private fun refreshCategoryDropdown() {
        if (!::tvSelectedCategory.isInitialized) return
        val categories = DocVaultApp.instance.repository.getCategories().filter { !it.isHidden }
        val labels = listOf("All documents") + categories.map { "${it.icon}  ${it.name}" }
        tvSelectedCategory.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels))
        val selectedIndex = selectedCategoryId?.let { id -> categories.indexOfFirst { it.id == id } + 1 } ?: 0
        tvSelectedCategory.setText(labels.getOrElse(selectedIndex) { labels.first() }, false)
    }

    private fun buildCategoryChips() {
        chipGroupCategories.removeAllViews()
        val context = requireContext()
        val repo = DocVaultApp.instance.repository
        val categories = repo.getCategories()
        if (selectedCategoryId != null && categories.firstOrNull { it.id == selectedCategoryId }?.isHidden == true) {
            selectedCategoryId = null
        }

        // "All" Chip
        val allChip = Chip(context).apply {
            text = "All"
            isCheckable = true
            isChecked = (selectedCategoryId == null)
            setOnClickListener {
                selectedCategoryId = null
                loadDocuments()
            }
        }
        chipGroupCategories.addView(allChip)

        // Category Chips
        for (cat in categories.filter { !it.isHidden }) {
            val catChip = Chip(context).apply {
                text = "${cat.icon}  ${cat.name}"
                isCheckable = true
                isChecked = (selectedCategoryId == cat.id)
                setOnClickListener {
                    selectedCategoryId = cat.id
                    loadDocuments()
                }
                setOnLongClickListener {
                    ManageCategoriesDialog.show(context) {
                        buildCategoryChips()
                        loadDocuments()
                    }
                    true
                }
            }
            chipGroupCategories.addView(catChip)
        }

        // "+ Add Category" Chip
        val addCatChip = Chip(context).apply {
            text = "+ Category"
            setOnClickListener {
                ManageCategoriesDialog.showAddCategoryDialog(context) {
                    buildCategoryChips()
                    loadDocuments()
                }
            }
        }
        chipGroupCategories.addView(addCatChip)
    }

    fun selectCategory(categoryId: String?) {
        selectedCategoryId = categoryId
        updateSelectedCategoryIndicator()
        if (::rvDocuments.isInitialized) loadDocuments()
    }

    private fun updateSelectedCategoryIndicator() {
        if (!::tvSelectedCategory.isInitialized) return
        refreshCategoryDropdown()
    }

    fun getViewMode(): DocumentAdapter.ViewMode = viewMode

    fun setViewMode(mode: DocumentAdapter.ViewMode) {
        viewMode = mode
        requireContext().getSharedPreferences("docvault_settings", 0).edit().putInt("view_mode", mode.ordinal).apply()
        if (::adapter.isInitialized) {
            adapter.setViewMode(mode)
            applyLayoutForMode(mode)
            loadDocuments()
        }
    }

    private fun loadDocuments() {
        val repo = DocVaultApp.instance.repository
        val docs = repo.getDocuments(selectedCategoryId, currentQuery, currentSortOrder)
        adapter.submitList(docs)

        val totalDocs = repo.getAllDocuments()
        val totalBytes = totalDocs.sumOf { it.fileSize }
        tvStats.text = "${totalDocs.size} documents • ${FileUtils.formatFileSize(totalBytes)} offline"

        if (docs.isEmpty()) {
            rvDocuments.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
        } else {
            rvDocuments.visibility = View.VISIBLE
            layoutEmptyState.visibility = View.GONE
        }
    }

    private fun openViewer(doc: PdfDocument) {
        val useExperimental = requireContext().getSharedPreferences("docvault_settings", 0)
            .getBoolean("use_experimental_pdf_viewer", false)
        val viewerClass = if (useExperimental) OfficialPdfViewerActivity::class.java else PdfViewerActivity::class.java
        val intent = Intent(requireContext(), viewerClass).apply {
            if (useExperimental) putExtra(OfficialPdfViewerActivity.EXTRA_DOC_ID, doc.id)
            else putExtra(PdfViewerActivity.EXTRA_DOC_ID, doc.id)
        }
        startActivity(intent)
    }

    private fun showEditTagsDialog(doc: PdfDocument) {
        EditTagsDialog.show(requireContext(), doc) { newTags ->
            DocVaultApp.instance.repository.setTags(doc.id, newTags)
            loadDocuments()
            Toast.makeText(requireContext(), "Tags updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImportUris(uris: List<Uri>) {
        val context = requireContext()
        ImportCategoryDialog.show(context, uris.size) { selectedCategory, subfolder ->
            Toast.makeText(context, "Importing ${uris.size} document(s)…", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                var importedCount = 0
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        try {
                            val contentResolver = context.contentResolver
                            var originalName = "imported_document.pdf"

                            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (nameIndex >= 0) {
                                        originalName = cursor.getString(nameIndex)
                                    }
                                }
                            }

                            val title = FileUtils.cleanTitleFromFilename(originalName)
                            contentResolver.openInputStream(uri)?.use { stream ->
                                DocVaultApp.instance.repository.addDocument(
                                    title = title,
                                    category = selectedCategory,
                                    inputStream = stream,
                                    originalFilename = originalName, // Exact original filename preserved
                                    sourceUrl = "Imported from device",
                                    initialTags = if (subfolder.isNotBlank()) listOf(subfolder) else emptyList(),
                                    subfolder = subfolder
                                )
                                importedCount++
                            }
                        } catch (e: Exception) {
                            // Continue with other files
                        }
                    }
                }

                Toast.makeText(context, "Successfully imported $importedCount document(s)", Toast.LENGTH_SHORT).show()
                loadDocuments()
            }
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.sort_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            currentSortOrder = when (item.itemId) {
                R.id.sort_newest -> SortOrder.NEWEST
                R.id.sort_oldest -> SortOrder.OLDEST
                R.id.sort_title_az -> SortOrder.TITLE_AZ
                R.id.sort_title_za -> SortOrder.TITLE_ZA
                R.id.sort_size_largest -> SortOrder.SIZE_LARGEST
                R.id.sort_size_smallest -> SortOrder.SIZE_SMALLEST
                else -> SortOrder.NEWEST
            }
            loadDocuments()
            true
        }
        popup.show()
    }

    private fun showChangeCategoryDialog(doc: PdfDocument) {
        val categories = DocVaultApp.instance.repository.getCategories()
        val items = categories.map { "${it.icon}  ${it.name}" }.toTypedArray()
        val currentIndex = categories.indexOfFirst { it.id == doc.categoryId }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Move to Category: ${doc.title}")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                val newCat = categories[which]
                DocVaultApp.instance.repository.updateCategory(doc.id, newCat)
                loadDocuments()
                dialog.dismiss()
                Toast.makeText(requireContext(), "Moved to ${newCat.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(doc: PdfDocument) {
        val input = TextInputEditText(requireContext()).apply {
            setText(doc.title)
            setSelection(text?.length ?: 0)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Document")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text?.toString()?.trim()
                if (!newTitle.isNullOrBlank()) {
                    DocVaultApp.instance.repository.updateTitle(doc.id, newTitle)
                    loadDocuments()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(doc: PdfDocument) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Document?")
            .setMessage("Are you sure you want to delete '${doc.title}'? The offline PDF file will be permanently removed.")
            .setPositiveButton("Delete") { _, _ ->
                DocVaultApp.instance.repository.deleteDocument(doc.id)
                loadDocuments()
                Toast.makeText(requireContext(), "Document deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
