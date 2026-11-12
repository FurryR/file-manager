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
 * One open-with rule. [open] returns null to let the next rule try; any
 * non-null result wins. Claiming a request and handling it are the same step,
 * so a rule never resolves the same thing twice.
 */
interface OpenRule<C, R> {
    val id: String

    suspend fun open(request: OpenRequest<C>): R?
}

/**
 * Ordered open-with table. It owns precedence only: what an outcome means and
 * how it is applied belongs to the caller, so `:opener` stays independent of
 * UI, renderers and the `:core` VFS route.
 */
class OpenRouter<C, R> {
    private val entries = mutableListOf<Pair<Int, OpenRule<C, R>>>()

    @Synchronized
    fun register(priority: Int, rule: OpenRule<C, R>) {
        entries.removeAll { it.second.id == rule.id }
        entries += priority to rule
        entries.sortByDescending { it.first }
    }

    @Synchronized
    fun unregister(id: String) {
        entries.removeAll { it.second.id == id }
    }

    @Synchronized
    private fun rules(): List<OpenRule<C, R>> = entries.map { it.second }

    suspend fun route(request: OpenRequest<C>): R? {
        for (rule in rules()) {
            rule.open(request)?.let { return it }
        }
        return null
    }
}
