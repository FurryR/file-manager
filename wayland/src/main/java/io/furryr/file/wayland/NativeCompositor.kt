package io.furryr.file.wayland

/**
 * JNI bridge to the Smithay compositor (`native/wayland`, packaged as
 * `libwayland_compositor.so`).
 *
 * Unlike the previous wlroots stack this compositor owns the output scale:
 * [nativeStart] and [nativeSetScale] take the device pixel ratio straight from
 * `DisplayMetrics`, and the value reaches clients through `wl_output` plus
 * `wp_fractional_scale_v1`.
 *
 * All calls are cheap and thread-safe: the native side only enqueues commands
 * for its own event loop. Coordinates are physical pixels of the viewer surface
 * unless noted otherwise.
 */
internal object NativeCompositor {
    init {
        // Smithay dlopens `libEGL.so.1`; Android only ships `libEGL.so`. The
        // shim claims that soname, so it must be in the process before any EGL
        // call is made.
        System.loadLibrary("eglshim")
        System.loadLibrary("wayland_compositor")
    }

    /**
     * Start the compositor thread. Returns null on success, otherwise the reason
     * the compositor refused to start (already running, EGL failure, missing
     * xkb data, socket bind failure, …).
     *
     * @param runtimeDir directory that receives the `wayland-0` socket; also
     *   used as `XDG_RUNTIME_DIR` by clients and by the keymap tempfile.
     * @param xkbRoot extracted xkeyboard-config tree (`XKB_CONFIG_ROOT`).
     * @param scale device pixel ratio (densityDpi / 160).
     */
    external fun nativeStart(
        runtimeDir: String,
        xkbRoot: String,
        width: Int,
        height: Int,
        scale: Float,
    ): String?

    external fun nativeStop()

    /** Attach the viewer's `Surface`; size may be 0 to use the window size. */
    external fun nativeAttachSurface(surface: android.view.Surface, width: Int, height: Int)

    external fun nativeDetachSurface()

    /** Output size in physical pixels after a resize or rotation. */
    external fun nativeResize(width: Int, height: Int)

    /** Device pixel ratio; updates `wl_output` and every tracked surface. */
    external fun nativeSetScale(scale: Float)

    /** [action] is `MotionEvent.getActionMasked()`, [id] the pointer id. */
    external fun nativeTouch(action: Int, id: Int, x: Float, y: Float, time: Long)

    external fun nativePointerMotion(x: Float, y: Float, time: Long)

    external fun nativePointerButton(button: Int, pressed: Boolean, time: Long)

    external fun nativePointerScroll(dx: Float, dy: Float, time: Long)

    /** [keycode] is an Android `KeyEvent.getKeyCode()` value. */
    external fun nativeKey(keycode: Int, pressed: Boolean, time: Long)

    /** Final text from the Android IME (`commitText`). */
    external fun nativeTextInputCommit(text: String)

    /** Composing text; an empty string clears the preedit. */
    external fun nativeTextInputPreedit(text: String, cursorBegin: Int, cursorEnd: Int)

    /** Backspace/delete from the IME, in code points. */
    external fun nativeTextInputDelete(before: Int, after: Int)

    /** Set by the viewer to show/hide the IME as the focused client enables a text field. */
    @Volatile
    @JvmStatic
    var textInputListener: ((Boolean) -> Unit)? = null

    /**
     * Called from the compositor thread when the focused client starts or stops
     * accepting text input (i.e. `zwp_text_input_v3.enable`/`disable`).
     */
    @JvmStatic
    fun onTextInputEnabled(enabled: Boolean) {
        // Called from the native compositor thread; views may only be touched
        // on the main thread.
        mainHandler.post { textInputListener?.invoke(enabled) }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
}
