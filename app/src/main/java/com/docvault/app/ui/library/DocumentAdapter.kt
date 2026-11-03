package com.docvault.app.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.PdfDocument

class DocumentAdapter(
    private val onViewClick: (PdfDocument) -> Unit,
    private val onShareClick: (PdfDocument) -> Unit,
    private val onEditTagsClick: (PdfDocument) -> Unit,
    private val onChangeCategoryClick: (PdfDocument) -> Unit,
    private val onRenameClick: (PdfDocument) -> Unit,
    private val onOpenExternalClick: (PdfDocument) -> Unit,
    private val onDeleteClick: (PdfDocument) -> Unit
) : ListAdapter<PdfDocument, DocumentAdapter.DocumentViewHolder>(DiffCallback) {

    private val thumbnailScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    enum class ViewMode { LIST, COMPACT, THUMBNAIL }
    private var viewMode = ViewMode.LIST

    fun clearSensitiveThumbnails() {
        thumbnailScope.coroutineContext.cancelChildren()
        for (index in 0 until itemCount) {
            val holder = (recyclerViewForSensitiveClear?.findViewHolderForAdapterPosition(index) as? DocumentViewHolder)
            holder?.clearThumbnail()
        }
        notifyItemRangeChanged(0, itemCount)
    }

    private var recyclerViewForSensitiveClear: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerViewForSensitiveClear = recyclerView
    }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (recyclerViewForSensitiveClear === recyclerView) recyclerViewForSensitiveClear = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setViewMode(mode: ViewMode) {
        viewMode = mode
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_document_card, parent, false)
        return DocumentViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DocumentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.ivDocumentThumbnail)
        private val tvCategoryBadge: TextView = itemView.findViewById(R.id.tvCategoryBadge)
        private val btnOptions: ImageView = itemView.findViewById(R.id.btnOptions)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvDocumentTitle)
        private val tvFilename: TextView = itemView.findViewById(R.id.tvOriginalFilename)
        private val tvPageCount: TextView = itemView.findViewById(R.id.tvPageCount)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvDateAdded: TextView = itemView.findViewById(R.id.tvDateAdded)
        private val chipGroupTags: ChipGroup = itemView.findViewById(R.id.chipGroupCardTags)
        private val btnView: MaterialButton = itemView.findViewById(R.id.btnView)
        private val btnShare: MaterialButton = itemView.findViewById(R.id.btnShare)

        fun clearThumbnail() {
            (thumbnail.drawable as? BitmapDrawable)?.bitmap?.eraseColor(Color.TRANSPARENT)
            thumbnail.setImageResource(R.drawable.ic_library)
        }

        fun bind(doc: PdfDocument) {
            val context = itemView.context
            val repo = DocVaultApp.instance.repository
            val cat = repo.getCategoryById(doc.categoryId)

            val thumbnailMode = viewMode == ViewMode.THUMBNAIL
            val compactMode = viewMode == ViewMode.COMPACT
            thumbnail.visibility = if (compactMode) View.GONE else View.VISIBLE
            thumbnail.layoutParams = (thumbnail.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (thumbnailMode) ViewGroup.LayoutParams.MATCH_PARENT else dp(context, 72)
                height = if (thumbnailMode) dp(context, 120) else dp(context, 96)
                gravity = if (thumbnailMode) Gravity.CENTER else Gravity.END
            }
            thumbnail.scaleType = if (thumbnailMode) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
            thumbnail.requestLayout()
            chipGroupTags.visibility = if (compactMode || thumbnailMode) View.GONE else View.VISIBLE
            btnView.visibility = if (compactMode || thumbnailMode) View.GONE else View.VISIBLE
            btnShare.visibility = if (compactMode || thumbnailMode) View.GONE else View.VISIBLE
            if (!compactMode) {
                thumbnail.setImageResource(R.drawable.ic_library)
                val protectedDocument = repo.getCategoryById(doc.categoryId)?.isPasswordProtected == true
                val cacheFile = if (protectedDocument) null else File(itemView.context.cacheDir, "docvault_thumbnails/${doc.id}_${doc.lastIndexedModified}_${doc.fileSize}.png")
                thumbnailScope.launch {
                    val file = repo.getReadableFile(doc) ?: return@launch
                    renderThumbnail(file, cacheFile)?.let { bitmap ->
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION && getItem(bindingAdapterPosition).id == doc.id) {
                            thumbnail.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            tvTitle.textSize = if (compactMode || thumbnailMode) 14f else 16f
            tvFilename.visibility = if (compactMode || thumbnailMode) View.GONE else View.VISIBLE
            tvDateAdded.visibility = if (compactMode || thumbnailMode) View.GONE else View.VISIBLE
            tvPageCount.visibility = if (thumbnailMode) View.GONE else View.VISIBLE
            tvFileSize.visibility = if (thumbnailMode) View.GONE else View.VISIBLE

            // Category badge styling
            val catColor = try {
                Color.parseColor(cat?.colorHex ?: "#2563EB")
            } catch (e: Exception) {
                Color.parseColor("#2563EB")
            }
            val catIcon = cat?.icon ?: "📁"
            tvCategoryBadge.text = "$catIcon  ${doc.categoryName.uppercase()}"

            val badgeBg = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.argb(35, Color.red(catColor), Color.green(catColor), Color.blue(catColor)))
                setStroke(2, catColor)
            }
            tvCategoryBadge.background = badgeBg
            tvCategoryBadge.setTextColor(catColor)

            tvTitle.text = doc.title
            tvFilename.text = doc.filename
            tvPageCount.text = if (doc.pageCount > 0) "${doc.pageCount} pages" else "PDF Document"
            tvFileSize.text = doc.formattedFileSize
            tvDateAdded.text = doc.formattedDate

            // Populate tags
            chipGroupTags.removeAllViews()
            for (tag in doc.tags) {
                val chip = Chip(context).apply {
                    text = "#$tag"
                    textSize = 11f
                    contentDescription = "Edit tag $tag for ${doc.title}"
                    setOnClickListener { onEditTagsClick(doc) }
                }
                chipGroupTags.addView(chip)
            }

            // Always add a quick "+ Tag" chip
            val addTagChip = Chip(context).apply {
                text = "+ Tag"
                textSize = 10f
                contentDescription = "Add tag to ${doc.title}"
                setOnClickListener { onEditTagsClick(doc) }
            }
            chipGroupTags.addView(addTagChip)

            itemView.setOnClickListener { onViewClick(doc) }
            btnView.setOnClickListener { onViewClick(doc) }
            btnShare.setOnClickListener { onShareClick(doc) }

            btnOptions.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menuInflater.inflate(R.menu.document_item_menu, popup.menu)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit_tags -> {
                            onEditTagsClick(doc)
                            true
                        }
                        R.id.action_change_category -> {
                            onChangeCategoryClick(doc)
                            true
                        }
                        R.id.action_rename -> {
                            onRenameClick(doc)
                            true
                        }
                        R.id.action_open_external -> {
                            onOpenExternalClick(doc)
                            true
                        }
                        R.id.action_delete -> {
                            onDeleteClick(doc)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    private suspend fun renderThumbnail(file: File, cacheFile: File?): Bitmap? = withContext(Dispatchers.IO) {
        if (cacheFile?.exists() == true) return@withContext BitmapFactory.decodeFile(cacheFile.absolutePath)
        if (!file.exists()) return@withContext null
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return@withContext null
                    renderer.openPage(0).use { page ->
                        val width = 240
                        val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            cacheFile?.let { cached ->
                                cached.parentFile?.mkdirs()
                                cached.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<PdfDocument>() {
            override fun areItemsTheSame(oldItem: PdfDocument, newItem: PdfDocument): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: PdfDocument, newItem: PdfDocument): Boolean {
                return oldItem.copy(tags = oldItem.tags.toMutableList()) == newItem.copy(tags = newItem.tags.toMutableList())
            }
        }
    }
}
