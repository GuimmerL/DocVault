package com.docvault.app.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.ui.viewer.PdfViewerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var tvStats: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rvResults: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySub: TextView

    private lateinit var adapter: SearchResultAdapter
    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_search, container, false)
        initViews(root)
        return root
    }

    private fun initViews(root: View) {
        etSearch = root.findViewById(R.id.etDeepSearch)
        btnClear = root.findViewById(R.id.btnClearDeepSearch)
        tvStats = root.findViewById(R.id.tvSearchStats)
        progressBar = root.findViewById(R.id.progressSearching)
        rvResults = root.findViewById(R.id.rvSearchResults)
        layoutEmptyState = root.findViewById(R.id.layoutSearchEmptyState)
        tvEmptyTitle = root.findViewById(R.id.tvSearchEmptyTitle)
        tvEmptySub = root.findViewById(R.id.tvSearchEmptySub)

        adapter = SearchResultAdapter { result ->
            val intent = Intent(requireContext(), PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_DOC_ID, result.document.id)
                putExtra(PdfViewerActivity.EXTRA_INITIAL_PAGE, result.pageNumber)
                putExtra(PdfViewerActivity.EXTRA_SEARCH_QUERY, result.matchWord)
            }
            startActivity(intent)
        }

        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = adapter

        btnClear.setOnClickListener {
            etSearch.setText("")
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                btnClear.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE

                searchJob?.cancel()
                if (query.isBlank()) {
                    searchJob?.cancel()
                    adapter.submitList(emptyList())
                    tvStats.visibility = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                    tvEmptyTitle.text = "Find inside any PDF"
                    tvEmptySub.text = "Type part numbers, error codes, wiring terms, or specifications to find the exact pages across all your offline files."
                    progressBar.visibility = View.GONE
                } else {
                    searchJob = lifecycleScope.launch {
                        delay(300) // Debounce typing
                        performSearch(query)
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private suspend fun performSearch(query: String) {
        progressBar.visibility = View.VISIBLE
        layoutEmptyState.visibility = View.GONE
        tvStats.visibility = View.GONE

        val results = DocVaultApp.instance.repository.searchFullText(query)
        if (query != etSearch.text?.toString()?.trim()) return
        progressBar.visibility = View.GONE
        adapter.submitList(results)

        if (results.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            tvEmptyTitle.text = "No matches found"
            tvEmptySub.text = "No offline documents or pages contain the term '$query'."
            tvStats.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            tvStats.visibility = View.VISIBLE
            val docCount = results.map { it.document.id }.distinct().size
            val matchWord = if (results.size == 1) "match" else "matches"
            val docWord = if (docCount == 1) "document" else "documents"
            tvStats.text = "Found ${results.size} $matchWord across $docCount $docWord"
        }
    }
}
