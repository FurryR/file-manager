package io.furryr.file.wayland

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.text.InputType
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A display/input attachment to the resident host; never owns compositor lifetime. */
class WaylandView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var attachedSurface: Surface? = null
    private var scope: CoroutineScope? = null
    private var imeReader: Job? = null
    private val input get() = WaylandManager.input
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
        readImeRequests()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        WaylandManager.attach(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    private fun detachSurface() {
        imeReader?.cancel()
        imeReader = null
        attachedSurface?.let(WaylandManager::detach)
        attachedSurface = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main + Job())
    }

    override fun onDetachedFromWindow() {
        detachSurface()
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    fun toggleKeyboard() {
        requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val target = input ?: return false
                val value = text?.toString() ?: return false
                if (target.hasActiveTextInput()) {
                    target.commitText(value)
                } else {
                    val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray())
                    if (events == null) target.commitText(value)
                    else events.forEach { forwardKey(it) }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                input?.deleteSurroundingText(beforeLength, afterLength)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                input?.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = forwardKey(event)
        }
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
        val target = input ?: return false
        target.sendKeyEvent(event.keyCode, event.action, event.eventTime)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = input ?: return false
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            target.sendPointerMotion(event.x, event.y, event.eventTime)
            val button = when {
                event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> 0x111
                event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> 0x112
                else -> 0x110
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.sendPointerButton(button, 0, event.eventTime)
                MotionEvent.ACTION_UP -> target.sendPointerButton(button, 1, event.eventTime)
            }
            return true
        }
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                target.sendTouchEvent(event.getPointerId(index), MotionEvent.ACTION_DOWN,
                    event.getX(index), event.getY(index), event.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                target.sendTouchEvent(event.getPointerId(index), MotionEvent.ACTION_UP,
                    event.getX(index), event.getY(index), event.eventTime)
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL ->
                repeat(event.pointerCount) { i ->
                    target.sendTouchEvent(event.getPointerId(i), event.actionMasked,
                        event.getX(i), event.getY(i), event.eventTime)
                }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val target = input ?: return super.onGenericMotionEvent(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                target.sendPointerMotion(event.x, event.y, event.eventTime)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                target.sendPointerScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.eventTime)
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }

    private fun readImeRequests() {
        imeReader?.cancel()
        val fd = input?.getImePipeFd() ?: return
        if (fd < 0) return
        imeReader = scope?.launch(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.fromFd(fd).use { descriptor ->
                    val poll = StructPollfd().apply {
                        this.fd = descriptor.fileDescriptor
                        events = OsConstants.POLLIN.toShort()
                    }
                    val buffer = ByteArray(1)
                    while (isActive) {
                        poll.revents = 0
                        if (Os.poll(arrayOf(poll), 250) <= 0) continue
                        if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                        if (Os.read(descriptor.fileDescriptor, buffer, 0, 1) <= 0) break
                        withContext(Dispatchers.Main) {
                            when (buffer[0].toInt().toChar()) {
                                'S' -> {
                                    requestFocus()
                                    imm.restartInput(this@WaylandView)
                                    imm.showSoftInput(this@WaylandView, InputMethodManager.SHOW_IMPLICIT)
                                }
                                'H' -> imm.hideSoftInputFromWindow(windowToken, 0)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (isActive) Log.w("WaylandView", "Cannot read compositor IME requests", error)
            }
        }
    }
}
