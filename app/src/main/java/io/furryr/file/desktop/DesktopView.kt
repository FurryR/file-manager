package io.furryr.file.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Native canvas, mouse/touch input, pinch zoom and IME commits; no web surface. */
internal class DesktopView(context: Context) : View(context) {
    var client: RfbClient? = null
        set(value) { field = value; zoom = 1f; panX = 0f; panY = 0f; pressed.clear(); invalidate() }
    var panMode = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var pointerX = 0
    private var pointerY = 0
    private var dragX = 0f
    private var dragY = 0f
    private var twoFingerY = 0f
    private var multiTouch = false
    private var pendingClick = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var buttonMask = 0
    private val pressed = mutableMapOf<Int, Int>()
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { releasePointer(); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = effectiveScale()
            val originX = originX(); val originY = originY()
            val pointX = (detector.focusX - originX) / oldScale
            val pointY = (detector.focusY - originY) / oldScale
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            val currentScale = effectiveScale()
            val frame = client?.frame ?: return true
            panX = detector.focusX - pointX * currentScale - (width - frame.width * currentScale) / 2
            panY = detector.focusY - pointY * currentScale - (height - frame.height * currentScale) / 2
            constrainPan(); invalidate(); return true
        }
    })
    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(Color.BLACK) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = client?.frame ?: return
        if (frame.width == 0 || frame.height == 0) return
        constrainPan()
        val scale = effectiveScale()
        canvas.save(); canvas.translate(originX(), originY()); canvas.scale(scale, scale)
        frame.draw(canvas, paint); canvas.restore()
        canvas.drawCircle(originX() + pointerX * scale, originY() + pointerY * scale, 5f, pointerPaint)
    }
    private fun effectiveScale(): Float {
        val frame = client?.frame ?: return 1f
        return if (frame.width == 0 || frame.height == 0 || width == 0 || height == 0) 1f
        else min(width.toFloat() / frame.width, height.toFloat() / frame.height) * zoom
    }
    private fun originX() = (width - (client?.frame?.width ?: 0) * effectiveScale()) / 2 + panX
    private fun originY() = (height - (client?.frame?.height ?: 0) * effectiveScale()) / 2 + panY
    private fun constrainPan() {
        val frame = client?.frame ?: return
        val x = ((frame.width * effectiveScale() - width) / 2).coerceAtLeast(0f)
        val y = ((frame.height * effectiveScale() - height) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-x, x); panY = panY.coerceIn(-y, y)
    }
    fun fit() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    fun showKeyboard() { requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    fun specialKey(keysym: Int) { client?.key(keysym, true); client?.key(keysym, false) }
    fun modifier(keysym: Int, enabled: Boolean) { client?.key(keysym, enabled) }
    fun wheel(up: Boolean) { client?.pointer(pointerX, pointerY, if (up) 8 else 16); client?.pointer(pointerX, pointerY, 0) }
    fun rightClick() { client?.pointer(pointerX, pointerY, 4); client?.pointer(pointerX, pointerY, 0) }
    private fun point(event: MotionEvent, mask: Int) {
        val frame = client?.frame ?: return
        pointerX = ((event.x - originX()) / effectiveScale()).roundToInt().coerceIn(0, (frame.width - 1).coerceAtLeast(0))
        pointerY = ((event.y - originY()) / effectiveScale()).roundToInt().coerceIn(0, (frame.height - 1).coerceAtLeast(0))
        client?.pointer(pointerX, pointerY, mask); invalidate()
    }
    private fun releasePointer() { buttonMask = 0; client?.pointer(pointerX, pointerY, 0) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val state = event.buttonState
            val mask = (if (state and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or
                (if (state and MotionEvent.BUTTON_TERTIARY != 0) 2 else 0) or (if (state and MotionEvent.BUTTON_SECONDARY != 0) 4 else 0)
            point(event, if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) 0 else mask)
            return true
        }
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false; dragX = event.x; dragY = event.y
                downX = event.x; downY = event.y; pendingClick = !panMode
                // Delay button-down until a tap or single-finger drag is known,
                // so starting a pinch does not click the desktop underneath it.
                if (!panMode) point(event, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true; pendingClick = false; releasePointer()
                twoFingerY = (event.getY(0) + event.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val middleY = (event.getY(0) + event.getY(1)) / 2
                    if (!scale.isInProgress && abs(middleY - twoFingerY) >= 24 * resources.displayMetrics.density) {
                        wheel(middleY < twoFingerY); twoFingerY = middleY
                    }
                } else if (!multiTouch && !scale.isInProgress) {
                    if (panMode) { panX += event.x - dragX; panY += event.y - dragY; constrainPan(); invalidate() }
                    else {
                        if (buttonMask == 0 && pendingClick && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                            buttonMask = 1; pendingClick = false; client?.pointer(pointerX, pointerY, buttonMask)
                        }
                        if (buttonMask != 0) point(event, buttonMask)
                    }
                    dragX = event.x; dragY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pendingClick && !multiTouch && !panMode) point(event, 1)
                pendingClick = false; releasePointer(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pendingClick = false; releasePointer() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) { point(event, 0); return true }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            point(event, 0)
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vertical != 0f) wheel(vertical > 0)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (horizontal != 0f) { client?.pointer(pointerX, pointerY, if (horizontal < 0) 32 else 64); client?.pointer(pointerX, pointerY, 0) }
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = keysym(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        pressed[keyCode] = symbol; client?.key(symbol, true); return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = pressed.remove(keyCode) ?: keysym(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        client?.key(symbol, false); return true
    }
    private fun keysym(code: Int, event: KeyEvent): Int? = when (code) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        KeyEvent.KEYCODE_META_LEFT -> 0xffeb
        KeyEvent.KEYCODE_META_RIGHT -> 0xffec
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xffbe + code - KeyEvent.KEYCODE_F1
        else -> event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()).takeIf { it > 0 }
            ?.let { if (it <= 255) it else 0x01000000 or it }
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            private val composition = SpannableStringBuilder()
            override fun getEditable(): Editable = composition
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composition.replace(0, composition.length, text ?: ""); Selection.setSelection(composition, composition.length); return true
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                client?.text(text?.toString().orEmpty()); composition.clear(); Selection.setSelection(composition, 0); return true
            }
            override fun finishComposingText(): Boolean {
                if (composition.isNotEmpty()) { client?.text(composition.toString()); composition.clear(); Selection.setSelection(composition, 0) }
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composition.isNotEmpty()) {
                    var start = (composition.length - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
                    if (start in 1 until composition.length && Character.isLowSurrogate(composition[start]) && Character.isHighSurrogate(composition[start - 1])) start--
                    composition.delete(start, composition.length); Selection.setSelection(composition, composition.length)
                }
                else { repeat(beforeLength.coerceIn(0, 1024)) { specialKey(0xff08) }; repeat(afterLength.coerceIn(0, 1024)) { specialKey(0xffff) } }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                else -> false
            }
            override fun performEditorAction(actionCode: Int): Boolean { specialKey(0xff0d); return true }
        }
    }
}
