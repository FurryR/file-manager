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
    enabled: Boolean = true,
    inputState: TerminalInputState = remember { TerminalInputState() },
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

            val wrapper = TerminalInputView(context, inputState)

            terminalView.apply {
                // Text selection mode requests focus on the TerminalView itself.
                // Normal keyboard input still goes through the wrapper below.
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setTerminalViewClient(TerminalViewClientImpl(this, wrapper, inputState, fontSizeDp))
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
            inputState.terminalView = terminalView
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
                inputState.resetModifiers()
                session?.let { tv.attachSession(it) }
                if (enabled) view.requestFocus()
                tv.onScreenUpdated()
            }
            tv.isEnabled = enabled
            if (!enabled) inputState.resetModifiers()
            tv.setBackgroundColor(backgroundColor)
        },
        onRelease = { view ->
            (view as? TerminalInputView)?.releaseInput()
            if (inputState.terminalView === view.getChildAt(0)) {
                inputState.terminalView = null
                inputState.resetModifiers()
            }
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

private const val DEFAULT_FONT_SIZE_DP = 9
private const val MIN_FONT_SIZE_DP = 8
private const val MAX_FONT_SIZE_DP = 128

internal class TerminalInputView(context: Context, private val inputState: TerminalInputState) : FrameLayout(context) {
    private var connection: TerminalInputConnection? = null
    private var restartPending = false
    private var released = false
    private val modeChanged: () -> Unit = {
        // Retire the old editor before restartInput can flush stale candidates into it.
        connection?.closeConnection()
        if (hasFocus() && !restartPending) {
            restartPending = true
            post {
                restartPending = false
                if (hasFocus() && !released) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.restartInput(this)
                }
            }
        }
    }

    init { inputState.onInputModeChanged = modeChanged }

    fun releaseInput() {
        released = true
        if (inputState.onInputModeChanged === modeChanged) inputState.onInputModeChanged = null
        connection?.closeConnection()
        connection = null
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (inputState.directInput) InputType.TYPE_NULL else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        connection?.closeConnection()
        return TerminalInputConnection(this, inputState).also { connection = it }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputState.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
}

internal class TerminalInputConnection(
    view: View,
    private val inputState: TerminalInputState,
) : BaseInputConnection(view, true) {
    private var closed = false
    private var forwardedComposition: String? = null

    init {
        val e = getEditable()
        if (e != null) e.append(PLACEHOLDER)
    }

    fun cancelComposition() {
        forwardedComposition = null
        super.finishComposingText()
        resetToPlaceholder()
    }

    override fun closeConnection() {
        if (closed) return
        closed = true
        cancelComposition()
        super.closeConnection()
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (closed) return false
        val pending = text.toString()
        val forwarded = forwardedComposition
        forwardedComposition = null
        val result = super.commitText(text, newCursorPosition)
        // Text streamed during a composing update must not be sent twice. If the
        // IME commits a longer value, only forward the part that is still missing.
        val remaining = when {
            forwarded == null -> pending
            pending.startsWith(forwarded) -> pending.substring(forwarded.length)
            else -> pending
        }
        if (remaining.isNotEmpty()) inputState.commitText(remaining)
        resetToPlaceholder()
        return result
    }

    override fun finishComposingText(): Boolean {
        if (closed) return false
        if (forwardedComposition != null) {
            // Already streamed while a modifier was active; just clear the flag so
            // it cannot swallow the next composition.
            forwardedComposition = null
            val result = super.finishComposingText()
            resetToPlaceholder()
            return result
        }
        val e = getEditable()
        val start = e?.let { getComposingSpanStart(it) } ?: -1
        val end = e?.let { getComposingSpanEnd(it) } ?: -1
        if (start >= 0 && end > start) inputState.commitText(e!!.subSequence(start, end).toString())
        val result = super.finishComposingText()
        resetToPlaceholder()
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        return !closed && inputState.dispatchKeyEvent(event)
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (closed) return false
        if (inputState.directInput || forwardedComposition != null) {
            val pending = text.toString()
            val previous = forwardedComposition ?: ""
            val delta = if (pending.startsWith(previous)) pending.substring(previous.length) else pending
            forwardedComposition = pending
            if (delta.isNotEmpty()) inputState.commitText(delta)
            resetToPlaceholder()
            return true
        }
        return super.setComposingText(text, newCursorPosition)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (closed) return false
        val e = getEditable()
        // While the IME is composing, the text only exists in the editor buffer
        // and has not reached the terminal yet; deleting it must not send DEL.
        val composing = e != null && getComposingSpanStart(e) >= 0
        if (!composing) {
            val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
            repeat(beforeLength) { inputState.dispatchKeyEvent(deleteKey) }
        }
        val result = super.deleteSurroundingText(beforeLength, afterLength)
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
    private val inputState: TerminalInputState,
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
    override fun readControlKey(): Boolean = inputState.ctrl
    override fun readAltKey(): Boolean = inputState.alt
    override fun readShiftKey(): Boolean = inputState.shift
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
