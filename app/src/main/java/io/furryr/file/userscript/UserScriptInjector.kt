package io.furryr.file.userscript

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/**
 * Per-WebView userscript attachment.
 *
 * The document-start script is registered once, before the WebView loads its
 * first page. A provider that lacks `DOCUMENT_START_SCRIPT` falls back to
 * evaluating the same bootstrap in `onPageStarted`; that is later than the
 * page's own scripts but keeps the feature usable.
 */
class UserScriptAttachment internal constructor(
    private val view: WebView,
    private val handler: ScriptHandler?,
    private val bridge: UserScriptBridge,
    private val bootstrap: String,
) {
    fun onPageStarted() {
        if (handler != null) return
        runCatching { view.evaluateJavascript(bootstrap, null) }
    }

    fun detach() {
        bridge.release()
        runCatching { handler?.remove() }
        runCatching { view.removeJavascriptInterface(USER_SCRIPT_INTERFACE) }
    }
}

private const val USER_SCRIPT_INTERFACE = "androidUserScript"

object UserScriptInjector {
    @Volatile
    private var template: String? = null

    @SuppressLint("JavascriptInterface")
    fun attach(
        context: Context,
        view: WebView,
        onOpenUrl: (String) -> Unit,
    ): UserScriptAttachment? {
        val token = UUID.randomUUID().toString().replace("-", "")
        val script = runCatching {
            bootstrap(context).replace("__US_TOKEN__", token)
        }.getOrNull() ?: return null
        val bridge = UserScriptBridge(
            context = context.applicationContext,
            token = token,
            view = view,
            onOpenUrl = onOpenUrl,
        )
        runCatching { view.addJavascriptInterface(bridge, USER_SCRIPT_INTERFACE) }
            .onFailure { return null }
        val handler = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
            }.getOrNull()
        } else {
            null
        }
        return UserScriptAttachment(view, handler, bridge, script)
    }

    private fun bootstrap(context: Context): String {
        template?.let { return it }
        return synchronized(this) {
            template ?: context.assets.open("userscript/bootstrap.js")
                .bufferedReader()
                .use { it.readText() }
                .also { template = it }
        }
    }
}
