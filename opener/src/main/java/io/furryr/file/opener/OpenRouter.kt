package io.furryr.file.opener

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.VFile

/**
 * Facts every route sees. [C] is caller-supplied context (a render host, a
 * tab id, ...), so this module never imports an app type.
 */
data class OpenRequest<C>(
    val file: VFile,
    val content: ContentInfo?,
    val context: C,
)

/**
 * One open-with route. [open] returns null to let the next route try; any
 * non-null result wins.
 */
interface OpenRoute<C, R> {
    val id: String

    fun supports(request: OpenRequest<C>): Boolean

    suspend fun open(request: OpenRequest<C>): R?
}

/**
 * Ordered open-with table. It owns precedence only: what an outcome means and
 * how it is applied belongs to the caller, so `:opener` stays independent of
 * UI, renderers and the `:core` VFS route.
 */
class OpenRouter<C, R> {
    private val entries = mutableListOf<Pair<Int, OpenRoute<C, R>>>()

    @Synchronized
    fun register(priority: Int, route: OpenRoute<C, R>) {
        entries.removeAll { it.second.id == route.id }
        entries += priority to route
        entries.sortByDescending { it.first }
    }

    @Synchronized
    fun unregister(id: String) {
        entries.removeAll { it.second.id == id }
    }

    @Synchronized
    private fun routes(): List<OpenRoute<C, R>> = entries.map { it.second }

    suspend fun route(request: OpenRequest<C>): R? {
        for (route in routes()) {
            if (!route.supports(request)) continue
            route.open(request)?.let { return it }
        }
        return null
    }
}
