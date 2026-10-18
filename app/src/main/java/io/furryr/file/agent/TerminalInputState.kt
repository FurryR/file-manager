package io.furryr.file.agent

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.view.TerminalView

class TerminalInputState {
    private var ctrlState by mutableStateOf(false)
    private var altState by mutableStateOf(false)
    private var shiftState by mutableStateOf(false)
    var ctrl: Boolean
        get() = ctrlState
        set(value) { val old = directInput; ctrlState = value; notifyMode(old) }
    var alt: Boolean
        get() = altState
        set(value) { val old = directInput; altState = value; notifyMode(old) }
    var shift: Boolean
        get() = shiftState
        set(value) { val old = directInput; shiftState = value; notifyMode(old) }

    internal val directInput: Boolean get() = ctrl || alt || shift
    internal var onInputModeChanged: (() -> Unit)? = null

    private fun notifyMode(old: Boolean) {
        if (old != directInput) onInputModeChanged?.invoke()
    }

    internal var terminalView: TerminalView? = null

    fun sendKey(keyCode: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    internal fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = terminalView?.takeIf { it.isEnabled } ?: return false
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val text = event.characters ?: return false
            if (view.currentSession == null) return false
            commitText(text)
            return true
        }
        val handled = view.dispatchKeyEvent(event)
        // Termux reads modifiers more than once while processing a key.
        if (handled && event.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(event.keyCode)) {
            resetModifiers()
        }
        return handled
    }

    internal fun commitText(text: CharSequence) {
        val view = terminalView?.takeIf { it.isEnabled && it.currentSession != null } ?: return
        view.stopTextSelectionMode()
        var i = 0
        while (i < text.length) {
            var codePoint = Character.codePointAt(text, i)
            i += Character.charCount(codePoint)
            if (codePoint == '\n'.code) codePoint = '\r'.code
            if (shift) codePoint = Character.toUpperCase(codePoint)
            view.inputCodePoint(codePoint, ctrl, alt)
            resetModifiers()
        }
    }

    fun resetModifiers() {
        ctrl = false
        alt = false
        shift = false
    }
}
