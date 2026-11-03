package com.docvault.app.ui.viewer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.PdfDocument
import com.docvault.app.ui.dialogs.EditTagsDialog
import com.docvault.app.utils.PdfHighlightHelper
import com.docvault.app.utils.ShareHelper
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.util.Locale

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvPageIndicator: TextView
    private lateinit var rvPages: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutInDocSearch: LinearLayout
    private lateinit var etInDocSearch: EditText
    private lateinit var tvInDocMatchCount: TextView
    private lateinit var tvInDocSnippetContext: TextView

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentDocument: PdfDocument? = null
    private var currentFile: File? = null
    private var pageAdapter: PdfPageAdapter? = null

    // In-document search state
    private val searchMatchPages = mutableListOf<Int>()
    private val pageSnippets = mutableMapOf<Int, String>()
    private var currentMatchIndex = -1
    private var initialSearchQuery: String? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        initViews()

        val docId = intent.getStringExtra(EXTRA_DOC_ID)
        val initialPage = intent.getIntExtra(EXTRA_INITIAL_PAGE, 1)
        initialSearchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY)

        if (docId != null) {
            currentDocument = DocVaultApp.instance.repository.getDocumentById(docId)
            currentDocument?.let { doc ->
                tvTitle.text = doc.title
                lifecycleScope.launch {
                    currentFile = DocVaultApp.instance.repository.getReadableFile(doc)
                    val file = currentFile
                    if (file == null || !file.exists()) {
                        Toast.makeText(this@PdfViewerActivity, "Unlock the category or select the document again", Toast.LENGTH_SHORT).show()
                        finish()
                    } else openPdfRenderer(initialPage)
                }
            }
        }

        if (currentDocument == null) {
            Toast.makeText(this, "Document file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvViewerTitle)
        tvPageIndicator = findViewById(R.id.tvViewerPageIndicator)
        rvPages = findViewById(R.id.rvPdfPages)
        progressBar = findViewById(R.id.progressLoadingPdf)
        layoutInDocSearch = findViewById(R.id.layoutInDocSearch)
        etInDocSearch = findViewById(R.id.etInDocSearch)
        tvInDocMatchCount = findViewById(R.id.tvInDocMatchCount)
        tvInDocSnippetContext = findViewById(R.id.tvInDocSnippetContext)

        findViewById<ImageView>(R.id.btnViewerBack).setOnClickListener {
            finish()
        }

        findViewById<ImageView>(R.id.btnViewerShare).setOnClickListener {
            currentDocument?.let { doc ->
                currentFile?.let { file ->
                    ShareHelper.shareDocument(this, doc, file)
                }
            }
        }

        findViewById<ImageView>(R.id.btnViewerToc).setOnClickListener {
            showTableOfContents()
        }

        findViewById<ImageView>(R.id.btnViewerMore).setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            menu.menu.add("Edit tags")
            menu.setOnMenuItemClickListener {
                currentDocument?.let { doc ->
                    EditTagsDialog.show(this, doc) { newTags ->
                        if (DocVaultApp.instance.repository.setTags(doc.id, newTags)) {
                            doc.tags = newTags.toMutableList()
                            Toast.makeText(this, "Tags saved", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            menu.show()
        }

        // Jump to Page Interface (both via toolbar icon and tapping the indicator)
        findViewById<ImageView>(R.id.btnViewerJumpPage).setOnClickListener {
            showJumpToPageDialog()
        }
        tvPageIndicator.setOnClickListener {
            showJumpToPageDialog()
        }

        findViewById<ImageView>(R.id.btnViewerSearch).setOnClickListener {
            if (layoutInDocSearch.visibility == View.VISIBLE) {
                layoutInDocSearch.visibility = View.GONE
                tvInDocSnippetContext.visibility = View.GONE
                pageAdapter?.setSearchKeyword(null)
            } else {
                layoutInDocSearch.visibility = View.VISIBLE
                etInDocSearch.requestFocus()
                if (etInDocSearch.text.isNotBlank()) {
                    performInDocSearch(etInDocSearch.text.toString(), null)
                }
            }
        }

        findViewById<ImageView>(R.id.btnInDocClose).setOnClickListener {
            layoutInDocSearch.visibility = View.GONE
            tvInDocSnippetContext.visibility = View.GONE
            etInDocSearch.setText("")
            searchMatchPages.clear()
            pageSnippets.clear()
            tvInDocMatchCount.text = ""
            pageAdapter?.setSearchKeyword(null)
        }

        findViewById<ImageView>(R.id.btnInDocNext).setOnClickListener {
            navigateSearchMatch(1)
        }

        findViewById<ImageView>(R.id.btnInDocPrev).setOnClickListener {
            navigateSearchMatch(-1)
        }

        etInDocSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                performInDocSearch(s?.toString().orEmpty(), null)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showTableOfContents() {
        val file = currentFile ?: return
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val result = mutableListOf<Pair<String, Int>>()
                try {
                    PDDocument.load(file).use { document ->
                        val outline = document.documentCatalog.documentOutline
                        fun walk(parent: PDOutlineItem, depth: Int) {
                            var item = parent.firstChild
                            while (item != null) {
                                val destination = when {
                                    item.destination is PDPageDestination -> item.destination as PDPageDestination
                                    item.action is PDActionGoTo && (item.action as PDActionGoTo).destination is PDPageDestination ->
                                        (item.action as PDActionGoTo).destination as PDPageDestination
                                    else -> null
                                }
                                val page = destination?.page?.let { document.pages.indexOf(it) + 1 } ?: -1
                                if (page > 0) result.add("${"  ".repeat(depth)}${item.title}" to page)
                                walk(item, depth + 1)
                                item = item.nextSibling
                            }
                        }
                        var top = outline?.firstChild
                        while (top != null) {
                            walk(top, 0)
                            top = top.nextSibling
                        }
                    }
                } catch (_: Exception) {
                    // Some PDFs contain malformed outline destinations.
                }
                result
            }
            if (entries.isEmpty()) {
                Toast.makeText(this@PdfViewerActivity, "This PDF has no table of contents", Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@PdfViewerActivity)
                .setTitle("Table of contents")
                .setItems(entries.map { "${it.first}  •  Page ${it.second}" }.toTypedArray()) { _, which ->
                    rvPages.smoothScrollToPosition(entries[which].second - 1)
                    tvPageIndicator.text = "Page ${entries[which].second} of ${pdfRenderer?.pageCount ?: entries[which].second}"
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showJumpToPageDialog() {
        val maxPage = pdfRenderer?.pageCount ?: 1
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - $maxPage"
            val currentPos = (rvPages.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
            setText((currentPos + 1).toString())
            selectAll()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Go to Page")
            .setMessage("Enter page number (1 to $maxPage):")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val num = input.text.toString().trim().toIntOrNull()
                if (num != null && num in 1..maxPage) {
                    rvPages.scrollToPosition(num - 1)
                    tvPageIndicator.text = "Page $num of $maxPage"
                } else {
                    Toast.makeText(this, "Please enter a valid page between 1 and $maxPage", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPdfRenderer(targetInitialPage: Int) {
        val file = currentFile ?: return
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    pdfRenderer = PdfRenderer(fileDescriptor!!)
                }

                val renderer = pdfRenderer ?: return@launch
                val count = renderer.pageCount
                tvPageIndicator.text = "Page 1 of $count"

                val layoutManager = LinearLayoutManager(this@PdfViewerActivity)
                rvPages.layoutManager = layoutManager
                pageAdapter = PdfPageAdapter(renderer, file, count, lifecycleScope)
                rvPages.adapter = pageAdapter

                rvPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val firstVisible = layoutManager.findFirstVisibleItemPosition()
                        if (firstVisible != RecyclerView.NO_POSITION) {
                            tvPageIndicator.text = "Page ${firstVisible + 1} of $count"
                        }
                    }
                })

                if (targetInitialPage > 1 && targetInitialPage <= count) {
                    rvPages.scrollToPosition(targetInitialPage - 1)
                }

                if (!initialSearchQuery.isNullOrBlank()) {
                    layoutInDocSearch.visibility = View.VISIBLE
                    etInDocSearch.setText(initialSearchQuery)
                    performInDocSearch(initialSearchQuery!!, targetInitialPage)
                }

                progressBar.visibility = View.GONE
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@PdfViewerActivity, "Error opening PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performInDocSearch(query: String, requestedPage: Int?) {
        val q = query.trim()
        searchJob?.cancel()
        searchMatchPages.clear()
        pageSnippets.clear()
        currentMatchIndex = -1

        if (q.isBlank()) {
            tvInDocMatchCount.text = ""
            tvInDocSnippetContext.visibility = View.GONE
            pageAdapter?.setSearchKeyword(null)
            return
        }

        pageAdapter?.setSearchKeyword(q)

        val doc = currentDocument ?: return
        val file = currentFile ?: return

        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            val lowerQ = q.lowercase(Locale.ROOT)
            val indexFile = File(filesDir, "search_index/${doc.id}.json")
            val foundPages = mutableListOf<Int>()
            val extractedSnippets = mutableMapOf<Int, String>()

            if (indexFile.exists()) {
                try {
                    val array = JSONArray(indexFile.readText())
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val pageNum = obj.optInt("page", i + 1)
                        val text = obj.optString("text", "")
                        val lowerText = text.lowercase(Locale.ROOT)
                        val matchIndex = lowerText.indexOf(lowerQ)
                        if (matchIndex >= 0) {
                            foundPages.add(pageNum)
                            val start = maxOf(0, matchIndex - 140)
                            val end = minOf(text.length, matchIndex + q.length + 180)
                            var snip = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
                            if (start > 0) snip = "…$snip"
                            if (end < text.length) snip = "$snip…"
                            extractedSnippets[pageNum] = snip
                        }
                    }
                } catch (e: Exception) {
                    // Ignore index parse error
                }
            }

            // Direct document scan fallback using PDFBox to extract pages & rich context
            if (foundPages.isEmpty() && file.exists()) {
                try {
                    PDDocument.load(file).use { pdDoc ->
                        val stripper = PDFTextStripper()
                        for (p in 1..pdDoc.numberOfPages) {
                            stripper.startPage = p
                            stripper.endPage = p
                            val pageText = stripper.getText(pdDoc) ?: ""
                            val lowerPageText = pageText.lowercase(Locale.ROOT)
                            val matchIndex = lowerPageText.indexOf(lowerQ)
                            if (matchIndex >= 0) {
                                foundPages.add(p)
                                val start = maxOf(0, matchIndex - 140)
                                val end = minOf(pageText.length, matchIndex + q.length + 180)
                                var snip = pageText.substring(start, end).replace(Regex("\\s+"), " ").trim()
                                if (start > 0) snip = "…$snip"
                                if (end < pageText.length) snip = "$snip…"
                                extractedSnippets[p] = snip
                            }
                        }
                    }
                } catch (e: Exception) {
                    // fallback
                }
            }

            withContext(Dispatchers.Main) {
                searchMatchPages.clear()
                searchMatchPages.addAll(foundPages)
                pageSnippets.clear()
                pageSnippets.putAll(extractedSnippets)

                if (searchMatchPages.isNotEmpty()) {
                    if (requestedPage != null && searchMatchPages.contains(requestedPage)) {
                        currentMatchIndex = searchMatchPages.indexOf(requestedPage)
                    } else {
                        currentMatchIndex = 0
                        rvPages.smoothScrollToPosition(searchMatchPages[0] - 1)
                    }

                    updateSearchMatchUi(q)
                } else {
                    tvInDocMatchCount.text = "0 matches"
                    tvInDocSnippetContext.visibility = View.GONE
                }
            }
        }
    }

    private fun navigateSearchMatch(direction: Int) {
        if (searchMatchPages.isEmpty()) return
        currentMatchIndex += direction
        if (currentMatchIndex >= searchMatchPages.size) {
            currentMatchIndex = 0
        } else if (currentMatchIndex < 0) {
            currentMatchIndex = searchMatchPages.size - 1
        }

        val targetPage = searchMatchPages[currentMatchIndex]
        rvPages.smoothScrollToPosition(targetPage - 1)
        updateSearchMatchUi(etInDocSearch.text.toString().trim())
    }

    private fun updateSearchMatchUi(keyword: String) {
        if (currentMatchIndex !in searchMatchPages.indices) return
        val pageNum = searchMatchPages[currentMatchIndex]
        tvInDocMatchCount.text = "${currentMatchIndex + 1} of ${searchMatchPages.size} pages"

        val snippet = pageSnippets[pageNum]
        if (!snippet.isNullOrBlank()) {
            val spannable = SpannableString("Page $pageNum: $snippet")
            val lowerSnippet = spannable.toString().lowercase(Locale.ROOT)
            val lowerKw = keyword.lowercase(Locale.ROOT)

            var idx = lowerSnippet.indexOf(lowerKw)
            while (idx >= 0 && lowerKw.isNotBlank()) {
                val end = idx + lowerKw.length
                spannable.setSpan(
                    BackgroundColorSpan(0xFFFFEB3B.toInt()),
                    idx,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    idx,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = lowerSnippet.indexOf(lowerKw, end)
            }

            tvInDocSnippetContext.text = spannable
            tvInDocSnippetContext.visibility = View.VISIBLE
        } else {
            tvInDocSnippetContext.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            // Ignore close exceptions
        }
    }

    companion object {
        const val EXTRA_DOC_ID = "extra_doc_id"
        const val EXTRA_INITIAL_PAGE = "extra_initial_page"
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
    }
}
