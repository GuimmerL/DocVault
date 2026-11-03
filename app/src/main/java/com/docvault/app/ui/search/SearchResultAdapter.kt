package com.docvault.app.ui.search

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.SearchResult
import java.util.Locale

class SearchResultAdapter(
    private val onResultClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchResultAdapter.ResultViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCategory: TextView = itemView.findViewById(R.id.tvResultCategory)
        private val tvPage: TextView = itemView.findViewById(R.id.tvResultPage)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvResultDocTitle)
        private val tvSnippet: TextView = itemView.findViewById(R.id.tvResultSnippet)

        fun bind(result: SearchResult) {
            val doc = result.document
            val repo = DocVaultApp.instance.repository
            val cat = repo.getCategoryById(doc.categoryId)

            val catColor = try {
                Color.parseColor(cat?.colorHex ?: "#2563EB")
            } catch (e: Exception) {
                Color.parseColor("#2563EB")
            }
            val catIcon = cat?.icon ?: "📁"
            tvCategory.text = "$catIcon  ${doc.categoryName.uppercase()}"

            val badgeBg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.argb(35, Color.red(catColor), Color.green(catColor), Color.blue(catColor)))
                setStroke(2, catColor)
            }
            tvCategory.background = badgeBg
            tvCategory.setTextColor(catColor)

            tvPage.text = "Page ${result.pageNumber}"
            tvTitle.text = doc.title

            // Highlight search query in snippet
            val snippet = result.snippet
            val spannable = SpannableString(snippet)
            val q = result.matchWord.lowercase(Locale.ROOT)
            val lowerSnippet = snippet.lowercase(Locale.ROOT)

            var startIndex = lowerSnippet.indexOf(q)
            while (startIndex >= 0 && q.isNotBlank()) {
                val endIndex = startIndex + q.length
                spannable.setSpan(
                    BackgroundColorSpan(0xFFFFEB3B.toInt()), // Bright yellow highlight
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(0xFF111318.toInt()),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = lowerSnippet.indexOf(q, endIndex)
            }
            tvSnippet.text = spannable

            itemView.setOnClickListener {
                onResultClick(result)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
                oldItem.document.id == newItem.document.id && oldItem.pageNumber == newItem.pageNumber

            override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
                oldItem == newItem
        }
    }
}
