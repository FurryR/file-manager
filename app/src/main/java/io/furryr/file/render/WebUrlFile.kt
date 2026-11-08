package io.furryr.file.render

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.ViewRef
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.StreamFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * A remote HTTP(S) resource addressed by URL.
 *
 * It implements [VFile] so the normal opener/renderer pipeline can consume it
 * exactly like a local file: an archive link opens as a folder, a page link
 * opens in the WebView, and every other type goes to an "open with" chooser.
 * The decision follows the response `Content-Type` first, so a URL that looks
 * like a repository (`.git`) still opens as a page when the server serves
 * HTML. When the server honours `Range: bytes=...`, the handle performs
 * positional reads, so only the bytes an opener or viewer asks for are
 * transferred.
 *
 * [referer] is the page the link was followed from. Both the probe and the
 * byte requests carry it (plus the page's cookies) because many hosts answer
 * hotlinked or authenticated links only inside the original session.
 */
class WebUrlFile(
    val url: String,
    val referer: String? = null,
) : VFile {
    override val name: String = WebRequest.displayName(url)

    /** A URL has no Vfs parent; relative resources are not resolvable. */
    override val parent: VFolder? get() = null

    override fun ref(): ViewRef = ViewRef.Url(url)

    internal val metadata: WebUrlMeta by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WebUrlMeta.probe(url, headers)
    }

    private val headers: Map<String, String> by lazy {
        WebRequest.headers(url, referer)
    }

    /**
     * HEAD-only facts used to classify a link before committing to a body
     * request. A page is decided here, so a plain link is fetched once by the
     * WebView; a file link falls through to [describe], which needs the first
     * bytes and the range probe anyway.
     */
    internal fun peek(): WebUrlHead? = WebUrlMeta.head(url, headers)

    override suspend fun stat(): Result<ProviderStat> = withContext(Dispatchers.IO) {
        runCatching {
            val meta = metadata
            ProviderStat(
                exists = true,
                isDirectory = false,
                size = meta.size.coerceAtLeast(0L),
            )
        }
    }

    override suspend fun describe(): Result<ContentInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val meta = metadata
            ContentTypes.describe(
                name = meta.fileName ?: name,
                size = meta.size.coerceAtLeast(0L),
                head = meta.head,
                seekable = meta.seekable,
                declaredMime = meta.mime,
            )
        }
    }

    override suspend fun open(mode: OpenMode): Result<FileSource> = withContext(Dispatchers.IO) {
        runCatching {
            require(mode == OpenMode.READ) { "web resources are read-only" }
            val meta = metadata
            val displayName = meta.fileName ?: name
            if (meta.seekable && meta.size > 0L) {
                WebRangeFileSource(url, meta.size, headers, displayName)
            } else {
                WebStreamFileSource(
                    inner = StreamFileSource(
                        inputStream = WebUrlMeta.openStream(url, headers),
                        size = meta.size.coerceAtLeast(0L),
                        absolutePath = url,
                    ),
                    url = url,
                    name = displayName,
                )
            }
        }
    }

    companion object {
        fun isHtml(content: ContentInfo?, name: String): Boolean {
            val mime = content?.mime
            if (mime != null) return ContentTypes.isHtmlMime(mime)
            return hasHtmlExtension(name)
        }

        /** Cheap pre-check: a visible page extension never needs a probe. */
        fun hasHtmlExtension(url: String): Boolean {
            val path = runCatching { URI(url).path }.getOrNull() ?: return false
            return path.endsWith(".html", ignoreCase = true) ||
                path.endsWith(".htm", ignoreCase = true) ||
                path.endsWith(".xhtml", ignoreCase = true)
        }
    }
}

/** HEAD-only facts: enough to decide page vs file without a body request. */
internal data class WebUrlHead(
    val mime: String?,
    val size: Long,
    val fileName: String?,
)

/** Response facts needed by [WebUrlFile]; resolved once per instance. */
internal class WebUrlMeta(
    val size: Long,
    val mime: String?,
    val seekable: Boolean,
    val head: ByteArray,
    val fileName: String?,
) {
    companion object {
        private const val PROBE_BYTES = ContentTypes.SNIFF_BYTES
        private const val CACHE_TTL_MS = 30_000L
        private const val CACHE_ENTRIES = 32

        private data class Cached(val at: Long, val meta: WebUrlMeta)
        private data class HeadCached(val at: Long, val head: WebUrlHead?)

        /**
         * The dispatcher probes a link, then the forward provider probes it
         * again to answer `getType`/`query` for the viewer. A short cache turns
         * those repeats into one request pair without hiding a fresh response
         * for long.
         */
        private val cache = object : LinkedHashMap<String, Cached>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) =
                size > CACHE_ENTRIES
        }

        /** HEAD facts are reused by the full probe and by `getType`/`query`. */
        private val headCache = object : LinkedHashMap<String, HeadCached>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HeadCached>?) =
                size > CACHE_ENTRIES
        }

        fun head(url: String, headers: Map<String, String>): WebUrlHead? {
            val now = System.currentTimeMillis()
            synchronized(headCache) {
                headCache[url]?.let {
                    if (now - it.at <= CACHE_TTL_MS) return it.head
                }
            }
            val facts = headNetwork(url, headers)
            synchronized(headCache) { headCache[url] = HeadCached(now, facts) }
            return facts
        }

        fun probe(url: String, headers: Map<String, String>): WebUrlMeta {
            val now = System.currentTimeMillis()
            synchronized(cache) {
                cache[url]?.let {
                    if (now - it.at <= CACHE_TTL_MS) return it.meta
                }
            }
            val meta = probeNetwork(url, headers)
            synchronized(cache) { cache[url] = Cached(now, meta) }
            return meta
        }

        /** HEAD is cheap and many servers report everything needed here. */
        private fun headNetwork(url: String, headers: Map<String, String>): WebUrlHead? {
            var facts: WebUrlHead? = null
            runCatching {
                val connection = WebRequest.connection(url, "HEAD", headers)
                try {
                    val code = connection.responseCode
                    if (code in 200..299) {
                        facts = WebUrlHead(
                            mime = WebRequest.normalizeMime(connection.contentType),
                            size = connection.contentLengthLong,
                            fileName = WebRequest.fileName(
                                connection.getHeaderField("Content-Disposition"),
                            ),
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }
            return facts
        }

        private fun probeNetwork(url: String, headers: Map<String, String>): WebUrlMeta {
            val facts = head(url, headers)
            var size = facts?.size ?: -1L
            var mime = facts?.mime
            var fileName = facts?.fileName
            var rangeStatus = 0
            var total: Long? = null
            var head = ByteArray(0)

            // A small ranged GET both validates range support and supplies the
            // first bytes for magic-byte sniffing. Its headers win over HEAD:
            // a redirect may land on a different representation.
            runCatching {
                val connection = WebRequest.connection(
                    url,
                    "GET",
                    headers,
                    "bytes=0-${PROBE_BYTES - 1}",
                )
                try {
                    rangeStatus = connection.responseCode
                    mime = WebRequest.normalizeMime(connection.contentType) ?: mime
                    if (fileName == null) {
                        fileName = WebRequest.fileName(
                            connection.getHeaderField("Content-Disposition"),
                        )
                    }
                    if (size < 0) size = connection.contentLengthLong
                    if (rangeStatus == HttpURLConnection.HTTP_PARTIAL) {
                        total = parseContentRangeTotal(
                            connection.getHeaderField("Content-Range"),
                        )
                    }
                    head = readHead(connection.inputStream, PROBE_BYTES)
                } finally {
                    connection.disconnect()
                }
            }

            val exactSize = total ?: size
            val seekable = rangeStatus == HttpURLConnection.HTTP_PARTIAL &&
                exactSize > 0L
            return WebUrlMeta(
                size = exactSize,
                mime = mime,
                seekable = seekable,
                head = head,
                fileName = fileName,
            )
        }

        fun openStream(url: String, headers: Map<String, String>): InputStream =
            WebRequest.connection(url, "GET", headers).inputStream

        private fun parseContentRangeTotal(value: String?): Long? {
            if (value == null) return null
            val slash = value.lastIndexOf('/')
            if (slash < 0 || slash == value.lastIndex) return null
            return value.substring(slash + 1).trim().toLongOrNull()
        }

        private fun readHead(stream: InputStream, limit: Int): ByteArray {
            val buffer = ByteArray(limit)
            var filled = 0
            while (filled < limit) {
                val read = stream.read(buffer, filled, limit - filled)
                if (read <= 0) break
                filled += read
            }
            return if (filled == limit) buffer else buffer.copyOf(filled)
        }
    }
}

/**
 * Positional reader over HTTP range requests. Blocks are cached so opening a
 * zip reads its central directory once instead of per entry, and a large
 * preview only transfers the requested window.
 */
internal class WebRangeFileSource(
    private val url: String,
    override val size: Long,
    private val headers: Map<String, String> = emptyMap(),
    name: String = "",
) : FileSource() {
    override val readable: Boolean get() = true
    override val writable: Boolean get() = false
    override val seekable: Boolean get() = true
    override val absolutePath: String? get() = url

    private val lock = Any()
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > CACHE_BLOCKS
    }
    private var position = 0L
    private val transfer = WebTransfers.open(url, size, name, onDemand = true)

    override fun readAt(offset: Long, len: Int): ByteArray {
        if (len <= 0 || offset >= size) return ByteArray(0)
        val want = minOf(len.toLong(), size - offset).toInt()
        val out = ByteArray(want)
        var filled = 0
        while (filled < want) {
            val absolute = offset + filled
            val index = absolute / BLOCK_SIZE
            val block = synchronized(lock) { blocks[index] } ?: fetchBlock(index).also { bytes ->
                synchronized(lock) { blocks[index] = bytes }
            }
            val startInBlock = (absolute % BLOCK_SIZE).toInt()
            val take = minOf(block.size - startInBlock, want - filled)
            // Every cached block is complete. A short one would shift the
            // following bytes into the gap, so treat it as an I/O failure.
            if (take <= 0) throw IOException("short range block $index")
            System.arraycopy(block, startInBlock, out, filled, take)
            filled += take
        }
        return out
    }

    override fun writeAt(offset: Long, data: ByteArray) =
        throw UnsupportedOperationException("web resources are read-only")

    override fun seek(position: Long) {
        require(position >= 0) { "negative seek" }
        this.position = position
    }

    override fun truncate(newSize: Long) =
        throw UnsupportedOperationException("web resources are read-only")

    override fun close() {
        synchronized(lock) { blocks.clear() }
        WebTransfers.close(transfer)
    }

    private fun fetchBlock(index: Long): ByteArray {
        val start = index * BLOCK_SIZE
        val end = minOf(start + BLOCK_SIZE, size) - 1
        if (end < start) return ByteArray(0)
        WebTransfers.readStarted(transfer)
        var connection: HttpURLConnection? = null
        try {
            connection = WebRequest.connection(url, "GET", headers, "bytes=$start-$end")
            val code = connection.responseCode
            // `readAt` clamps every request to the advertised size, so a 416
            // here means the server no longer agrees with it.
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("server stopped honouring byte ranges ($code)")
            }
            val expected = (end - start + 1).toInt()
            val bytes = ByteArray(expected)
            var filled = 0
            connection.inputStream.use { stream ->
                while (filled < expected) {
                    val read = stream.read(bytes, filled, expected - filled)
                    if (read <= 0) break
                    filled += read
                    WebTransfers.advance(transfer, read)
                }
            }
            // A short body is a server/proxy failure, not EOF, and must never
            // be cached: later reads would splice the next block into the gap.
            if (filled != expected) {
                throw IOException("short range response: expected $expected, got $filled")
            }
            return bytes
        } finally {
            WebTransfers.readFinished(transfer)
            connection?.disconnect()
        }
    }

    private companion object {
        const val BLOCK_SIZE = 128L * 1024L
        const val CACHE_BLOCKS = 16
    }
}

/**
 * Stream-only HTTP body (server ignored `Range`) reported to [WebTransfers] as
 * a full download. Its reads are sequential, so the wrapper only counts bytes
 * and starts/stops the foreground notification around each block.
 */
private class WebStreamFileSource(
    private val inner: StreamFileSource,
    url: String,
    name: String,
) : FileSource() {
    private val transfer = WebTransfers.open(url, inner.size, name, onDemand = false)
    private var closed = false

    override val readable: Boolean get() = true
    override val writable: Boolean get() = false
    override val seekable: Boolean get() = false
    override val size: Long get() = inner.size
    override val absolutePath: String? get() = inner.absolutePath

    override fun readAt(offset: Long, len: Int): ByteArray {
        WebTransfers.readStarted(transfer)
        try {
            val bytes = inner.readAt(offset, len)
            WebTransfers.advance(transfer, bytes.size)
            return bytes
        } finally {
            WebTransfers.readFinished(transfer)
        }
    }

    override fun writeAt(offset: Long, data: ByteArray) =
        throw UnsupportedOperationException("web resources are read-only")

    override fun seek(position: Long) =
        throw UnsupportedOperationException("stream is not seekable")

    override fun truncate(newSize: Long) =
        throw UnsupportedOperationException("web resources are read-only")

    override fun close() {
        if (closed) return
        closed = true
        runCatching { inner.close() }
        WebTransfers.close(transfer)
    }
}
