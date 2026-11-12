package io.furryr.file.render

import io.furryr.file.core.Path

/**
 * Text form of a node address.
 *
 * The provider part is a plain path (`/storage/a.zip`, `container://alpine/x`,
 * `https://host/a.zip`) and every `!` after it opens one view level
 * (`/storage/a.zip!/docs/page.html`). Formatting and parsing are exact
 * inverses, so an address never has to be guessed back by probing the
 * filesystem — the boundary is written in the text.
 */
object NodeAddress {
    fun format(path: Path): String = path.toString()

    fun parse(text: String): Path = Path.parse(text)

    /**
     * True when [text] looks like an address rather than a search term. A
     * miss with this shape is a real filesystem miss, so the caller shows the
     * directory error state instead of falling back to a web search.
     */
    fun isAddress(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith('/')) return true
        if (!trimmed.contains("://")) return false
        return runCatching { Path.parse(trimmed).scheme != null }.getOrDefault(false)
    }
}
