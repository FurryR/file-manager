package io.furryr.file.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.R
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.ui.util.BrowseAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "file-manager.local"
private const val DOCUMENT_PATH = "__document__"
private const val LOADING_TAG = "web-loading"

/** One page of the WebView's own back/forward list, already display-formatted. */
data class WebHistoryEntry(val address: String, val title: String?)

/** Snapshot of the page history; [currentIndex] points at the visible page. */
data class WebHistorySnapshot(val entries: List<WebHistoryEntry>, val currentIndex: Int)

/**
 * WebView-backed renderer for both HTTP(S) URLs and local/archived HTML files.
 * The renderer only sees a [DocumentView]; it never knows whether the document
 * came from Vfs, a zip mount or a network URL.
 *
 * The session owns its WebView for as long as the tab lives, so switching tabs
 * keeps the WebView's own back/forward history and does not pay Chromium
 * start-up again. Only [close] destroys it.
 */
class WebRendererSession(
    val document: HtmlDocument?,
    initialUrlArg: String?,
    internal val host: RenderHost,
) : RendererSession {
    var initialUrl: String = initialUrlArg ?: "https://$ASSET_HOST/$DOCUMENT_PATH"
        private set

    var address by mutableStateOf(initialUrlArg ?: document?.file?.name.orEmpty())
        internal set

    /** Last page title; cleared as soon as a new document starts loading. */
    var pageTitle by mutableStateOf<String?>(null)
        private set

    /** 0..100, drives the slim progress bar above the page. */
    var progress by mutableIntStateOf(0)
        internal set

    var loading by mutableStateOf(true)
        internal set

    /** True once the current page produced its first visible frame. */
    var contentVisible by mutableStateOf(false)
        internal set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    private var webView: WebView? = null
    private var darkTheme = true
    private var closing = false
    /** A page's beforeunload prompt waiting for the shared leave dialog. */
    private var pendingUnload: JsResult? = null
    /** URLs this session already decided to load itself. */
    private val approvedNavigations = mutableSetOf<String>()

    override val capabilities: RenderCapabilities
        get() = RenderCapabilities(
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            canRefresh = true,
            // A page is not a directory: no file creation, no PTY/terminal.
            canCreate = false,
            canTerminal = false,
        )

    /** Navigate an existing URL session without rebuilding its WebView. */
    fun load(url: String) {
        pendingUnload?.cancel()
        pendingUnload = null
        initialUrl = url
        address = url
        // Keep the old title until the new document reports one; a navigation
        // should not blank the path bar while the page is still loading.
        loading = true
        progress = 0
        contentVisible = false
        webView?.loadUrl(url)
    }

    override fun onBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    fun goForward(): Boolean {
        val view = webView ?: return false
        if (!view.canGoForward()) return false
        view.goForward()
        return true
    }

    fun reload() {
        webView?.reload()
    }

    override fun refresh(): Boolean {
        // A web tab always owns refresh, even before its view is re-attached.
        webView?.reload() ?: run {
            loading = true
            progress = 0
            contentVisible = false
        }
        return true
    }

    override fun close() {
        if (closing) return
        closing = true
        pendingUnload?.cancel()
        pendingUnload = null
        val view = webView ?: return
        webView = null
        canGoBack = false
        canGoForward = false
        loading = false
        progress = 0
        contentVisible = false
        view.stopLoading()
        view.loadUrl("about:blank")
        view.destroy()
    }

    override fun menuItems(context: Context): List<RendererMenuItem> = listOf(
        RendererMenuItem(
            title = context.getString(R.string.refresh),
            icon = Icons.Default.Refresh,
        ) { refresh() },
    )

    override fun pathBar(context: Context): RendererPath {
        val title = pageTitle?.takeIf { it.isNotBlank() }
            ?: address.substringAfter("://").substringBefore('/').ifBlank { address }
        return RendererPath(
            title = title,
            caption = address,
            editText = address,
        )
    }

    internal fun ensureView(context: Context): WebView {
        webView?.let { return it }
        val created = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    // Network pages are never intercepted: only resources of a
                    // document that this session projects through Vfs.
                    val document = document ?: return null
                    val uri = request?.url ?: return null
                    if (uri.host != ASSET_HOST) return null
                    val path = uri.path.orEmpty().trimStart('/')
                    val relative = if (path.isEmpty() || path == DOCUMENT_PATH) null else path
                    val resource = runBlocking { document.open(relative) }.getOrNull()
                        ?: return notFound()
                    val text = resource.mime?.let {
                        it.startsWith("text/") || it.contains("json") ||
                            it.contains("xml") || it.contains("javascript")
                    } ?: false
                    return WebResourceResponse(
                        resource.mime ?: "application/octet-stream",
                        if (text) resource.encoding else null,
                        200,
                        "OK",
                        emptyMap<String, String>(),
                        resource.openStream(),
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Only user-facing main-frame navigations are candidates;
                    // resources and POST submissions stay with the WebView.
                    if (request == null || !request.isForMainFrame) return false
                    if (request.method != "GET") return false
                    val uri = request.url ?: return false
                    // A local document owns its own pages through ASSET_HOST.
                    if (document != null && uri.host == ASSET_HOST) return false
                    if (!BrowseAddress.isWebUrl(uri.toString())) return false
                    val url = uri.toString()
                    if (approvedNavigations.remove(url)) return false
                    // Same-document fragment jumps must not start a request.
                    val current = view?.url
                    if (current != null && stripFragment(current) == stripFragment(url)) return false
                    val target = view ?: return false
                    // A visible page extension is definitive: load it without
                    // the probe round-trip.
                    if (WebUrlFile.hasHtmlExtension(url)) return false
                    // Everything else is decided by the response, not the name.
                    // `github.com/user/repo.git` is a page, and an extensionless
                    // `/download?id=1` may be a PDF: only Content-Type (with
                    // magic-byte/sniff fallbacks) can tell them apart.
                    val file = WebUrlFile(url, referer = current)
                    loading = true
                    this@WebRendererSession.progress = 0
                    host.scope.launch {
                        // A page is recognized from HEAD alone, so the WebView
                        // fetches it exactly once. A concrete non-HTML type is
                        // a file and goes to the shared opener pipeline.
                        val head = withContext(Dispatchers.IO) { file.peek() }
                        if (head != null && ContentTypes.isHtmlMime(head.mime)) {
                            target.post {
                                if (webView !== target) return@post
                                approvedNavigations += url
                                target.loadUrl(url)
                            }
                            return@launch
                        }
                        val concrete = head != null &&
                            !ContentTypes.isGenericMime(head.mime) &&
                            // `text/plain` is also what misconfigured servers
                            // send for HTML: let the body sniff decide.
                            head.mime != "text/plain"
                        if (concrete) {
                            target.post {
                                if (webView !== target) return@post
                                loading = false
                                host.openFile(file)
                            }
                            return@launch
                        }
                        val content = withContext(Dispatchers.IO) {
                            file.describe().getOrNull()
                        }
                        target.post {
                            // The tab may have closed or navigated while the
                            // probe was in flight.
                            if (webView !== target) return@post
                            if (content == null || WebUrlFile.isHtml(content, file.name)) {
                                approvedNavigations += url
                                target.loadUrl(url)
                            } else {
                                loading = false
                                host.openFile(file, content)
                            }
                        }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    updateAddress(url)
                    refreshHistory(view)
                    loading = true
                    this@WebRendererSession.progress = 0
                    contentVisible = false
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    contentVisible = true
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    updateAddress(url)
                    refreshHistory(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    updateAddress(url)
                    refreshHistory(view)
                    loading = false
                    contentVisible = true
                    view?.let { injectColorScheme(it) }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    this@WebRendererSession.progress = newProgress
                    if (newProgress >= 100) loading = false
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    // Some pages report the URL as the title; keep the address
                    // fallback in the tab instead of duplicating it.
                    val clean = title?.takeIf { it.isNotBlank() && it != view?.url }
                    pageTitle = clean
                    host.updateTitle(clean)
                }

                override fun onJsBeforeUnload(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    val target = result ?: return false
                    if (closing) {
                        target.cancel()
                        return true
                    }
                    // A page without a real beforeunload prompt must keep the
                    // normal close path; waiting for our dialog here strands
                    // navigations that never registered the hook.
                    if (message == null) {
                        target.confirm()
                        return true
                    }
                    // The page is asking to leave; use the same tab-level
                    // dialog as the editor instead of WebView's default one.
                    pendingUnload?.cancel()
                    pendingUnload = target
                    host.confirmLeave(
                        message = message,
                        onLeave = {
                            if (pendingUnload === target) {
                                pendingUnload = null
                                target.confirm()
                            }
                        },
                        onCancel = {
                            if (pendingUnload === target) {
                                pendingUnload = null
                                target.cancel()
                            }
                        },
                    )
                    return true
                }
            }
            webView = this
            // Keep the renderer resident across tab switches; a killed renderer
            // would make the next page look like a cold start.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            loadUrl(initialUrl)
        }
        return created
    }

    internal val currentView: WebView? get() = webView

    internal fun applyTheme(view: WebView, dark: Boolean, surface: Int) {
        // Paint the WebView itself, so the window never flashes white before
        // the first frame of a dark page.
        view.setBackgroundColor(surface)
        val settings = view.settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Target SDK is below 33, so the legacy switch is still honoured on
            // newer WebViews; set it together with the modern opt-in.
            @Suppress("DEPRECATION")
            settings.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = dark
        }
        if (dark != darkTheme) {
            darkTheme = dark
            injectColorScheme(view)
        }
    }

    private fun injectColorScheme(view: WebView) {
        val scheme = if (darkTheme) "dark" else "light"
        view.evaluateJavascript(
            "(function(){var d=document.documentElement;if(d){d.style.colorScheme='$scheme';}})()",
            null,
        )
    }

    private fun refreshHistory(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    /**
     * The WebView's own back/forward list in display form: every URL the page
     * history holds, including in-page link navigations that never reach the
     * tab's folder stacks. The current page is the entry at [currentIndex].
     */
    fun historySnapshot(): WebHistorySnapshot {
        val view = webView
            ?: return WebHistorySnapshot(listOf(WebHistoryEntry(address, null)), 0)
        val list = view.copyBackForwardList()
        if (list.size <= 0) {
            return WebHistorySnapshot(listOf(WebHistoryEntry(address, null)), 0)
        }
        val entries = (0 until list.size).map { index ->
            val item = list.getItemAtIndex(index)
            val raw = item?.url
            WebHistoryEntry(
                address = raw?.let(::displayAddress) ?: address,
                title = item?.title?.takeIf { it.isNotBlank() && it != raw },
            )
        }
        return WebHistorySnapshot(entries, list.currentIndex.coerceIn(0, entries.lastIndex))
    }

    /** Move inside the WebView's own history without appending an entry. */
    fun jumpToHistory(index: Int) {
        val view = webView ?: return
        val current = view.copyBackForwardList().currentIndex
        if (index == current) return
        view.goBackOrForward(index - current)
    }

    private fun updateAddress(url: String?) {
        if (url == null) return
        approvedNavigations.clear()
        val next = displayAddress(url)
        if (next == address) return
        address = next
        // Keep the previous title while the new document loads. The title is
        // replaced by onReceivedTitle, so the path bar never flashes blank.
    }

    private fun stripFragment(url: String): String = url.substringBefore('#')

    /** Turn a WebView URL into what the address bar and history should show. */
    private fun displayAddress(url: String): String {
        val uri = Uri.parse(url)
        return if (uri.host == ASSET_HOST) documentAddress(uri) else url
    }

    private fun documentAddress(uri: Uri): String {
        val file = document?.file ?: return uri.toString()
        val path = uri.path.orEmpty().trimStart('/')
        return if (path.isEmpty() || path == DOCUMENT_PATH) {
            file.name
        } else {
            "${file.name}/$path"
        }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            emptyMap<String, String>(),
            ByteArrayInputStream(ByteArray(0)),
        )
}

class WebRendererFactory : RendererFactory {
    override val id: String = "web"

    override fun supports(target: RenderTarget): Boolean = when (target) {
        is RenderTarget.Url -> true
        is RenderTarget.Node -> {
            val content = target.content
            val mime = content?.mime ?: ContentTypes.fromName(target.node.name)
            ContentTypes.isHtmlMime(mime) ||
                target.node.name.endsWith(".html", ignoreCase = true) ||
                target.node.name.endsWith(".htm", ignoreCase = true) ||
                target.node.name.endsWith(".xhtml", ignoreCase = true)
        }
    }

    override suspend fun create(
        target: RenderTarget,
        host: RenderHost,
    ): Result<RendererSession> = runCatching {
        when (target) {
            is RenderTarget.Url -> WebRendererSession(document = null, initialUrlArg = target.url, host = host)
            is RenderTarget.Node -> {
                val file = target.node as? VFile ?: error("web renderer requires a file")
                WebRendererSession(document = HtmlDocument(file), initialUrlArg = null, host = host)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebContent(
    session: WebRendererSession,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val surfaceArgb = surface.toArgb()
    val dark = surface.luminance() < 0.5f
    val contentVisible = session.contentVisible
    val spinnerTint = MaterialTheme.colorScheme.primary.toArgb()
    key(session) {
        // The WebView is attached only after this holder has been laid out.
        // Creating Chromium inside the first AndroidView pass can leave the
        // interop view covering the surrounding Compose UI (tabs) until the
        // first page finishes; a plain holder avoids that first-run glitch.
        AndroidView(
            modifier = modifier.clipToBounds(),
            factory = { context ->
                FrameLayout(context).apply {
                    clipChildren = true
                    setBackgroundColor(surfaceArgb)
                }
            },
            onRelease = { host ->
                val web = session.currentView
                if (web != null && web.parent === host) host.removeView(web)
            },
            update = { host ->
                host.setBackgroundColor(surfaceArgb)
                val spinner = host.findViewWithTag<View>(LOADING_TAG)
                val view = session.currentView
                if (view != null && view.parent === host) {
                    session.applyTheme(view, dark, surfaceArgb)
                    spinner?.let {
                        it.setBackgroundColor(surfaceArgb)
                        it.visibility = if (contentVisible) View.GONE else View.VISIBLE
                    }
                } else if (host.tag == null && host.childCount == 0) {
                    host.tag = Unit
                    host.post {
                        host.tag = null
                        if (!host.isAttachedToWindow) return@post
                        val web = session.ensureView(host.context)
                        (web.parent as? ViewGroup)?.removeView(web)
                        host.addView(
                            web,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        session.applyTheme(web, dark, surfaceArgb)
                        // A blank WebView must not replace the page background
                        // while a remote document or a slow page is loading.
                        // The cover sits above the WebView with the real
                        // surface colour, so Chromium keeps rendering and its
                        // first white frame never reaches the screen.
                        val cover = FrameLayout(host.context).apply {
                            tag = LOADING_TAG
                            setBackgroundColor(surfaceArgb)
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                            addView(
                                ProgressBar(context).apply {
                                    isIndeterminate = true
                                    indeterminateTintList = ColorStateList.valueOf(spinnerTint)
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        Gravity.CENTER,
                                    )
                                },
                            )
                        }
                        host.addView(cover)
                        cover.visibility =
                            if (session.contentVisible) View.GONE else View.VISIBLE
                    }
                }
            },
        )
    }
}
