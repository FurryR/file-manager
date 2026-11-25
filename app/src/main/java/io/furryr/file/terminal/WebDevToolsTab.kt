package io.furryr.file.terminal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.furryr.file.R
import io.furryr.file.core.model.FileEntry
import io.furryr.file.devtools.DevToolsServer
import io.furryr.file.devtools.DevToolsTargets
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "WebDevTools"

/** The page a DevTools tab is attached to. */
internal interface WebDevToolsHost {
    val devToolsAlive: Boolean

    /** The WebView target; created on demand when the tab opens first. */
    fun devToolsView(context: Context): WebView?
}

/**
 * The DevTools tab opened from a web page.
 *
 * It runs the official Chrome DevTools frontend in a WebView. The frontend is
 * served from a loopback HTTP server in `:app`, which also proxies its CDP
 * WebSocket to the WebView's own remote-debugging socket. No PTY is involved;
 * the tab owns its DevTools WebView and a per-tab frontend endpoint.
 */
internal class WebDevToolsTab(
    private val host: WebDevToolsHost,
    private val label: () -> String,
) : TerminalTab {
    override val id: String = UUID.randomUUID().toString()
    override val title: String get() {
        val base = label()
        return if (host.devToolsAlive) base else "$base (closed)"
    }

    /** True once the tab ended; the page must build a fresh one. */
    internal val isDisposed: Boolean get() = disposed

    private var server: DevToolsServer? = null
    private var inspectorUrl by mutableStateOf<String?>(null)
    private var baseUrl by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var attaching by mutableStateOf(false)
    private var webView: WebView? = null
    private var webViewUrl: String? = null
    /** Theme applied to the current page; null forces the next load to apply it. */
    private var webViewDark: Boolean? = null
    /** App theme at the last composition; a settling page loads in this one. */
    private var currentDark = true
    /** Invalidates an attach that was in flight when the host went away. */
    private var attachEpoch = 0
    private var disposed = false
    /**
     * Opens the shell's terminal drawer. Stored from the last [enter] context
     * because the gesture lives in the WebView's own touch stream.
     */
    private var openDrawer: (() -> Unit)? = null
    /**
     * Own coroutine so the tab unloads itself when its page is gone, even if
     * the terminal sheet is hidden or another tab is active.
     */
    private val lifecycle = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        lifecycle.launch {
            // WebRendererSession.close() flips this when the page is released;
            // the DevTools target it owned no longer exists, so the tab is dead.
            // Discard (not the shell's close path): a terminal sheet that the
            // user is opening at that moment must stay open.
            snapshotFlow { host.devToolsAlive }.first { !it }
            Terminal.discard(id)
        }
    }

    override fun acceptDrop(entry: FileEntry): Boolean = false

    override suspend fun enter(ctx: TerminalContext) {
        // No body for a dead page; Terminal discards the tab because enter
        // returned without committing.
        if (!host.devToolsAlive) return
        openDrawer = ctx::requestOpenDrawer
        ctx.commit { enabled, modifier -> DevToolsBody(enabled, modifier) }
    }

    override fun blur() {
        val view = webView ?: return
        view.clearFocus()
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** Drop the WebView and endpoint; the next entry reattaches. */
    override fun reset() = closeResources()

    override fun dispose() {
        disposed = true
        openDrawer = null
        lifecycle.cancel()
        closeResources()
    }

    // Legacy window resizing is still the correct IME mode for target SDK 28.
    @Suppress("DEPRECATION")
    @Composable
    private fun DevToolsBody(enabled: Boolean, modifier: Modifier) {
        val context = LocalContext.current
        val activity = context as? Activity
        var retry by remember { mutableIntStateOf(0) }
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

        DisposableEffect(activity, enabled) {
            if (enabled) {
                activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onDispose {
                if (enabled) {
                    activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }

        LaunchedEffect(host.devToolsAlive, retry) {
            if (!host.devToolsAlive) {
                closeResources()
                return@LaunchedEffect
            }
            attach(context, dark)
        }

        val currentUrl = inspectorUrl
        val currentBase = baseUrl
        val failure = error
        when {
            !host.devToolsAlive -> Box(
                modifier = modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.devtools_page_closed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            failure != null -> DevToolsFailure(
                detail = failure,
                onRetry = {
                    closeResources()
                    retry++
                },
                modifier = modifier,
            )
            currentUrl == null || currentBase == null || attaching -> DevToolsLoading(modifier)
            else -> DevToolsWebView(currentUrl, currentBase, dark, modifier)
        }
    }

    private suspend fun attach(context: Context, darkTheme: Boolean) {
        if (disposed || inspectorUrl != null) return
        val epoch = attachEpoch
        attaching = true
        error = null
        currentDark = darkTheme
        try {
            val view = withContext(Dispatchers.Main) { host.devToolsView(context) }
                ?: throw IllegalStateException("page is closed")
            val pageUrl = withContext(Dispatchers.Main) { view.url ?: view.originalUrl }
            val pageTitle = withContext(Dispatchers.Main) { view.title }
            val marker = UUID.randomUUID().toString()
            withContext(Dispatchers.Main) { setMarker(view, marker) }
            val nextServer = withContext(Dispatchers.IO + NonCancellable) {
                DevToolsServer(context, darkTheme)
            }
            try {
                val target = withContext(Dispatchers.IO) {
                    DevToolsTargets.findTarget(
                        pageUrl = pageUrl,
                        pageTitle = pageTitle,
                        marker = marker,
                    )
                }
                if (disposed || epoch != attachEpoch || !currentCoroutineContext().isActive) {
                    nextServer.close()
                    return
                }
                server = nextServer
                inspectorUrl = nextServer.inspectorUrl(target.id)
                baseUrl = nextServer.baseUrl
            } catch (failure: Throwable) {
                nextServer.close()
                throw failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "devtools attach failed", failure)
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            attaching = false
        }
    }

    private suspend fun setMarker(view: WebView, marker: String) {
        withTimeoutOrNull(1_500) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val script = "window.__fileManagerDevToolsMarker=${JSONObject.quote(marker)}"
                view.evaluateJavascript(script) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    @Composable
    private fun DevToolsWebView(url: String, baseUrl: String, dark: Boolean, modifier: Modifier) {
        val surface = MaterialTheme.colorScheme.surface.toArgb()
        key(url) {
            AndroidView(
                modifier = modifier.fillMaxSize().clipToBounds(),
                factory = { context ->
                    DrawerEdgeLayout(context) { openDrawer?.invoke() }.apply {
                        clipChildren = true
                        setBackgroundColor(surface)
                    }
                },
                update = { holder ->
                    holder.setBackgroundColor(surface)
                    currentDark = dark
                    // Keep the served bootstrap current, so a frontend reload
                    // (or a language reload) starts in the app theme too.
                    server?.darkTheme = dark
                    val view = ensureView(holder.context, url, baseUrl)
                    if (view.parent !== holder) {
                        (view.parent as? ViewGroup)?.removeView(view)
                        holder.addView(
                            view,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                    applyTheme(view, dark)
                },
                onRelease = { holder ->
                    val view = webView
                    if (view != null && view.parent === holder) holder.removeView(view)
                },
            )
        }
    }

    /**
     * Apply the app theme to the frontend: it stores the choice in the
     * `ui-theme` setting for later loads and paints with a root class now.
     */
    private fun applyTheme(view: WebView, dark: Boolean) {
        if (webViewDark == dark) return
        webViewDark = dark
        val theme = if (dark) "dark" else "default"
        val scheme = if (dark) "dark" else "light"
        val script = buildString {
            append("(function(){try{")
            append("var d=document.documentElement;")
            append("if(d){d.classList.toggle('theme-with-dark-background',")
            append(dark)
            append(");d.style.colorScheme='")
            append(scheme)
            append("';}")
            append("localStorage.setItem('ui-theme',JSON.stringify('")
            append(theme)
            append("'));")
            append("}catch(e){}})()")
        }
        view.evaluateJavascript(script, null)
    }

    private fun ensureView(context: Context, url: String, baseUrl: String): WebView {
        val existing = webView
        if (existing != null && webViewUrl == url) return existing
        existing?.let(::destroyView)

        val created = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    if (target.startsWith(baseUrl)) return false
                    if (target.startsWith("http://") || target.startsWith("https://")) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                        }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val target = view ?: return
                    if (target !== this@WebDevToolsTab.webView) return
                    // The bootstrap script picks the starting theme; this
                    // re-applies the one the app has now, even if it changed
                    // while the frontend was still loading.
                    webViewDark = null
                    applyTheme(target, currentDark)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) return
                    this@WebDevToolsTab.error = error?.description?.toString()
                        ?: "DevTools frontend failed to load"
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    val message = if (detail?.didCrash() == true) {
                        "DevTools renderer crashed"
                    } else {
                        "DevTools renderer was killed"
                    }
                    // A renderer-gone WebView cannot be reused; drop it on the
                    // next main-loop turn, then keep the error so Retry works.
                    this@WebDevToolsTab.error = message
                    view?.post {
                        this@WebDevToolsTab.reset()
                        this@WebDevToolsTab.error = message
                    }
                    return true
                }
            }
            webChromeClient = WebChromeClient()
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        webView = created
        webViewUrl = url
        webViewDark = null
        created.loadUrl(url)
        return created
    }

    private fun closeResources() {
        attachEpoch++
        val view = webView
        webView = null
        webViewUrl = null
        webViewDark = null
        if (view != null) destroyView(view)
        server?.close()
        server = null
        inspectorUrl = null
        baseUrl = null
        error = null
        attaching = false
    }

    private fun destroyView(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.loadUrl("about:blank")
        view.destroy()
    }

    @Composable
    private fun DevToolsLoading(modifier: Modifier) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.devtools_connecting),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun DevToolsFailure(
        detail: String,
        onRetry: () -> Unit,
        modifier: Modifier,
    ) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.devtools_unavailable),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.devtools_retry))
            }
        }
    }
}

/**
 * Holder for the DevTools WebView.
 *
 * A WebView frontend consumes touch events, and Compose cannot observe them.
 * The holder sees the whole subtree in [dispatchTouchEvent] before the
 * Chromium view does; a long right drag opens the terminal tab drawer, the
 * same gesture a Termux tab uses.
 */
private class DrawerEdgeLayout(
    context: Context,
    private val onOpenDrawer: () -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val drawerThreshold = 48 * resources.displayMetrics.density
    private var tracking = false
    private var triggered = false
    private var startX = 0f
    private var startY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                triggered = false
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (tracking && !triggered) {
                val dx = event.x - startX
                val dy = event.y - startY
                if (dx > drawerThreshold && dx > abs(dy) * 1.5f) {
                    triggered = true
                    tracking = false
                    onOpenDrawer()
                } else if (dx < -touchSlop || abs(dy) > touchSlop * 2) {
                    tracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return super.dispatchTouchEvent(event)
    }
}
