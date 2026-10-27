package com.docvault.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.docvault.app.R
import com.docvault.app.utils.PdfHighlightHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfPageAdapter(
    private val renderer: PdfRenderer,
    private val pdfFile: File,
    private val pageCount: Int,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    private var currentSearchKeyword: String? = null
    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun setSearchKeyword(keyword: String?) {
        val clean = keyword?.trim()
        val newKw = if (clean.isNullOrBlank()) null else clean
        if (currentSearchKeyword != newKw) {
            currentSearchKeyword = newKw
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position, currentSearchKeyword)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPage: ZoomablePdfImageView = itemView.findViewById(R.id.ivPdfPage)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressPage)
        private val tvFooter: TextView = itemView.findViewById(R.id.tvPageFooter)
        private var renderJob: Job? = null
        private var currentBitmap: Bitmap? = null

        fun bind(pageIndex: Int, keyword: String?) {
            renderJob?.cancel()
            progressBar.visibility = View.VISIBLE
            ivPage.setImageDrawable(null)
            tvFooter.text = "- Page ${pageIndex + 1} of $pageCount -"
            val cacheKey = "$pageIndex|${keyword.orEmpty().lowercase()}"
            bitmapCache.get(cacheKey)?.let { cached ->
                currentBitmap = cached
                ivPage.setImageBitmap(cached)
                progressBar.visibility = View.GONE
                return
            }

            renderJob = scope.launch {
                val bitmap = renderPageBitmap(pageIndex, keyword)
                if (bitmap != null) {
                    bitmapCache.put(cacheKey, bitmap)
                    currentBitmap = bitmap
                    ivPage.setImageBitmap(bitmap)
                    progressBar.visibility = View.GONE
                }
            }
        }

        private suspend fun renderPageBitmap(pageIndex: Int, keyword: String?): Bitmap? = withContext(Dispatchers.IO) {
            try {
                var width = 0
                var height = 0
                var renderedBitmap: Bitmap? = null

                synchronized(renderer) {
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null
                    val page = renderer.openPage(pageIndex)
                    
                    // Render at high resolution (approx 2x standard display points)
                    width = page.width * 2
                    height = page.height * 2
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // If search keyword is active, highlight the found text directly on the rendered page
                    if (!keyword.isNullOrBlank()) {
                        val matches = PdfHighlightHelper.findMatchesOnPage(pdfFile, pageIndex + 1, keyword)
                        if (matches.isNotEmpty()) {
                            val scaleX = width.toFloat() / page.width.toFloat()
                            val scaleY = height.toFloat() / page.height.toFloat()

                            val canvas = Canvas(bitmap)
                            val highlightPaint = Paint().apply {
                                color = 0x88FFEB3B.toInt() // Translucent highlighter yellow
                                style = Paint.Style.FILL
                                isAntiAlias = true
                            }
                            val borderPaint = Paint().apply {
                                color = 0xFFFF8F00.toInt() // Amber accent outline
                                style = Paint.Style.STROKE
                                strokeWidth = 3f
                                isAntiAlias = true
                            }

                            for (rect in matches) {
                                val scaledRect = RectF(
                                    rect.left * scaleX - 3f,
                                    rect.top * scaleY - 3f,
                                    rect.right * scaleX + 3f,
                                    rect.bottom * scaleY + 3f
                                )
                                canvas.drawRoundRect(scaledRect, 6f, 6f, highlightPaint)
                                canvas.drawRoundRect(scaledRect, 6f, 6f, borderPaint)
                            }
                        }
                    }

                    page.close()
                    renderedBitmap = bitmap
                }

                renderedBitmap
            } catch (e: Exception) {
                null
            }
        }

        fun recycle() {
            renderJob?.cancel()
            ivPage.setImageDrawable(null)
            // Bitmaps are owned by the LRU cache; do not recycle them while cached.
            currentBitmap = null
        }
    }
}
