package io.furryr.file.core

/**
 * A provider endpoint exists but cannot answer right now: its backing service
 * is not running, not reachable, or has not been started yet.
 *
 * [endpoint] is an opaque provider fact (for SAF it is the content authority),
 * never a UI action. Renderers decide whether the failure gets a retry or a
 * "start the target app" affordance.
 */
class ProviderUnavailableException(
    val endpoint: String,
    cause: Throwable? = null,
) : IllegalStateException("Provider unavailable: $endpoint", cause)
