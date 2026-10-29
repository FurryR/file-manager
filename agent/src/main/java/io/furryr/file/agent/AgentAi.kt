package io.furryr.file.agent

/** App-facing AI facade; provider details stay inside :agent. */
object AgentAi {
    /** Fetch the normalized model catalog JSON for the active provider. */
    suspend fun catalogJson(): String = AiService.catalog()
}
