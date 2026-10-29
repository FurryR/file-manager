package io.furryr.file.agent

/**
 * Transport-facing agent facade. :hub owns the socket; every AI operation ends
 * here, so the hub never implements provider or tool semantics.
 */
object AgentService {
    suspend fun catalog(): String = AiService.catalog()
}
