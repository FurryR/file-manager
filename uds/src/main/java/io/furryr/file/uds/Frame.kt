package io.furryr.file.uds

import android.system.Os
import java.io.FileDescriptor

/**
 * One transport payload together with any descriptors that rode it (SCM_RIGHTS).
 *
 * Ownership: descriptors on *inbound* frames (from [Transport.events]) belong to
 * the receiver. Take them (e.g. into a `FileSource`) or [close] them; dropping a
 * frame without doing either leaks descriptors. Descriptors passed to
 * [Transport.send] remain the sender's — the kernel duplicates them on send.
 */
class Frame(
    val payload: ByteArray,
    val fds: List<FileDescriptor> = emptyList(),
) {
    /** Close every attached descriptor. */
    fun close() {
        fds.forEach { runCatching { Os.close(it) } }
    }
}
