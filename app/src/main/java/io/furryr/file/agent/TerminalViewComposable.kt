package io.furryr.file.agent

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Jetpack Compose wrapper for Termux [TerminalView].
 *
 * Used in two modes:
 * - Interactive terminal embedded in [AgentScreen] (fullscreen, input-enabled).
 * - Read-only snippets inside [OutputBlockView] for block-model display.
 *
 * @param session The TerminalSession to attach, or null for an empty placeholder.
 * @param modifier Standard Compose modifier applied to the AndroidView.
 * @param enabled When false the view ignores touch/key input (snippet mode).
 */
@Composable
fun TerminalViewComposable(
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // Capture theme color outside factory/update lambdas (non-Composable context).
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()

    AndroidView(
        factory = { context ->
            val view = TerminalView(context, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setTerminalViewClient(TerminalViewClientImpl(this))
                setTextSize(DEFAULT_FONT_SIZE_DP)
                setBackgroundColor(backgroundColor)
            }

            val listener: (TerminalSession) -> Unit = { view.onScreenUpdated() }
            SessionManager.addTextChangedListener(listener)
            view.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    SessionManager.removeTextChangedListener(listener)
                }
            })

            session?.let { view.attachSession(it) }
            view.isEnabled = enabled
            view.post { view.onScreenUpdated() }

            view
        },
        update = { view ->
            if (view.currentSession !== session) {
                session?.let { view.attachSession(it) }
                view.requestFocus()
                view.onScreenUpdated()
            }
            view.isEnabled = enabled
            view.setBackgroundColor(backgroundColor)
        },
        modifier = modifier
    )
}

/**
 * Read-only terminal snippet for inline block-model display.
 *
 * Renders a portion of the terminal session buffer without accepting input.
 * The caller should have scrolled the terminal to the desired region
 * before embedding this composable (e.g. via [TerminalSession.scrollTo]).
 *
 * @param session The TerminalSession whose buffer to display.
 * @param modifier Standard Compose modifier.
 */
@Composable
fun TerminalSnippet(
    session: TerminalSession?,
    modifier: Modifier = Modifier
) {
    TerminalViewComposable(
        session = session,
        modifier = modifier,
        enabled = false
    )
}

/** Default terminal font size in density-independent pixels. */
private const val DEFAULT_FONT_SIZE_DP = 24

/** Minimum / maximum font size allowed by pinch-zoom. */
private const val MIN_FONT_SIZE_DP = 8
private const val MAX_FONT_SIZE_DP = 128

/**
 * [TerminalViewClient] implementation that:
 * - enables pinch-to-zoom font resizing (like Termux)
 * - brings up the soft keyboard on tap when the view is interactive
 * - provides conservative defaults for all other callbacks
 */
private class TerminalViewClientImpl(
    private val view: TerminalView
) : TerminalViewClient {

    /** Current font size in dp; mirrors the value passed to [TerminalView.setTextSize]. */
    private var fontSize = DEFAULT_FONT_SIZE_DP

    override fun onScale(scale: Float): Float {
        // Ignore tiny jitter; scale meaningfully only when the user clearly
        // pinches or spreads.  Resetting the returned factor to 1.0 makes
        // each gesture independent.
        if (scale < 0.9f || scale > 1.1f) {
            val delta = if (scale > 1.0f) 2 else -2
            fontSize = (fontSize + delta).coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)
            view.setTextSize(fontSize)
        }
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val session = view.currentSession
        val isMouseTracking = session?.emulator?.isMouseTrackingActive() ?: false
        // Only show keyboard when NOT in alternate screen mode (vim/less/nano),
        // so scrollback gestures aren't intercepted by the keyboard.
        if (!isMouseTracking) {
            view.requestFocus()
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean =
        false

    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}
