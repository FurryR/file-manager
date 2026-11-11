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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.R
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.RemoteFile
import io.furryr.file.core.VFile
import io.furryr.file.provider.web.httpFile
import io.furryr.file.ui.util.BrowseAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.io.ByteArrayInputStream

private const val ASSET_HOST = "file-manager.local"
private const val DOCUMENT_PATH = "__document__"
private const val LOADING_TAG = "web-loading"

/** `scheme:` prefix: a hit-test URL that already stands on its own. */
private val ABSOLUTE_URL = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/**
 * What a long press landed on inside the page: the link target or the image
 * source as the node it already is — a [RemoteFile], which is a [VFile] like
 * any other — plus the text the element carries (anchor text or `alt`).
 *
 * "Copy" and "share" hand this node to the tab, so a URL takes the same copy
 * and share paths as a listed file; [content] is the response's own
 * name/size/type once the probe answers.
 */
data class WebHitTarget(
    val file: RemoteFile,
    val text: String?,
    val isImage: Boolean,
    val content: ContentInfo? = null,
) {
    /** Absolute address of the target, for display and for the text actions. */
    val url: String get() = file.address
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
class WebRendererSession(
    val document: HtmlDocument,
    internal val host: RenderHost,
) : RendererSession {
    /**
     * A live document is loaded by its own origin; a projected one is served
     * through [ASSET_HOST] so relative resources can be resolved through the
     * node graph.
     */
    private val liveBase: String? = document.remoteBase

    /** True when the page's bytes live at a network origin. */
    val isRemote: Boolean get() = liveBase != null

    var initialUrl: String = liveBase ?: "https://$ASSET_HOST/$DOCUMENT_PATH"
        private set

    /** Text address of the document itself (provider path plus view levels). */
    private val documentAddress: String = document.origin.let(LocationAddress::format)

    var address by mutableStateOf(liveBase ?: documentAddress)
        internal set

    /** Last page title; cleared as soon as a new document starts loading. */
    var pageTitle by mutableStateOf<String?>(null)
        private set

    /** 0..100, drives the slim progress bar above the page. */
    override var progress by mutableIntStateOf(0)
        internal set

    override var loading by mutableStateOf(true)
        internal set

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

    /** Link or image under the last long press; drives the renderer dialog. */
    var hitTarget by mutableStateOf<WebHitTarget?>(null)
        private set

    private var webView: WebView? = null
    private var darkTheme = true
    private var closing = false
    /** A page's beforeunload prompt waiting for the shared leave dialog. */
    private var pendingUnload: JsResult? = null
    /** URLs this session already decided to load itself. */
    private val approvedNavigations = mutableSetOf<String>()
    /** URLs this session moved to by itself (a retrace); the tab already knows. */
    private val selfDriven = mutableSetOf<String>()

    override val capabilities: RenderCapabilities
        get() = RenderCapabilities(
            // Back/forward are the tab's history, not the WebView's cache: the
            // session has nothing to advertise here.
            canRefresh = true,
            // A page is not a directory: no file creation, no PTY/terminal.
            canCreate = false,
            canTerminal = false,
        )

    override val consumesEdgeGestures: Boolean get() = true
    override val canDismiss: Boolean get() = true

    @Composable
    override fun Content(darkTheme: Boolean, modifier: Modifier) {
        WebContent(this, modifier)
    }

    /**
     * Point this session at [url], keeping its WebView.
     *
     * The address is recorded as approved, so the WebView's own navigation
     * callback recognises it as the page the route table just chose instead of
     * classifying it a second time (and asking the server again).
     */
    fun load(url: String) {
        pendingUnload?.cancel()
        pendingUnload = null
        hitTarget = null
        initialUrl = url
        address = url
        approvedNavigations += url
        selfDriven.clear()
        // Keep the old title until the new document reports one; a navigation
        // should not blank the path bar while the page is still loading.
        loading = true
        progress = 0
        contentVisible = false
        probing = false
        webView?.loadUrl(url)
    }

    /**
     * Reposition the WebView to a page it already holds, so a history step
     * costs no request.
     *
     * The tab owns the history; this only moves the cache, so the landing is
     * not reported back as a new visit. Returns false when the page is not in
     * the list and has to be opened again through the route table.
     */
    fun retrace(address: String): Boolean {
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
        hitTarget = null
        val view = webView ?: return
        webView = null
        loading = false
        progress = 0
        contentVisible = false
        probing = false
        view.stopLoading()
        view.loadUrl("about:blank")
        view.destroy()
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

    override fun menuItems(context: Context): List<RendererMenuItem> = listOf(
        RendererMenuItem(
            title = context.getString(R.string.refresh),
            icon = Icons.Default.Refresh,
        ) { refresh() },
    )

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
                    // never reaches this branch.
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
                    // A projected document owns its own pages through ASSET_HOST.
                    if (uri.host == ASSET_HOST) return false
                    if (!BrowseAddress.isWebUrl(uri.toString())) return false
                    val url = uri.toString()
                    selfDriven.clear()
                    if (approvedNavigations.remove(url)) return false
                    // Same-document fragment jumps must not start a request.
                    val current = view?.url
                    if (current != null && stripFragment(current) == stripFragment(url)) return false
                    if (view == null) return false
                    // A link is just a node at an address: hand it to the same
                    // route table an address-bar open uses, with no facts. A page
                    // comes back as this session (reused and loaded), an archive
                    // or a file goes to its opener or viewer — this renderer
                    // decides nothing, and probing here would delay the very
                    // navigation the WebView can already start. The tab settles
                    // the guess against the server and reopens the node if it was
                    // not a page.
                    val file = httpFile(url, referer = current)
                    loading = true
                    this@WebRendererSession.progress = 0
                    host.openFile(file)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    updateAddress(url)
                    loading = true
                    this@WebRendererSession.progress = 0
                    contentVisible = false
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    contentVisible = true
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    updateAddress(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    updateAddress(url)
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
                // probe and the forward provider must carry.
                val file = httpFile(url, referer = target.url)
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
                    this@WebRendererSession.host.scope.launch {
                        val content = withContext(Dispatchers.IO) { file.describe().getOrNull() }
                        target.post {
                            this@WebRendererSession.updateHitTarget(url) { it.copy(content = content) }
                        }
                    }
                }
                true
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

    private fun updateAddress(url: String?) {
        // A closing session loads about:blank first; that is not a location.
        if (url == null || closing) return
        approvedNavigations.clear()
        val next = displayAddress(url)
        if (next == address) return
        address = next
        // A retrace lands on a stop the tab already made. Anything else is a
        // visit, and the tab decides what it becomes: a new stop, a stop it
        // already holds, or nothing. Keep the previous title while the new
        // document loads; onReceivedTitle replaces it.
        if (selfDriven.remove(next)) return
        host.onPageVisited(next)
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
        view.evaluateJavascript(script) { value -> onResult(decodeJsString(value)) }
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
}

class WebRendererFactory : RendererFactory {
    override val id: String = "web"

    override fun supports(target: RenderTarget): Boolean {
        val node = target.node
        val content = target.content
        if (content != null && !ContentTypes.isGenericMime(content.mime)) {
            val mime = content.mime ?: ContentTypes.fromName(node.name)
            return ContentTypes.isHtmlMime(mime) ||
                node.name.endsWith(".html", ignoreCase = true) ||
                node.name.endsWith(".htm", ignoreCase = true) ||
                node.name.endsWith(".xhtml", ignoreCase = true)
        }
        // No usable type: only a live address can still be a page, because the
        // server answers for it (and a typed address must never fall through to
        // a file chooser just because the probe was inconclusive). A projected
        // file without facts has nothing to show.
        return node is RemoteFile
    }

    override suspend fun create(
        target: RenderTarget,
        host: RenderHost,
    ): Result<RendererSession> = runCatching {
        val file = target.node as? VFile ?: error("web renderer requires a file")
        WebRendererSession(document = HtmlDocument(file), host = host)
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
                        it.visibility = if (settled) View.GONE else View.VISIBLE
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
                            if (session.settled) View.GONE else View.VISIBLE
                    }
                }
            },
        )
    }
    // The dialog lives next to the WebView, so a long press never has to
    // leave Chromium's hit test result behind to reach Compose.
    session.hitTarget?.let { target ->
        WebHitDialog(
            target = target,
            host = session.host,
            onDismiss = session::dismissHitTarget,
        )
    }
}

/**
 * Long-press dialog for a link or an image: the address on top, then five
 * actions. "Copy" and "Share" act on the resource itself ([WebHitTarget] is an
 * on-demand [RemoteFile], served to the receiver through the forward
 * provider), while the other three act on the text the page carries.
 */
@Composable
private fun WebHitDialog(
    target: WebHitTarget,
    host: RenderHost,
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
                    host.copyFile(target.file, target.content)
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
                    host.shareFile(target.file, target.content)
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
