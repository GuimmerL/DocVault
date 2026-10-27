package com.docvault.app.ui.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.android.material.button.MaterialButton
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.docvault.app.DocVaultApp
import com.docvault.app.R
import com.docvault.app.model.Category
import com.docvault.app.model.PdfDocument
import com.docvault.app.ui.dialogs.CategorySelectDialog
import com.docvault.app.ui.viewer.OfficialPdfViewerActivity
import com.docvault.app.ui.viewer.PdfViewerActivity
import com.docvault.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class BrowserFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var etUrl: EditText
    private lateinit var btnClearUrl: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnForward: ImageView
    private lateinit var btnReload: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnFavorite: MaterialButton
    private lateinit var progressBar: ProgressBar

    private val favoritePrefs = "browser_favorites"
    private val favoriteUrlsKey = "urls" 
    private val favoriteTitlesKey = "titles" 
    private fun favoriteUrls(): MutableSet<String> = requireContext().getSharedPreferences(favoritePrefs, 0).getStringSet(favoriteUrlsKey, emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun isFavorite(url: String): Boolean = favoriteUrls().contains(url) 
    private fun updateFavoriteButton(url: String?) { if (::btnFavorite.isInitialized) btnFavorite.text = if (url != null && isFavorite(url)) "★" else "☆" }

    private val homeUrl = "https://www.google.com"
    private var activeDownloadJob: Job? = null
    private var downloadDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_browser, container, false)
        initViews(root)
        setupWebView()
        return root
    }

    private fun initViews(root: View) {
        webView = root.findViewById(R.id.webView)
        etUrl = root.findViewById(R.id.etUrl)
        btnClearUrl = root.findViewById(R.id.btnClearUrl)
        btnBack = root.findViewById(R.id.btnBrowserBack)
        btnForward = root.findViewById(R.id.btnBrowserForward)
        btnReload = root.findViewById(R.id.btnBrowserReload)
        btnHome = root.findViewById(R.id.btnBrowserHome)
        btnFavorite = root.findViewById(R.id.btnBrowserFavorite)
        progressBar = root.findViewById(R.id.progressBar)

        btnFavorite.setOnClickListener { toggleFavorite() }
        btnFavorite.setOnLongClickListener { showFavorites(); true }
        updateFavoriteButton(webView.url)

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }

        btnReload.setOnClickListener {
            webView.reload()
        }

        btnHome.setOnClickListener {
            loadUrl(homeUrl)
        }

        btnClearUrl.setOnClickListener {
            etUrl.setText("")
        }

        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = etUrl.text.toString().trim()
                if (input.isNotBlank()) {
                    navigateTo(input)
                }
                true
            } else {
                false
            }
        }

        // Browser favorites and bookmark chips
        root.findViewById<Chip>(R.id.chipFavorites).setOnClickListener { showFavorites() }
        root.findViewById<Chip>(R.id.chipBookmarkManuals).setOnClickListener {
            navigateTo("https://www.manualslib.com")
        }
        root.findViewById<Chip>(R.id.chipBookmarkGoogle).setOnClickListener {
            navigateTo("https://www.google.com")
        }
        root.findViewById<Chip>(R.id.chipBookmarkTestPdf).setOnClickListener {
            navigateTo("https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf")
        }
    }

    private fun toggleFavorite() {
        val url = webView.url ?: return
        if (url.startsWith("about:")) return
        val prefs = requireContext().getSharedPreferences(favoritePrefs, 0)
        val favorites = favoriteUrls()
        if (favorites.contains(url)) {
            favorites.remove(url)
            Toast.makeText(requireContext(), "Favorite removed", Toast.LENGTH_SHORT).show()
        } else {
            favorites.add(url)
            Toast.makeText(requireContext(), "Favorite added", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet(favoriteUrlsKey, favorites).apply()
        updateFavoriteButton(url)
    }

    private fun showFavorites() {
        val favorites = favoriteUrls().toList()
        if (favorites.isEmpty()) {
            Toast.makeText(requireContext(), "No favorites saved", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Browser favorites")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()
        favorites.forEach { url ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val label = TextView(requireContext()).apply { text = url; textSize = 14f; setTextColor(android.graphics.Color.DKGRAY); setPadding(0, 10, 8, 10) }
            val delete = MaterialButton(requireContext()).apply { text = "Delete"; textSize = 12f; setOnClickListener {
                val updated = favoriteUrls().apply { remove(url) }
                requireContext().getSharedPreferences(favoritePrefs, 0).edit().putStringSet(favoriteUrlsKey, updated).apply()
                dialog.dismiss(); showFavorites(); updateFavoriteButton(webView.url)
            } }
            label.setOnClickListener { dialog.dismiss(); navigateTo(url) }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(delete, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(row)
        }
        dialog.show()
    }

    private fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true) -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=" + Uri.encode(input)
        }
        loadUrl(url)
    }

    private fun loadUrl(url: String) {
        if (isPdfUrl(url)) {
            promptAndDownloadPdf(url, null, "application/pdf", 0L)
        } else {
            webView.loadUrl(url)
        }
    }

    private fun isPdfUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val clean = lower.substringBefore('?').substringBefore('#')
        return clean.endsWith(".pdf") ||
                lower.contains(".pdf?") ||
                lower.contains("=pdf") ||
                lower.contains("/pdf/") ||
                lower.endsWith("/pdf") ||
                lower.contains("documentid=") ||
                lower.contains("/download?") ||
                lower.contains("/download/")
    }

    /**
     * Unsticks the webpage after download interception.
     * Clears disabled flags, spinners, and busy overlays that some websites
     * add during download clicks.
     */
    private fun unstickWebPage() {
        if (!::webView.isInitialized) return
        webView.post {
            try {
                webView.evaluateJavascript(
                    """
                    (function() {
                        window.dispatchEvent(new Event('focus'));
                        document.querySelectorAll('.loading, .spinner, .disabled, [aria-busy="true"], [data-busy="true"]').forEach(function(el) {
                            el.classList.remove('loading', 'spinner', 'disabled');
                            el.removeAttribute('aria-busy');
                            el.removeAttribute('data-busy');
                        });
                        document.querySelectorAll('button[disabled], a[disabled], [disabled]').forEach(function(el) {
                            el.removeAttribute('disabled');
                            el.style.pointerEvents = 'auto';
                        });
                        if (document.activeElement && document.activeElement.blur) {
                            document.activeElement.blur();
                        }
                    })();
                    """.trimIndent(), null
                )
            } catch (ignored: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Enable multiple windows so target="_blank" and window.open on certain sites don't unload the main page!
        settings.setSupportMultipleWindows(true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
            }

            // Handles target="_blank" and window.open downloads safely without clobbering the main page
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val tempWebView = WebView(requireContext()).apply {
                    this.settings.javaScriptEnabled = true
                    this.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                            val targetUrl = req?.url?.toString() ?: return false
                            if (isPdfUrl(targetUrl)) {
                                promptAndDownloadPdf(targetUrl, null, "application/pdf", 0L)
                                unstickWebPage()
                                return true
                            }
                            loadUrl(targetUrl)
                            return true
                        }
                    }
                    this.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                        promptAndDownloadPdf(url, contentDisposition, mimetype, contentLength)
                        unstickWebPage()
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = tempWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isPdfUrl(url)) {
                    promptAndDownloadPdf(url, null, "application/pdf", 0L)
                    unstickWebPage()
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    etUrl.setText(it)
                    btnClearUrl.visibility = View.VISIBLE
                    updateFavoriteButton(it)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let {
                    etUrl.setText(it)
                }
                unstickWebPage()
            }
        }

        webView.setDownloadListener(object : DownloadListener {
            override fun onDownloadStart(
                url: String?,
                userAgent: String?,
                contentDisposition: String?,
                mimetype: String?,
                contentLength: Long
            ) {
                if (url != null) {
                    webView.stopLoading()
                    progressBar.visibility = View.GONE
                    unstickWebPage()

                    // If main webview was diverted to the download URL or a blank page, navigate back
                    if (webView.canGoBack() && (webView.url == null || webView.url == "about:blank" || isPdfUrl(webView.url ?: ""))) {
                        webView.goBack()
                    }

                    promptAndDownloadPdf(url, contentDisposition, mimetype, contentLength)
                }
            }
        })

        loadUrl(homeUrl)
    }

    private fun promptAndDownloadPdf(
        url: String,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        val suggestedFilename = URLUtil.guessFileName(url, contentDisposition, mimetype).let {
            if (it.isNotBlank() && it != "downloadfile.bin") it
            else FileUtils.extractFilenameFromUrl(url)
        }
        val suggestedTitle = FileUtils.cleanTitleFromFilename(suggestedFilename)

        CategorySelectDialog.show(
            context = requireContext(),
            suggestedTitle = suggestedTitle,
            suggestedFilename = suggestedFilename,
            fileSizeBytes = contentLength,
            onConfirm = { finalTitle, selectedCategory, tags, subfolder ->
                startPdfDownload(url, finalTitle, selectedCategory, suggestedFilename, tags, contentLength, subfolder)
            }
        )
    }

    private fun startPdfDownload(
        url: String,
        title: String,
        category: Category,
        filename: String,
        tags: List<String>,
        estimatedSize: Long,
        subfolder: String = ""
    ) {
        val context = context ?: return

        // Create and show dedicated Download Progress Dialog with Cancel action
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_download_progress, null)
        val tvProgressFilename = dialogView.findViewById<TextView>(R.id.tvDownloadProgressFilename)
        val progressBarDownload = dialogView.findViewById<ProgressBar>(R.id.progressBarDownload)
        val tvDownloadStatus = dialogView.findViewById<TextView>(R.id.tvDownloadStatus)
        val tvDownloadPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)

        tvProgressFilename.text = "$title ($filename)"
        tvDownloadStatus.text = "Connecting to server…"
        tvDownloadPercent.text = ""

        if (estimatedSize > 0) {
            progressBarDownload.isIndeterminate = false
            progressBarDownload.max = 100
            progressBarDownload.progress = 0
        } else {
            progressBarDownload.isIndeterminate = true
        }

        downloadDialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { dialog, _ ->
                activeDownloadJob?.cancel()
                dialog.dismiss()
                unstickWebPage()
                Toast.makeText(context, "Download canceled", Toast.LENGTH_SHORT).show()
            }
            .create()

        downloadDialog?.show()

        activeDownloadJob = lifecycleScope.launch {
            var downloadedDoc: PdfDocument? = null
            var errorMessage: String? = null
            val userAgent = webView.settings.userAgentString
            val referer = webView.url ?: url
            val tempFile = File(context.cacheDir, "temp_download_${System.currentTimeMillis()}.tmp")

            withContext(Dispatchers.IO) {
                var currentUrl = url
                var redirectCount = 0
                var conn: HttpURLConnection? = null

                try {
                    while (redirectCount < 8 && isActive) {
                        val targetUrl = URL(currentUrl)
                        conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 20000
                            readTimeout = 60000
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept", "application/pdf,application/octet-stream,*/*")
                            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                            setRequestProperty("Connection", "keep-alive")

                            val cookies = CookieManager.getInstance().getCookie(currentUrl)
                            if (!cookies.isNullOrEmpty()) {
                                setRequestProperty("Cookie", cookies)
                            }
                            if (!referer.isNullOrEmpty()) {
                                setRequestProperty("Referer", referer)
                            }
                        }

                        conn.connect()
                        val code = conn.responseCode

                        if (code in 300..399) {
                            val location = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (!location.isNullOrBlank()) {
                                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                                    location
                                } else {
                                    URL(targetUrl, location).toString()
                                }
                                redirectCount++
                                continue
                            }
                        }
                        break
                    }

                    val finalConn = conn ?: throw IOException("Could not establish connection to server")
                    val responseCode = finalConn.responseCode
                    if (responseCode !in 200..299) {
                        throw IOException("Server returned HTTP $responseCode (${finalConn.responseMessage})")
                    }

                    val totalLength = finalConn.contentLengthLong
                    var bytesRead = 0L

                    withContext(Dispatchers.Main) {
                        if (totalLength > 0) {
                            progressBarDownload.isIndeterminate = false
                            progressBarDownload.max = 100
                        }
                    }

                    finalConn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(16384)
                            var len: Int
                            var lastUpdate = System.currentTimeMillis()

                            while (input.read(buffer).also { len = it } != -1) {
                                if (!isActive) {
                                    throw IOException("Download canceled by user")
                                }
                                output.write(buffer, 0, len)
                                bytesRead += len

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 100) {
                                    lastUpdate = now
                                    val currentBytes = bytesRead
                                    withContext(Dispatchers.Main) {
                                        if (totalLength > 0) {
                                            val pct = ((currentBytes * 100) / totalLength).toInt()
                                            progressBarDownload.progress = pct
                                            tvDownloadPercent.text = "$pct%"
                                            tvDownloadStatus.text = "Downloaded: ${FileUtils.formatFileSize(currentBytes)} / ${FileUtils.formatFileSize(totalLength)}"
                                        } else {
                                            tvDownloadStatus.text = "Downloaded: ${FileUtils.formatFileSize(currentBytes)}"
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    if (tempFile.length() < 10) {
                        throw IOException("Downloaded file is empty (0 bytes).")
                    }

                    val header = ByteArray(5)
                    FileInputStream(tempFile).use { it.read(header) }
                    val headerStr = String(header)
                    if (!headerStr.startsWith("%PDF")) {
                        throw IOException("The file returned by the server is not a valid PDF (received web page/HTML).")
                    }

                    withContext(Dispatchers.Main) {
                        tvDownloadStatus.text = "Saving to vault & indexing…"
                        progressBarDownload.isIndeterminate = true
                    }

                    // Save verified PDF into DocVault directory
                    FileInputStream(tempFile).use { stream ->
                        downloadedDoc = DocVaultApp.instance.repository.addDocument(
                            title = title,
                            category = category,
                            inputStream = stream,
                            originalFilename = filename,
                            sourceUrl = url,
                            initialTags = tags,
                            subfolder = subfolder
                        )
                    }

                } catch (e: Exception) {
                    errorMessage = e.message ?: "Download connection error"
                } finally {
                    try { tempFile.delete() } catch (ignored: Exception) {}
                    try { conn?.disconnect() } catch (ignored: Exception) {}
                }
            }

            downloadDialog?.dismiss()
            downloadDialog = null
            unstickWebPage()

            if (downloadedDoc != null) {
                val doc = downloadedDoc!!
                view?.let { root ->
                    Snackbar.make(
                        root,
                        "Saved to DocVault/${category.folderName}/: ${doc.title}",
                        Snackbar.LENGTH_LONG
                    ).setAction("View Now") {
                        val useExperimental = requireContext().getSharedPreferences("docvault_settings", 0)
                            .getBoolean("use_experimental_pdf_viewer", false)
                        val viewerClass = if (useExperimental) OfficialPdfViewerActivity::class.java else PdfViewerActivity::class.java
                        val intent = Intent(requireContext(), viewerClass).apply {
                            if (useExperimental) putExtra(OfficialPdfViewerActivity.EXTRA_DOC_ID, doc.id)
                            else putExtra(PdfViewerActivity.EXTRA_DOC_ID, doc.id)
                        }
                        startActivity(intent)
                    }.show()
                }
            } else if (errorMessage != null && !errorMessage.contains("canceled")) {
                showDownloadErrorDialog(url, title, category, filename, errorMessage)
            }
        }
    }

    private fun showDownloadErrorDialog(
        url: String,
        title: String,
        category: Category,
        filename: String,
        errorMessage: String
    ) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("Download Failed")
            .setMessage("Could not download '$filename':\n\n$errorMessage\n\nWould you like to try downloading via Android System Download Manager?")
            .setPositiveButton("Use System Downloader") { _, _ ->
                try {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setTitle(title)
                        setDescription("DocVault Download")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOCUMENTS, "DocVault/${category.folderName}/$filename")
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (!cookie.isNullOrEmpty()) {
                            addRequestHeader("Cookie", cookie)
                        }
                        addRequestHeader("User-Agent", webView.settings.userAgentString)
                    }
                    dm.enqueue(request)
                    Toast.makeText(context, "Download queued in Android System Manager", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "System download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    fun canGoBack(): Boolean = ::webView.isInitialized && webView.canGoBack()
    fun goBack() { if (::webView.isInitialized) webView.goBack() }

    override fun onDestroyView() {
        super.onDestroyView()
        activeDownloadJob?.cancel()
        downloadDialog?.dismiss()
        downloadDialog = null
    }
}
