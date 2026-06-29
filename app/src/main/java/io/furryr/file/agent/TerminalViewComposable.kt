package io.furryr.file.agent

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

@Composable
fun TerminalViewComposable(
    session: TerminalSession?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val context = LocalContext.current
    val density = LocalDensity.current

    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var fontSizeDp by remember { mutableIntStateOf(prefs.getInt("terminal_font_size", 9).coerceIn(8, 128)) }
    var fontPath by remember { mutableStateOf(prefs.getString("terminal_font", "") ?: "") }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "terminal_font_size" -> fontSizeDp = prefs.getInt("terminal_font_size", 9).coerceIn(8, 128)
                "terminal_font" -> fontPath = prefs.getString("terminal_font", "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val fontSizePx = remember(fontSizeDp) { with(density) { fontSizeDp.dp.toPx() }.roundToInt() }
    val typeface = remember(fontPath) {
        fontPath.takeIf { it.isNotEmpty() }?.let {
            try { Typeface.createFromFile(it) } catch (e: Exception) { null }
        }
    }

    AndroidView(
        factory = { context ->
            val terminalView = TerminalView(context, null)

            val wrapper = object : FrameLayout(context) {
                override fun onCheckIsTextEditor(): Boolean = true

                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                        EditorInfo.IME_FLAG_NO_ENTER_ACTION
                    return TerminalInputConnection(this, terminalView)
                }

                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    return terminalView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
                }
            }

            terminalView.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = true
                setTerminalViewClient(TerminalViewClientImpl(this, wrapper, fontSizeDp))
                setTextSize(fontSizePx)
                setBackgroundColor(backgroundColor)
            }

            wrapper.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                addView(terminalView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            val listener: (TerminalSession) -> Unit = { terminalView.onScreenUpdated() }
            SessionManager.addTextChangedListener(listener)
            wrapper.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    SessionManager.removeTextChangedListener(listener)
                }
            })

            session?.let { terminalView.attachSession(it) }
            terminalView.isEnabled = enabled
            terminalView.post { terminalView.onScreenUpdated() }

            wrapper
        },
        update = { view ->
            val tv = (view as FrameLayout).getChildAt(0) as? TerminalView ?: return@AndroidView
            tv.setTextSize(fontSizePx)
            if (typeface != null) tv.setTypeface(typeface)
            if (tv.mClient is TerminalViewClientImpl) {
                (tv.mClient as TerminalViewClientImpl).fontSizeDp = fontSizeDp
            }
            if (tv.currentSession !== session) {
                session?.let { tv.attachSession(it) }
                view.requestFocus()
                tv.onScreenUpdated()
            }
            tv.isEnabled = enabled
            tv.setBackgroundColor(backgroundColor)
        },
        modifier = modifier
    )
}

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

private fun writeToTerminal(session: TerminalSession, text: CharSequence) {
    var i = 0
    val len = text.length
    while (i < len) {
        val cp = Character.codePointAt(text, i)
        i += Character.charCount(cp)
        if (cp == '\n'.code) {
            session.writeCodePoint(false, '\r'.code)
        } else {
            session.writeCodePoint(false, cp)
        }
    }
}

private const val DEFAULT_FONT_SIZE_DP = 9
private const val MIN_FONT_SIZE_DP = 8
private const val MAX_FONT_SIZE_DP = 128

private class TerminalInputConnection(
    view: View,
    private val terminalView: TerminalView
) : BaseInputConnection(view, true) {

    init {
        val e = getEditable()
        if (e != null) e.append(PLACEHOLDER)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        val result = super.commitText(text, newCursorPosition)
        if (text.isNotEmpty()) {
            val session = terminalView.currentSession
            if (session != null) writeToTerminal(session, text)
        }
        resetToPlaceholder()
        return result
    }

    override fun finishComposingText(): Boolean {
        val result = super.finishComposingText()
        resetToPlaceholder()
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        return terminalView.dispatchKeyEvent(event)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        repeat(beforeLength) { terminalView.dispatchKeyEvent(deleteKey) }
        val result = super.deleteSurroundingText(beforeLength, afterLength)
        val e = getEditable()
        if (e != null && e.isEmpty()) e.append(PLACEHOLDER)
        return result
    }

    private fun resetToPlaceholder() {
        beginBatchEdit()
        val e = getEditable()
        if (e != null) {
            e.clear()
            e.append(PLACEHOLDER)
        }
        setSelection(0, 0)
        endBatchEdit()
    }

    companion object {
        private const val PLACEHOLDER = "\u200B"
    }
}

private class TerminalViewClientImpl(
    private val view: TerminalView,
    private val wrapper: View,
    initialFontSizeDp: Int = DEFAULT_FONT_SIZE_DP,
) : TerminalViewClient {

    var fontSizeDp = initialFontSizeDp.coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val delta = if (scale > 1.0f) 2 else -2
            fontSizeDp = (fontSizeDp + delta).coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)
            view.setTextSize((fontSizeDp * view.resources.displayMetrics.density).roundToInt())
        }
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val session = view.currentSession
        val isMouseTracking = session?.emulator?.isMouseTrackingActive() ?: false
        if (!isMouseTracking) {
            wrapper.requestFocus()
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(wrapper, InputMethodManager.SHOW_IMPLICIT)
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
