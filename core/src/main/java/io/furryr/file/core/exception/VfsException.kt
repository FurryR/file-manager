package io.furryr.file.core.exception

/**
 * Base for failures raised by the core VFS and its providers.
 *
 * A caller that can recover from a specific case catches the derived type;
 * everything else can catch [VfsException] without knowing the provider.
 */
open class VfsException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
