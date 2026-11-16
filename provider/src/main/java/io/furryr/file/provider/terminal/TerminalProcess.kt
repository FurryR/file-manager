package io.furryr.file.provider.terminal

/**
 * One spawned terminal backend: the bytes going in, the bytes coming out and
 * the lifetime controls a terminal tab needs.
 *
 * It replaces the old "give Termux a PTY fd" contract. A local/container
 * process wraps its daemon PTY; an SSH shell wraps the channel streams
 * directly, so no socketpair or relay threads are needed.
 */
interface TerminalProcess {
    /** Container this shell runs in, or null for host and remote shells. */
    val containerName: String? get() = null

    /** Fill [buffer]; -1 ends the stream. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Write to the backend. */
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Apply a new terminal size. */
    fun resize(rows: Int, cols: Int)

    /** Block until the backend exits and return its status. */
    fun awaitExit(): Int

    /** Release the backend. Idempotent. */
    fun close()
}
