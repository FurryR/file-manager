package io.furryr.file.daemon

import io.furryr.file.proto.Response
import java.io.FileDescriptor

data class DaemonResponse(val response: Response, val attachedFd: FileDescriptor? = null)
