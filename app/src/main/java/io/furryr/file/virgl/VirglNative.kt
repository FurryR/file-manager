package io.furryr.file.virgl

/**
 * JNI bridge to the per-instance VirGL host library.
 *
 * This class is loaded in the dedicated `:virgl` process only.  Each handle is
 * an independent renderer instance bound to the thread that serves one accepted
 * vtest connection.
 */
internal object VirglNative {
    init {
        System.loadLibrary("virglhost")
    }

    external fun create(): Long

    /** Blocking: serve one accepted client fd.  Native takes ownership of [fd]. */
    external fun serve(handle: Long, fd: Int): Int

    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun error(handle: Long): String?
}
