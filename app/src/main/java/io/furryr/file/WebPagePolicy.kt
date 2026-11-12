package io.furryr.file

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes

/**
 * Whether an address belongs to the browser renderer, decided from what is
 * already known before any request.
 *
 * The address is the only free signal. Spending a round trip on the server's
 * type first pushes the page's first byte back by exactly that round trip, so
 * the tab hands the address to the browser renderer immediately and settles the
 * question against the answer it gets ([Verdict]). The rules live here, not in
 * the renderer: a renderer that asked the server what it was could never be
 * handed a page nobody has identified yet.
 */
object WebPagePolicy {
    enum class Verdict {
        /** The browser renderer owns the address. */
        PAGE,

        /** Nothing decisive yet; the body sniff has to answer. */
        UNDECIDED,

        /** A concrete type a browser cannot show: hand it back to the table. */
        NOT_PAGE,
    }

    /**
     * The address alone says "page", so no request is worth spending on the way
     * to the renderer.
     *
     * A page name, or no name at all (an extensionless article, a directory
     * style address), is the common case and always wins. A name the shared
     * table already maps to a concrete non-page type — a `.zip`, a `.pdf`, an
     * image — keeps the eager probe, so a link to an archive opens as an
     * archive on the first tap instead of flashing a browser tab and reopening.
     */
    fun likelyPage(address: String): Boolean {
        val name = nameOf(address)
        if (isPageName(name)) return true
        val byName = ContentTypes.fromName(name) ?: return true
        return ContentTypes.isHtmlMime(byName)
    }

    /**
     * What [content] says about the browser renderer's ownership.
     *
     * [settled] marks facts that came from the body (a magic-byte sniff), not
     * from headers alone: a header can mislabel a page as `text/plain` or
     * octet-stream, a sniffed body cannot.
     */
    fun verdict(content: ContentInfo?, settled: Boolean): Verdict {
        val mime = content?.mime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (ContentTypes.isHtmlMime(mime)) return Verdict.PAGE
        // No usable type, a generic one, or the classic mislabelling of a page
        // as plain text: only the name, and after it the body, can decide.
        val vague = mime == null || ContentTypes.isGenericMime(mime) || mime == "text/plain"
        if (vague) {
            if (isPageName(content?.name)) return Verdict.PAGE
            return if (settled) Verdict.NOT_PAGE else Verdict.UNDECIDED
        }
        return Verdict.NOT_PAGE
    }

    private val pageSuffixes = listOf(".html", ".htm", ".xhtml")

    private fun isPageName(name: String?): Boolean {
        val value = name?.trim()?.lowercase() ?: return false
        return pageSuffixes.any { value.endsWith(it) }
    }

    /** Last path segment of [address]: no query, no fragment, no trailing slash. */
    private fun nameOf(address: String): String =
        address.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
}
