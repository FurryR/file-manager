package io.furryr.file.render

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "file-manager.local"
private const val DOCUMENT_PATH = "__document__"

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

    /** 0..100, drives the slim progress bar above the page. */
    var progress by mutableIntStateOf(0)
        internal set

    var loading by mutableStateOf(false)
        internal set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    private var webView: WebView? = null
    private var darkTheme = true

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
        initialUrl = url
        address = url
        loading = true
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
        webView?.reload() ?: run { loading = true }
        return true
    }

    override fun close() {
        val view = webView ?: return
        webView = null
        canGoBack = false
        canGoForward = false
        loading = false
        progress = 0
        view.stopLoading()
        view.loadUrl("about:blank")
        view.destroy()
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
                    // Keep every link inside this WebView; update the address
                    // immediately instead of waiting for onPageStarted.
                    request?.url?.toString()?.let { updateAddress(it) }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    updateAddress(url)
                    refreshHistory(view)
                    loading = true
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    updateAddress(url)
                    refreshHistory(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    updateAddress(url)
                    refreshHistory(view)
                    loading = false
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
                    host.updateTitle(clean)
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
        val next = displayAddress(url)
        if (next == address) return
        address = next
        // The old page's title must not label the new page while it loads.
        host.updateTitle(null)
    }

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
            mime == "text/html" || target.node.name.endsWith(".html", ignoreCase = true) ||
                target.node.name.endsWith(".htm", ignoreCase = true)
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
                val view = session.currentView
                if (view != null && view.parent === host) {
                    session.applyTheme(view, dark, surfaceArgb)
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
                    }
                }
            },
        )
    }
}
