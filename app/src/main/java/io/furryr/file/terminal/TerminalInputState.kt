package io.furryr.file.terminal

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

    /** The surface currently showing this tab; rebound on every attach. */
    internal var inputView: TerminalInputView? = null
    internal var terminalView: TerminalView? = null

    /**
     * A session that connects before the shared surface has bound itself asks
     * for the IME immediately. Store the request and replay it in [bind], or
     * the first terminal in a tab stays unfocused until its view is rebuilt.
     */
    private var imePending = false

    /**
     * True between [blur] and the next [bind]. An attach/focus callback is a
     * recovery path for a state that never bound; a blurred state must not use
     * it to reclaim the shared wrapper, or the surface would keep typing into
     * the tab that is no longer on screen.
     */
    private var blurred = false

    fun requestIme() {
        val view = inputView
        if (view != null) view.requestIme() else imePending = true
    }

    /** Focus the surface without raising the IME (used when the sheet opens). */
    internal fun requestFocus() {
        inputView?.requestFocus()
    }

    private fun notifyMode(old: Boolean) {
        if (old != directInput) inputView?.onModeChanged()
    }

    /**
     * The view/wrapper now on screen for this tab. Called for every
     * composition and every tab switch, so the row and the IME never talk to
     * an old tab's surface.
     */
    internal fun bind(view: TerminalInputView, terminal: TerminalView) {
        // One surface serves every tab; detach the tab it served before, so an
        // inactive tab cannot keep a pointer to the live wrapper.
        val previous = view.inputState
        if (previous !== this) previous.unbind(view)
        inputView = view
        blurred = false
        view.bindState(this)
        if (terminalView !== terminal) resetModifiers()
        terminalView = terminal
        // A session can connect before the surface exists; replay the request
        // now that there is a wrapper to receive it.
        if (imePending) {
            imePending = false
            view.requestIme()
        }
    }

    /** Attach/focus recovery; a blurred tab never reclaims a shared surface. */
    internal fun rebind(view: TerminalInputView, terminal: TerminalView) {
        if (blurred) return
        bind(view, terminal)
    }

    /** The surface left composition; only clear the binding it still owns. */
    internal fun unbind(view: TerminalInputView) {
        if (inputView === view) {
            inputView = null
            terminalView = null
            resetModifiers()
        }
    }

    /**
     * The tab stopped being current: drop the surface binding, keep focus.
     *
     * The wrapper is shared by every tab, so its transient state belongs to the
     * tab that is leaving: a pending IME request, an open editor and Termux's
     * text-selection mode (which pins focus and the IME on the terminal view)
     * must be retired before the pointer is dropped, or the next tab inherits
     * them and the first tap only closes the stale selection instead of
     * summoning the keyboard.
     */
    internal fun blur() {
        imePending = false
        blurred = true
        inputView?.releaseBinding()
        inputView = null
        terminalView = null
        resetModifiers()
    }

    fun sendKey(keyCode: Int) {
        // Enter with a sticky modifier is a distinct key: Ctrl+Enter is the
        // conventional newline (Ctrl+J) and Alt+Enter is ESC+CR. Termux's key
        // handler ignores Ctrl on Enter, so the code point must be written
        // directly here instead of relying on the key-code path.
        if (keyCode == KeyEvent.KEYCODE_ENTER && (ctrl || alt)) {
            sendModifiedEnter()
            return
        }
        val meta = metaState()
        dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun sendModifiedEnter() {
        val view = terminalView?.takeIf { it.isEnabled && it.currentSession != null } ?: return
        view.stopTextSelectionMode()
        val codePoint = if (ctrl) '\n'.code else '\r'.code
        view.inputCodePoint(codePoint, ctrl, alt)
        resetModifiers()
    }

    private fun metaState(): Int {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return meta
    }

    // ACTION_MULTIPLE/characters are deprecated since API 29 (the IME should
    // commit text), but older keyboards and IMEs still deliver a batch this way.
    @Suppress("DEPRECATION")
    internal fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = terminalView?.takeIf { it.isEnabled } ?: return false
        // Termux drops Ctrl on Enter, so an IME's Enter key event must take
        // the same path as the extra-key row: Ctrl+Enter is LF.
        if (event.keyCode == KeyEvent.KEYCODE_ENTER && (ctrl || alt)) {
            if (event.action == KeyEvent.ACTION_DOWN) sendModifiedEnter()
            return true
        }
        // Termux's view has no Paste handler; keyboards that paste with the
        // dedicated key still expect the terminal's own paste path.
        if (event.keyCode == KeyEvent.KEYCODE_PASTE) {
            if (event.action == KeyEvent.ACTION_DOWN) pasteFromClipboard()
            return true
        }
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

    /**
     * The terminal's paste path (bracketed paste, CR normalization, control
     * characters stripped), shared by the selection menu, the input method's
     * Paste command and the Paste key.
     */
    internal fun pasteFromClipboard() {
        val view = terminalView?.takeIf { it.isEnabled && it.currentSession != null } ?: return
        view.stopTextSelectionMode()
        view.currentSession?.onPasteTextFromClipboard()
    }

    internal fun commitText(text: CharSequence) {
        val view = terminalView?.takeIf { it.isEnabled && it.currentSession != null } ?: return
        view.stopTextSelectionMode()
        var i = 0
        while (i < text.length) {
            var codePoint = Character.codePointAt(text, i)
            i += Character.charCount(codePoint)
            // IMEs that commit a newline instead of sending Enter must still
            // honour Ctrl+Enter = LF, matching the extra-key row.
            if (codePoint == '\n'.code || codePoint == '\r'.code) {
                codePoint = if (ctrl) '\n'.code else '\r'.code
            }
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
