package io.furryr.file.render

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import io.furryr.file.ui.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.R
import io.furryr.file.Factory
import io.furryr.file.Renderer
import io.furryr.file.adblock.AdBlockEngine
import io.furryr.file.core.OpenIcon
import io.furryr.file.core.OpenOption
import io.furryr.file.core.VFile
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.runCatchingCancellable
import io.furryr.file.home.HomeEntry
import io.furryr.file.home.HomeEntryKind
import io.furryr.file.model.PageIcon
import io.furryr.file.ui.util.BrowseAddress
import io.furryr.file.terminal.TerminalTab
import io.furryr.file.terminal.WebDevToolsHost
import io.furryr.file.terminal.WebDevToolsTab
import io.furryr.file.userscript.UserScriptAttachment
import io.furryr.file.userscript.UserScriptEngine
import io.furryr.file.userscript.UserScriptInjector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale

private const val ASSET_HOST = "file.local"
private const val DOCUMENT_PATH = "__document__"
private const val LOADING_TAG = "web-loading"

/** `scheme:` prefix: a hit-test URL that already stands on its own. */
private val ABSOLUTE_URL = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/**
 * What a long press landed on inside the page: the link target or the image
 * source as the node it already is — a [VFile] like any other — plus the text
 * the element carries (anchor text or `alt`).
 *
 * "Copy" and "share" hand this node to the tab, so a URL takes the same copy
 * and share paths as a listed file; [stat] is the response's own name/size/type
 * once the probe answers.
 */
data class WebHitTarget(
    val file: VFile,
    val text: String?,
    val isImage: Boolean,
    val stat: ProviderStat? = null,
) {
    /** Absolute address of the target, for display and for the text actions. */
    val url: String get() = file.liveAddress ?: file.path.toString()
}

/**
 * WebView-backed renderer for an HTML document.
 *
 * The session only sees a [DocumentView]: whether the page is projected out of
 * a Vfs path, an archive view or a live origin is the document's business
 * ([DocumentView.remoteBase]), never a scheme this renderer inspects.
 *
 * The session owns its WebView for as long as the tab lives, so switching tabs
 * keeps the WebView's own back/forward history and does not pay Chromium
 * start-up again. Only [close] destroys it.
 */
class WebPageState(
    var address: String,
    var title: String?,
) : PageState()

internal class WebRendererSession(
    val document: HtmlDocument,
    initialState: WebPageState? = null,
) : PageSession<WebPageState>(initialState), WebDevToolsHost {
    internal var host: RendererContext? = null
    internal val state: WebPageState = seedState ?: WebPageState(
        address = document.remoteBase ?: document.origin.let(NodeAddress::format),
        title = null,
    )
    /**
     * A live document is loaded by its own origin; a projected one is served
     * through [ASSET_HOST] so relative resources can be resolved through the
     * node graph.
     */
    private val liveBase: String? = document.remoteBase

    /** True when the page's bytes live at a network origin. */
    val isRemote: Boolean get() = liveBase != null
    override val isLive: Boolean get() = isRemote
    override val webAddress: String? get() = address

    var pageIcon by mutableStateOf<PageIcon>(PageIcon.Vector(Icons.Default.Language))
        private set
    override val title: String? get() =
        titleState ?: document.file.name.takeIf { it.isNotBlank() }

    /**
     * Up routes the folder that owns the document and appends it as a stop.
     * A live page's node has no browsable parent, so Up stays unavailable.
     */
    override val capabilities: RenderCapabilities
        get() {
            val folder = document.file.parent
            val host = host
            val up = if (folder == null || host == null) {
                null
            } else {
                {
                    host.scope.launch {
                        try {
                            host.open(host.opener.route(folder))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            host.reportError(error)
                        }
                    }
                    Unit
                }
            }
            return RenderCapabilities(
                up = up,
                terminal = TerminalFactory {
                    devToolsTab?.takeUnless { it.isDisposed }
                        ?: WebDevToolsTab(this) { devToolsLabel() }.also { devToolsTab = it }
                },
            )
        }

    /** One DevTools tab per page: its frontend owns the only CDP connection. */
    private var devToolsTab: WebDevToolsTab? = null

    private fun devToolsLabel(): String {
        val page = pageTitle?.takeIf { it.isNotBlank() }
            ?: document.file.name?.takeIf { it.isNotBlank() }
            ?: address
        return "DevTools · $page"
    }

    override fun bookmarkEntry(): HomeEntry? {
        val location = address.takeIf { it.isNotBlank() } ?: return null
        val label = title?.removePrefix("• ")?.takeIf { it.isNotBlank() } ?: location
        return HomeEntry(
            if (isLive) HomeEntryKind.WEB else HomeEntryKind.FILE,
            location,
            label,
            location,
        )
    }

    var initialUrl: String = liveBase ?: "https://$ASSET_HOST/$DOCUMENT_PATH"
        private set

    /** Text address of the document itself (provider path plus view levels). */
    private val documentAddress: String = document.origin.let(NodeAddress::format)

    override var address: String
        get() = addressState
        private set(value) {
            addressState = value
            state.address = value
        }

    private var addressState by mutableStateOf(state.address)

    /** Last page title; cleared as soon as a new document starts loading. */
    var pageTitle: String?
        get() = titleState
        private set(value) {
            titleState = value
            state.title = value
        }

    private var titleState by mutableStateOf(state.title)

    /** A load is in flight; drives in-page history detection. */
    private var pageLoading = true

    private fun setPageProgress(progress: Int?) {
        pageLoading = progress != null
        host?.setProgress(progress)
    }

    /** True once the current page produced its first visible frame. */
    var contentVisible by mutableStateOf(false)
        internal set

    /**
     * True while the tab is still settling whether this address is a page.
     *
     * An optimistic open hands the address to this renderer before the server
     * has said what it is, so the cover must outlive the first committed frame:
     * a response that turns out to be an archive or an image is handed back to
     * the opener, and its bytes must never flash on the way out.
     *
     * The flag is owned by one address. As soon as the WebView moves to another
     * document (a redirect, a link, the page's own pushState) the verdict no
     * longer describes what is on screen, so the cover is released instead of
     * waiting for an answer that can never settle it.
     */
    var probing by mutableStateOf(false)
        internal set

    /** The page may replace the loading cover once nothing still has to confirm it. */
    internal val settled: Boolean get() = contentVisible && !probing

    /** Hold the cover up: the address is on screen but not confirmed yet. */
    fun beginProbe() {
        probing = true
    }

    /** The address is confirmed as a page: the cover may get out of the way. */
    fun settlePage() {
        probing = false
    }

    override fun beginConfirmation() = beginProbe()
    override fun settleConfirmation() = settlePage()

    /**
     * A live page can keep its WebView when the route table chose another
     * live page. A projected page is a different document, so it gets a
     * fresh session instead.
     */
    override fun adopt(address: String, next: RendererSession): Boolean {
        val other = next as? WebRendererSession ?: return false
        if (!isRemote || !other.isRemote) return false
        other.close()
        load(address)
        return true
    }

    /** Link or image under the last long press; drives the renderer dialog. */
    var hitTarget by mutableStateOf<WebHitTarget?>(null)
        private set

    /** False once the page is gone; the DevTools tab shows its closed state. */
    override var devToolsAlive by mutableStateOf(true)
        private set

    override fun devToolsView(context: Context): WebView? {
        if (closing) return null
        return ensureView(context)
    }

    /** Written and released on the main thread, read by Chromium's request thread. */
    @Volatile
    private var webView: WebView? = null
    /** The view's own context, so the request thread never touches the WebView. */
    @Volatile
    private var hostContext: Context? = null
    /**
     * Raw address reported by the WebView for the current document. Kept
     * separate from [address] because `shouldInterceptRequest` runs off the
     * main thread, where `WebView.getUrl` is a fatal call, and because
     * [address] is the display spelling (a VFS path for a projected page).
     */
    @Volatile
    private var loadedUrl: String? = null
    private var userScripts: UserScriptAttachment? = null
    private var darkTheme = true
    @Volatile
    private var closing = false

    /** True once the WebView was released; no view may be rebuilt after that. */
    internal val closed: Boolean get() = closing

    /** A page's beforeunload prompt waiting for the shared popup. */
    private var pendingUnload: JsResult? = null
    /** Non-null while a tab unload is driving about:blank to trigger beforeunload. */
    private var unloadResult: CompletableDeferred<Boolean>? = null
    /** URLs this session already decided to load itself. */
    private val approvedNavigations = mutableSetOf<String>()
    /** URLs this session moved to by itself (a retrace); the tab already knows. */
    private val selfDriven = mutableSetOf<String>()
    /**
     * Last address that actually rendered a page. A redirect target that
     * arrives before this catches up to [address] replaces the provisional
     * stop instead of adding one.
     */
    private var committedAddress: String? = null


    override val consumesEdgeGestures: Boolean get() = true

    override suspend fun requestUnload(context: RendererContext): Boolean {
        if (closing) return true
        val view = webView
        if (view == null || unloadResult != null) {
            return unloadResult == null
        }
        val deferred = CompletableDeferred<Boolean>()
        unloadResult = deferred
        // A WebView is destroyed without firing beforeunload. Navigate to a
        // blank document so the page's own handler can run; the answer settles
        // in onPageFinished.
        view.loadUrl("about:blank")
        val allowed = withTimeoutOrNull(5_000) { deferred.await() } ?: true
        if (unloadResult === deferred) unloadResult = null
        return allowed
    }


    /**
     * Point this session at [url], keeping its WebView.
     *
     * The address is recorded as approved, so the WebView's own navigation
     * callback recognises it as the page the route table just chose instead of
     * classifying it a second time (and asking the server again).
     */
    fun load(url: String) {
        if (closing) return
        unloadResult?.complete(false)
        unloadResult = null
        pendingUnload?.cancel()
        pendingUnload = null
        hitTarget = null
        initialUrl = url
        address = url
        approvedNavigations += url
        selfDriven.clear()
        // Keep the old title until the new document reports one; a navigation
        // should not blank the path bar while the page is still loading.
        setPageProgress(0)
        contentVisible = false
        probing = false
        webView?.let { view ->
            ensureUserScriptAttachment(view)
            view.loadUrl(url)
        }
    }

    /**
     * Reposition the WebView to a page it already holds, so a history step
     * costs no request.
     *
     * The tab owns the history; this only moves the cache, so the landing is
     * not reported back as a new visit. Returns false when the page is not in
     * the list and has to be opened again through the route table.
     */
    override fun retrace(address: String): Boolean {
        val view = webView ?: return false
        val list = view.copyBackForwardList()
        val target = (list.size - 1 downTo 0).firstOrNull { index ->
            list.getItemAtIndex(index)?.url?.let(::displayAddress) == address
        } ?: return false
        val delta = target - list.currentIndex
        if (delta == 0) return true
        selfDriven += address
        view.goBackOrForward(delta)
        return true
    }

    fun reload() {
        if (closing) return
        // A web tab always owns reload, even before its view is re-attached.
        val view = webView
        if (view != null) {
            ensureUserScriptAttachment(view)
            view.reload()
        } else {
            setPageProgress(0)
            contentVisible = false
        }
    }

    override fun reset() {
        userScripts?.detach()
        userScripts = null
        host = null
        unloadResult?.complete(false)
        unloadResult = null
        pendingUnload?.cancel()
        pendingUnload = null
        hitTarget = null
        val view = webView ?: return
        webView = null
        setPageProgress(null)
        contentVisible = false
        probing = false
        view.stopLoading()
        // destroy() must never run while the WebView is still in a view tree:
        // a path switch releases a page from inside a composition callback,
        // where the AndroidView holder has not removed the child yet. Detach
        // first, then destroy, so a destroyed view cannot be drawn, measured
        // or reached by a late Chromium callback.
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }

    override fun close() {
        if (closing) return
        closing = true
        devToolsAlive = false
        reset()
    }

    /** Close the long-press dialog; the page itself keeps running. */
    fun dismissHitTarget() {
        hitTarget = null
    }

    /** Patch the open hit target, unless the dialog moved on to another link. */
    private fun updateHitTarget(url: String, transform: (WebHitTarget) -> WebHitTarget) {
        val current = hitTarget ?: return
        if (current.url != url) return
        hitTarget = transform(current)
    }

    override fun menuItems(context: Context): List<RendererMenuItem> = buildList {
        add(
            RendererMenuItem(
                title = context.getString(R.string.refresh),
                icon = Icons.Default.Refresh,
            ) { reload() },
        )
        bookmarkMenuItem(context, bookmarkEntry())?.let(::add)
    }

    override fun pathBar(context: Context): RendererPath {
        // The title line keeps the file name; the address below it carries the
        // whole path, so a saved page is still locatable.
        val title = pageTitle?.takeIf { it.isNotBlank() }
            ?: document.file.name
            ?: address.substringAfter("://").substringBefore('/').ifBlank { address }
        return RendererPath(
            title = title,
            caption = address,
            editText = address,
        )
    }

    internal fun ensureView(context: Context): WebView {
        webView?.let { return it }
        hostContext = context.applicationContext
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
                    // Only a projected document is served through this host; a
                    // live page fetches its own resources from its origin and
                    // never reaches this branch. A request from a view this
                    // session no longer owns (a released or replaced WebView)
                    // must not read the document.
                    if (closing || view == null || view !== webView) return null
                    val webRequest = request ?: return null
                    val uri = webRequest.url
                    if (uri.host != ASSET_HOST) {
                        // Only live subresources are advertisements: a top-level
                        // navigation is the page the user asked for, and a
                        // projected document already goes through ASSET_HOST.
                        if (webRequest.isForMainFrame) return null
                        val scheme = uri.scheme?.lowercase(Locale.ROOT)
                        if (scheme != "http" && scheme != "https") return null
                        val headers = webRequest.requestHeaders
                        val source = (headers["Referer"] ?: headers["referer"])
                            ?.takeIf { it.isNotBlank() }
                            ?: loadedUrl.orEmpty()
                        // This callback runs on Chromium's request thread, where
                        // any WebView method is fatal; the context is cached at
                        // view creation for exactly this call.
                        val context = hostContext ?: return null
                        if (AdBlockEngine.shouldBlock(
                                context,
                                uri.toString(),
                                source,
                                resourceType(uri, webRequest),
                                webRequest.method,
                            )
                        ) {
                            return blockedResponse()
                        }
                        return null
                    }
                    // The body read happens on Chromium's request thread and can
                    // fail (a vanished archive, a provider that went away). The
                    // callback must answer with a response, never throw.
                    return runCatching {
                        val path = uri.path.orEmpty().trimStart('/')
                        val relative = if (path.isEmpty() || path == DOCUMENT_PATH) null else path
                        val resource = runBlocking { document.open(relative) }.getOrNull()
                            ?: return@runCatching notFound()
                        val text = resource.mime?.let {
                            it.startsWith("text/") || it.contains("json") ||
                                it.contains("xml") || it.contains("javascript")
                        } ?: false
                        WebResourceResponse(
                            resource.mime ?: "application/octet-stream",
                            if (text) resource.encoding else null,
                            200,
                            "OK",
                            emptyMap<String, String>(),
                            resource.openStream(),
                        )
                    }.getOrElse { notFound() }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    if (closing || view == null || view !== webView) return false
                    // Only user-facing main-frame navigations are candidates;
                    // resources and POST submissions stay with the WebView.
                    if (request == null || !request.isForMainFrame) return false
                    if (request.method != "GET") return false
                    // A redirect is not a link click: the WebView follows it
                    // itself so onPageStarted/onPageFinished move this page's
                    // stop. Routing it here would open the target as a second
                    // page and leave the first stop titleless.
                    if (request.isRedirect) {
                        return false
                    }
                    val uri = request.url ?: return false
                    // A projected document owns its own pages through ASSET_HOST.
                    if (uri.host == ASSET_HOST) return false
                    if (!BrowseAddress.isWebUrl(uri.toString())) return false
                    val url = uri.toString()
                    selfDriven.clear()
                    if (approvedNavigations.remove(url)) return false
                    // Same-document fragment jumps must not start a request.
                    val current = view.url
                    if (current != null && stripFragment(current) == stripFragment(url)) return false
                    // A link is just a node at an address: hand it to the same
                    // route table an address-bar open uses, with no facts. A page
                    // comes back as this session (reused and loaded), an archive
                    // or a file goes to its opener or viewer — this renderer
                    // decides nothing, and probing here would delay the very
                    // navigation the WebView can already start. The tab settles
                    // the guess against the server and reopens the node if it was
                    // not a page.
                    val currentHost = host ?: return false
                    // A hit-test/web address that the router cannot parse is not
                    // a reason to kill the process: leave it to the WebView.
                    val file = currentHost.vfs.liveFile(url, referer = current).getOrNull()
                        ?: return false
                    setPageProgress(0)
                    currentHost.openFile(file)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (closing || view == null || view !== webView) return
                    this@WebRendererSession.userScripts?.onPageStarted()
                    pageIcon = PageIcon.Vector(Icons.Default.Language)
                    host?.updatePageIcon(pageIcon)
                    updateAddress(url)
                    setPageProgress(0)
                    contentVisible = false
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (closing || view == null || view !== webView) return
                    updateAddress(url)
                    contentVisible = true
                    committedAddress = url?.let(::displayAddress) ?: address
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (closing || view == null || view !== webView) return
                    // pushState/replaceState update the page's history without
                    // a new document; there is no commit callback to follow, so
                    // this URL becomes the committed page now.
                    val inPage = contentVisible && !pageLoading
                    updateAddress(url)
                    if (inPage) committedAddress = url?.let(::displayAddress) ?: address
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (closing || view == null || view !== webView) return
                    val unloading = unloadResult
                    if (unloading != null && url == "about:blank") {
                        unloadResult = null
                        unloading.complete(true)
                        return
                    }
                    updateAddress(url)
                    setPageProgress(null)
                    contentVisible = true
                    committedAddress = url?.let(::displayAddress) ?: address
                    injectColorScheme(view)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    // Chromium's renderer process died (OOM, a crashed page).
                    // The default outcome is the app being killed; drop the
                    // dead view instead and let the next render rebuild one
                    // from the address that was showing.
                    if (view == null || view !== webView) return true
                    webView = null
                    userScripts?.detach()
                    userScripts = null
                    unloadResult?.complete(false)
                    unloadResult = null
                    pendingUnload = null
                    setPageProgress(null)
                    contentVisible = false
                    probing = false
                    (view.parent as? ViewGroup)?.removeView(view)
                    runCatching { view.destroy() }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (closing || view == null || view !== webView) return
                    setPageProgress(if (newProgress >= 100) null else newProgress)
                }

                override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
                    if (closing || view == null || view !== webView || icon == null) return
                    pageIcon = PageIcon.Bitmap(icon.asImageBitmap())
                    host?.updatePageIcon(pageIcon)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (closing || view == null || view !== webView) return
                    if (unloadResult != null) return
                    // Some pages report the URL as the title; keep the address
                    // fallback in the tab instead of duplicating it.
                    val clean = title?.takeIf { it.isNotBlank() && it != view.url }
                    pageTitle = clean
                    host?.updateTitle(clean)
                }

                override fun onJsBeforeUnload(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    val target = result ?: return false
                    if (closing) {
                        runCatching { target.cancel() }
                        return true
                    }
                    if (view != null && view !== webView) return false
                    // A page without a real beforeunload prompt must keep the
                    // normal close path; waiting for our dialog here strands
                    // navigations that never registered the hook.
                    if (message == null) {
                        runCatching { target.confirm() }
                        return true
                    }
                    // The page is asking to leave; use this renderer's own
                    // popup instead of WebView's default one.
                    runCatching { pendingUnload?.cancel() }
                    pendingUnload = target
                    val currentHost = host
                    if (currentHost == null) {
                        pendingUnload = null
                        runCatching { target.confirm() }
                        return true
                    }
                    val unloading = unloadResult
                    currentHost.scope.launch {
                        val leave = currentHost.popup<Boolean> { resolve ->
                            AlertDialog(
                                onDismissRequest = { resolve(false) },
                                title = { Text(stringResource(R.string.editor_unsaved)) },
                                text = { Text(message) },
                                confirmButton = {
                                    TextButton(onClick = { resolve(true) }) {
                                        Text(stringResource(R.string.editor_leave))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { resolve(false) }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                },
                            )
                        }
                        if (pendingUnload === target) {
                            pendingUnload = null
                            if (leave == true) {
                                runCatching { target.confirm() }
                            } else {
                                // A dismissed popup answers null; treat it as a
                                // cancel so the WebView is not left waiting for
                                // a verdict that will never come.
                                runCatching { target.cancel() }
                                if (unloading != null) {
                                    if (unloadResult === unloading) unloadResult = null
                                    unloading.complete(false)
                                }
                            }
                        }
                    }
                    return true
                }
            }
            // A long press on a link or an image opens this renderer's own
            // dialog instead of Chromium's built-in menu. The press point is
            // captured here because the hit test result only carries the URL,
            // while the anchor/alt text lives in the DOM.
            var pressX = 0f
            var pressY = 0f
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    pressX = event.x
                    pressY = event.y
                }
                false
            }
            setOnLongClickListener { pressed ->
                val target = pressed as WebView
                val hit = target.hitTestResult ?: return@setOnLongClickListener false
                val type = hit.type
                val isImage = type == WebView.HitTestResult.IMAGE_TYPE ||
                    type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                if (!isImage && type != WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    // Plain text, form fields and blank space keep the
                    // WebView's own selection behaviour.
                    return@setOnLongClickListener false
                }
                val raw = hit.extra?.takeIf { it.isNotBlank() }
                    ?: return@setOnLongClickListener false
                val url = absoluteUrl(raw)
                // The link is a node: the page it came from is the referer the
                // probe and the forward provider must carry. A hit-test URL the
                // router cannot turn into a live file (a data: image, a
                // javascript: anchor) simply keeps Chromium's own behaviour.
                val files = host?.vfs ?: return@setOnLongClickListener false
                val file = files.liveFile(url, referer = target.url).getOrNull()
                    ?: return@setOnLongClickListener false
                this@WebRendererSession.hitTarget = WebHitTarget(
                    file = file,
                    text = null,
                    isImage = isImage,
                )
                // The dialog already shows the URL; the element text arrives
                // one script round-trip later, the response facts after the
                // probe. Neither blocks the other.
                requestHitText(target, pressX, pressY, isImage) { text ->
                    this@WebRendererSession.updateHitTarget(url) { it.copy(text = text) }
                }
                // A link inside a local document points at this session's own
                // asset host: there is no server to probe.
                if (Uri.parse(url).host != ASSET_HOST) {
                    this@WebRendererSession.host?.scope?.launch {
                        val stat = withContext(Dispatchers.IO) { file.stat().getOrNull() }
                        target.post {
                            this@WebRendererSession.updateHitTarget(url) { it.copy(stat = stat) }
                        }
                    }
                }
                true
            }
            webView = this
            userScripts?.detach()
            userScripts = null
            ensureUserScriptAttachment(this)
            // Keep the renderer resident across tab switches; a killed renderer
            // would make the next page look like a cold start.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            loadUrl(initialUrl)
        }
        return created
    }

    /**
     * Attach the userscript bridge exactly once per WebView. Enabling scripts
     * after a page is already open takes effect on the next document load.
     */
    private fun ensureUserScriptAttachment(view: WebView) {
        if (closing) return
        runCatching { UserScriptEngine.refreshIfChanged(view.context) }
        if (userScripts != null) return
        userScripts = UserScriptInjector.attach(view.context, view) { url ->
            val currentHost = host ?: return@attach
            val file = currentHost.vfs.liveFile(url, referer = view.url).getOrNull()
                ?: return@attach
            currentHost.openFile(file)
        }
    }

    internal val currentView: WebView? get() = webView

    internal fun applyTheme(view: WebView, dark: Boolean, surface: Int) {
        if (closing || view !== webView) return
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
        if (closing || view !== webView) return
        val scheme = if (darkTheme) "dark" else "light"
        runCatching {
            view.evaluateJavascript(
                "(function(){var d=document.documentElement;if(d){d.style.colorScheme='$scheme';}})()",
                null,
            )
        }
    }

    private fun updateAddress(url: String?) {
        // A closing session loads about:blank first; that is not a location.
        if (url == null || closing || unloadResult != null) return
        // The request thread reads this instead of asking the WebView.
        loadedUrl = url
        approvedNavigations.clear()
        val next = displayAddress(url)
        if (next == address) return
        val previous = address
        // The page being replaced never rendered: this is a redirect (or a
        // redirect chain), not a visit. The tab must replace its provisional
        // stop instead of pushing it into history.
        val replacePrevious = previous.takeIf { committedAddress != previous }
        address = next
        // A WebView rebuilt after an activity recreate must come back to the
        // document that is showing, not to the address this session first
        // opened.
        initialUrl = next
        // An optimistic probe belongs to the address that was opened. Once the
        // WebView moves to another document (a redirect, a link, pushState) the
        // verdict no longer describes what is on screen; holding the loading
        // cover for it would leave the spinner up for good.
        probing = false
        // A retrace lands on a stop the tab already made. Anything else is a
        // visit, and the tab decides what it becomes: a new stop, a stop it
        // already holds, or nothing. Keep the previous title while the new
        // document loads; onReceivedTitle replaces it.
        if (selfDriven.remove(next)) return
        host?.onPageVisited(next, replacePrevious)
    }

    private fun stripFragment(url: String): String = url.substringBefore('#')

    /** Make a hit-test URL absolute against the page it was found in. */
    private fun absoluteUrl(raw: String): String {
        if (ABSOLUTE_URL.containsMatchIn(raw)) return raw
        val base = webView?.url ?: return raw
        return runCatching { java.net.URI(base).resolve(raw).toString() }.getOrDefault(raw)
    }

    /**
     * Read the anchor text (or the image `alt`) of the element under the
     * press. A DOM point is in CSS pixels, so the view point is divided by the
     * current page scale. A blank answer just leaves the "copy link text"
     * action falling back to the URL.
     */
    private fun requestHitText(
        view: WebView,
        x: Float,
        y: Float,
        isImage: Boolean,
        onResult: (String?) -> Unit,
    ) {
        // getScale() is the only view→CSS pixel factor available on all
        // supported versions; the page has no other way to report its zoom.
        @Suppress("DEPRECATION")
        val scale = view.scale.takeIf { it > 0f } ?: 1f
        val px = (x / scale).toInt()
        val py = (y / scale).toInt()
        val script = if (isImage) {
            "(function(){var e=document.elementFromPoint($px,$py);if(!e)return '';" +
                "if(e.tagName==='IMG')return e.alt||'';" +
                "var i=e.querySelector?e.querySelector('img'):null;return i?(i.alt||''):'';})()"
        } else {
            "(function(){var e=document.elementFromPoint($px,$py);if(!e)return '';" +
                "var a=e.closest?e.closest('a'):null;var t=a||e;" +
                "return (t.innerText||t.textContent||'').trim();})()"
        }
        if (closing || view !== webView) return
        runCatching { view.evaluateJavascript(script) { value -> onResult(decodeJsString(value)) } }
    }

    /** Unwrap the JSON-encoded string `evaluateJavascript` hands back. */
    private fun decodeJsString(value: String?): String? {
        if (value == null || value == "null") return null
        val text = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Turn a WebView URL into what the address bar and history should show. */
    private fun displayAddress(url: String): String {
        val uri = Uri.parse(url)
        if (uri.host != ASSET_HOST) return url
        val base = documentAddress
        val path = uri.path.orEmpty().trimStart('/')
        // The main document is addressed by its own path; anything else it
        // loads is a sibling of that file, so the path extends the document's
        // folder rather than the document itself.
        if (path.isEmpty() || path == DOCUMENT_PATH) return base
        val parent = base.substringBeforeLast('/', "")
        return if (parent.isEmpty()) path else "$parent/$path"
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

    /** An empty 200: the request is answered without fetching the advertiser. */
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            emptyMap<String, String>(),
            ByteArrayInputStream(ByteArray(0)),
        )

    /**
     * Best-effort resource type for the filter engine. WebView exposes no type
     * on [WebResourceRequest], so the Accept header comes first and the URL
     * extension is the fallback. Unknown resources stay "other" instead of
     * being guessed into a rule class.
     */
    private fun resourceType(uri: Uri, request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val headers = request.requestHeaders
        // Modern WebView reports the fetch destination; it is more precise
        // than the Accept header for scripts and fetch requests.
        val destination = (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"]).orEmpty()
        when (destination) {
            "script" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "iframe", "frame", "document" -> return "subdocument"
            "audio", "video", "track" -> return "media"
            "empty" -> return "xmlhttprequest"
        }
        val accept = (headers["Accept"] ?: headers["accept"]).orEmpty()
        when {
            accept.contains("text/css") -> return "stylesheet"
            accept.contains("javascript") || accept.contains("ecmascript") -> return "script"
            accept.contains("image/") -> return "image"
            accept.contains("font/") || accept.contains("application/font") -> return "font"
            accept.contains("text/html") -> return "subdocument"
            accept.contains("video/") || accept.contains("audio/") -> return "media"
        }
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".json") -> "xmlhttprequest"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".avif") || path.endsWith(".ico") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") -> "font"
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") ||
                path.endsWith(".m4a") || path.endsWith(".ogg") -> "media"
            else -> "other"
        }
    }
}

/** View half of a web session; the session holds state and the WebView. */
internal class WebSessionView(
    private val session: WebRendererSession,
) : SessionView {
    @Composable
    override fun Content(host: RendererContext, darkTheme: Boolean, modifier: Modifier) {
        session.host = host
        WebContent(session, modifier)
    }
}

class WebFactory(private val files: io.furryr.file.core.FileWorld, label: String) : Factory {
    override val option: OpenOption = OpenOption(id = "web", label = label, icon = OpenIcon.PAGE)

    override suspend fun create(file: VFile): Result<Renderer> = runCatchingCancellable {
        WebRenderer(HtmlDocument(files.vfs, file))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebContent(
    session: WebRendererSession,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val surfaceArgb = surface.toArgb()
    val dark = surface.luminance() < 0.5f
    val settled = session.settled
    val spinnerTint = MaterialTheme.colorScheme.primary.toArgb()
    // A tab switch drops this content but keeps the session: never bring a
    // stale long-press dialog back with the tab.
    DisposableEffect(session) {
        onDispose { session.dismissHitTarget() }
    }
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
                    // The cover exists from the first frame and independent of
                    // the WebView: the loading state can never outlive a missed
                    // attach pass, and updating it never depends on Chromium
                    // already being a child of this holder.
                    addView(
                        FrameLayout(context).apply {
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
                        },
                    )
                }
            },
            onRelease = { host ->
                val web = session.currentView
                if (web != null && web.parent === host) host.removeView(web)
            },
            update = { host ->
                host.setBackgroundColor(surfaceArgb)
                val view = session.currentView
                if (!session.closed) {
                    if (view != null && view.parent === host) {
                        session.applyTheme(view, dark, surfaceArgb)
                    } else if (host.tag == null) {
                        host.tag = Unit
                        host.post {
                            host.tag = null
                            if (!host.isAttachedToWindow || session.closed) return@post
                            val web = session.currentView ?: session.ensureView(host.context)
                            if (web.parent === host) return@post
                            (web.parent as? ViewGroup)?.removeView(web)
                            // Index 0 keeps the loading cover above Chromium,
                            // so a remote document or a slow page never shows
                            // a blank first frame through the app surface.
                            host.addView(
                                web,
                                0,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            session.applyTheme(web, dark, surfaceArgb)
                        }
                    }
                }
                // The cover is updated even when there is no WebView yet: a
                // page that finished loading while its view was detached must
                // not keep the spinner once it is shown again.
                host.findViewWithTag<View>(LOADING_TAG)?.let { cover ->
                    cover.setBackgroundColor(surfaceArgb)
                    cover.visibility = if (settled) View.GONE else View.VISIBLE
                }
            },
        )
    }
    // The dialog lives next to the WebView, so a long press never has to
    // leave Chromium's hit test result behind to reach Compose.
    session.hitTarget?.let { target ->
        session.host?.let { host ->
            WebHitDialog(
                target = target,
                host = host,
                onDismiss = session::dismissHitTarget,
            )
        }
    }
}

/**
 * Long-press dialog for a link or an image: the address on top, then five
 * actions. "Copy" and "Share" act on the resource itself ([WebHitTarget] is an
 * on-demand live file, served to the receiver through the forward
 * provider), while the other three act on the text the page carries.
 */
@Composable
private fun WebHitDialog(
    target: WebHitTarget,
    host: RendererContext,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val url = target.url
    val label = stringResource(if (target.isImage) R.string.web_hit_image else R.string.web_hit_link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                WebHitAction(Icons.Default.ContentCopy, stringResource(R.string.copy)) {
                    // The file, not a string: the tab's own copy flow takes the
                    // node, so the destination folder streams it on demand.
                    host.copyFile(target.file, target.stat)
                    onDismiss()
                }
                WebHitAction(
                    Icons.Default.TextFields,
                    stringResource(R.string.web_copy_link_text),
                ) {
                    copyToClipboard(context, label, target.text ?: url)
                    onDismiss()
                }
                WebHitAction(Icons.Default.Link, stringResource(R.string.web_copy_url)) {
                    copyToClipboard(context, label, url)
                    onDismiss()
                }
                WebHitAction(Icons.Default.Share, stringResource(R.string.share)) {
                    // Shared as a file: the receiver opens a content URI and
                    // reads it on demand, exactly like a local file share.
                    host.shareFile(target.file, target.stat)
                    onDismiss()
                }
                WebHitAction(Icons.AutoMirrored.Filled.Send, stringResource(R.string.web_share_url)) {
                    shareUrlText(context, url)
                    onDismiss()
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun WebHitAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT)
                .show()
        }
}

private fun shareUrlText(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_failed), Toast.LENGTH_SHORT).show()
    }
}

class WebRenderer(
    private val document: HtmlDocument,
    initialState: WebPageState? = null,
) : RendererBase<WebPageState>(initialState) {
    val state: WebPageState get() = webSession.state
    override val pagePath: String get() = state.address
    override val pageIcon: PageIcon get() = webSession.pageIcon

    private val webSession: WebRendererSession get() = session as WebRendererSession

    override fun createSession(state: WebPageState?): PageSession<WebPageState> =
        WebRendererSession(document, state)

    override suspend fun enter(host: RendererContext) {
        host.commit { h, dark, modifier ->
            WebSessionView(session as WebRendererSession).Content(h, dark, modifier)
        }
    }
}
