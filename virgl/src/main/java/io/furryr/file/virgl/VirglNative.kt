package io.furryr.file.virgl

/**
 * JNI bridge to the VirGL host library.
 *
 * This class is loaded in the dedicated `:virgl` process only.  The host owns
 * one vtest listener and one renderer; every accepted connection is served by
 * the same native thread.
 */
internal object VirglNative {
    init {
        System.loadLibrary("virglhost")
    }

    external fun create(): Long

    /** Bind the vtest listener; returns 0 on success. */
    external fun start(handle: Long, socketPath: String): Int

    /** No listener: clients are queued through [addClient]. Returns 0 on success. */
    external fun startClients(handle: Long): Int

    /** Hand a connected client socket to the host; ownership transfers on success. */
    external fun addClient(handle: Long, fd: Int): Int

    /** Blocking: serve all connections until [stop] is called. */
    external fun run(handle: Long): Int

    external fun stop(handle: Long)
    external fun destroy(handle: Long)
    external fun error(handle: Long): String?
}
