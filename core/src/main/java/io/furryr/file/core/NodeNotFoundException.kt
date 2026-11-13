package io.furryr.file.core

/**
 * A provider answered that the address does not exist. Callers that create a
 * destination on demand (copy targets) may create only for this failure; a
 * transport, permission or availability error must surface instead.
 */
class NodeNotFoundException(message: String, cause: Throwable? = null) : Exception(message, cause)
