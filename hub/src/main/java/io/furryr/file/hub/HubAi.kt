package io.furryr.file.hub

/** App-facing AI facade; keeps the provider implementation internal to :hub. */
object HubAi {
    /** Fetch the normalized model catalog JSON for the active provider. */
    suspend fun catalogJson(): String = AiService.catalog()
}
