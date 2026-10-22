package io.furryr.file.daemon.ipc

import io.furryr.file.proto.Response
import java.io.FileDescriptor

/**
 * One daemon response. [fds] holds every SCM_RIGHTS descriptor that rode the
 * frame; the receiver owns them and must consume or close them.
 */
data class DaemonResponse(
    val response: Response,
    val fds: List<FileDescriptor> = emptyList(),
) {
    val attachedFd: FileDescriptor? get() = fds.firstOrNull()
}
