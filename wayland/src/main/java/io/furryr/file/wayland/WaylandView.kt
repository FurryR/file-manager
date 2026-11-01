package io.furryr.file.wayland

import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.text.InputType

/**
 * Viewer surface and input source for the resident compositor.
 *
 * Input is forwarded straight into the native compositor: touch and mouse stay
 * as `MotionEvent` payloads (the compositor converts physical pixels to logical
 * coordinates at the current scale) and keys travel as Android key codes, which
 * the native keymap translates to evdev before they reach the Wayland seat.
 */
class WaylandView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var attachedSurface: Surface? = null
    private var composing = ""
    /** True when the focused client enabled zwp_text_input_v3 (GTK/Qt widgets). */
    private var textInputActive = false
    private val imm get() = context.getSystemService(InputMethodManager::class.java)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachedSurface = holder.surface
        WaylandManager.attach(holder.surface, width, height)
        requestFocus()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        WaylandManager.attach(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    private fun detachSurface() {
        attachedSurface?.let(WaylandManager::detach)
        attachedSurface = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NativeCompositor.textInputListener = { enabled ->
            textInputActive = enabled
            if (enabled) showKeyboard()
        }
    }

    override fun onDetachedFromWindow() {
        NativeCompositor.textInputListener = null
        detachSurface()
        super.onDetachedFromWindow()
    }

    private fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun toggleKeyboard() {
        requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Always a real text editor: the IME must be free to compose (pinyin,
        // emoji), and the composed text is delivered afterwards either through
        // zwp_text_input_v3 or, for keyboard-only clients, as key events.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            // Text goes through zwp_text_input_v3 so the client sees real
            // commits and preedit instead of synthetic key events.
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString() ?: return false
                if (textInputActive) {
                    if (composing.isNotEmpty()) clearPreedit()
                    NativeCompositor.nativeTextInputCommit(value)
                } else {
                    // Terminals and other keyboard-only clients ignore
                    // text-input; type the characters as key events instead.
                    composing = ""
                    typeAsKeys(value)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString() ?: return false
                if (!textInputActive) return true
                composing = value
                NativeCompositor.nativeTextInputPreedit(value, value.length, value.length)
                return true
            }

            override fun finishComposingText(): Boolean {
                // IMEs in editor mode (fullEditor=true) type through
                // setComposingText + finishComposingText, not commitText.
                // Dropping the composing buffer here lost every letter for
                // keyboard-only clients like terminals.
                val value = composing
                composing = ""
                if (value.isEmpty()) return true
                if (textInputActive) {
                    NativeCompositor.nativeTextInputPreedit("", 0, 0)
                    NativeCompositor.nativeTextInputCommit(value)
                } else {
                    typeAsKeys(value)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (textInputActive) {
                    NativeCompositor.nativeTextInputDelete(beforeLength, afterLength)
                } else {
                    repeat(beforeLength) {
                        forwardKey(backspace(android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN))
                        forwardKey(backspace(android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP))
                    }
                }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean = deleteSurroundingText(beforeLength, afterLength)

            override fun sendKeyEvent(event: KeyEvent): Boolean = forwardKey(event)
        }
    }

    /** Turn characters into the key events a keyboard-only client understands. */
    private fun typeAsKeys(value: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(value.toCharArray())
        if (events == null) {
            // CJK, emoji and anything else without a keycode: the compositor
            // types it through generated `U+XXXX` keysyms instead.
            NativeCompositor.nativeTextInputCommit(value)
            return
        }
        events.forEach { forwardKey(it) }
    }

    private fun backspace(time: Long, action: Int) =
        KeyEvent(time, time, action, KeyEvent.KEYCODE_DEL, 0)

    private fun clearPreedit() {
        composing = ""
        NativeCompositor.nativeTextInputPreedit("", 0, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(event) || super.onKeyUp(keyCode, event)

    private fun forwardKey(event: KeyEvent): Boolean {
        if (event.keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH)) {
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        NativeCompositor.nativeKey(
            event.keyCode,
            event.action == KeyEvent.ACTION_DOWN,
            event.eventTime,
        )
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            val button = when {
                event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> 0x111
                event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> 0x112
                else -> 0x110
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE ->
                    NativeCompositor.nativePointerMotion(event.x, event.y, event.eventTime)
                MotionEvent.ACTION_DOWN ->
                    NativeCompositor.nativePointerButton(button, true, event.eventTime)
                MotionEvent.ACTION_UP ->
                    NativeCompositor.nativePointerButton(button, false, event.eventTime)
            }
            return true
        }
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                NativeCompositor.nativeTouch(
                    MotionEvent.ACTION_DOWN,
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    event.eventTime,
                )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                NativeCompositor.nativeTouch(
                    MotionEvent.ACTION_UP,
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    event.eventTime,
                )
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL ->
                repeat(event.pointerCount) { i ->
                    NativeCompositor.nativeTouch(
                        event.actionMasked,
                        event.getPointerId(i),
                        event.getX(i),
                        event.getY(i),
                        event.eventTime,
                    )
                }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                NativeCompositor.nativePointerMotion(event.x, event.y, event.eventTime)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                NativeCompositor.nativePointerScroll(
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble().let { (it * 15.0).toFloat() },
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble().let { (it * 15.0).toFloat() },
                    event.eventTime,
                )
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }
}
