package io.furryr.file.core

import java.io.FileDescriptor

/** App-owned actions exposed through the hub, without app/provider dependencies. */
object HubActions {
    /**
     * Invoked on the hub's IO thread. The borrowed descriptor is valid only for
     * this call; resolve it synchronously before queuing any UI work. Throw to
     * return a failure to the client.
     */
    @Volatile
    var onHere: ((FileDescriptor) -> Unit)? = null

    /** Read the current settings for each request; never cache credentials. */
    @Volatile
    var aiConfigProvider: (() -> HubAiConfig)? = null
}

data class HubAiConfig(val baseUrl: String, val apiKey: String, val model: String)
