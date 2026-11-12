package io.furryr.file.provider.web

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.FileProvider
import io.furryr.file.core.Path
import io.furryr.file.core.ProviderFeature
import io.furryr.file.core.VFile
import io.furryr.file.core.VNode
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.StreamFileSource
import io.furryr.file.provider.ProviderSchemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * `http://` and `https://` as ordinary provider addresses.
 *
 * A URL is a single read-only file, so the whole pipeline above this module
 * never learns that "web" is special: [Vfs][io.furryr.file.core.Vfs] routes the
 * address here, this provider mints an [HttpFile] node, and the shared
 * opener/renderer table decides what to do with it — an archive link opens as a
 * folder, a page link opens in the browser renderer, everything else goes to
 * an "open with" chooser.
 *
 * Nothing here is listable, writable or copyable: the provider answers with
 * `Failure` and the directory/capability paths already treat that as an
 * error instead of inventing an empty directory.
 */
object HttpProvider : FileProvider {
    override val scheme: String get() = ProviderSchemes.HTTP

    /** Both schemes reach the same origin; `https` is not a second backend. */
    val schemes: Set<String> get() = ProviderSchemes.web

    /** No authority: the host is part of the URL, not a provider namespace. */
    override val authority: String? get() = null

    override val label: String get() = "Web"

    override val writable: Boolean get() = false

    override val features: Set<ProviderFeature>
        get() = setOf(ProviderFeature.STAT, ProviderFeature.OPEN)

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {}

    /** A URL has no children; the address is one resource. */
    override suspend fun list(path: String): Result<List<FileEntry>> =
        Result.failure(UnsupportedOperationException("$path is not a directory"))

    override suspend fun stat(path: String): Result<ProviderStat> = stat(path, null)

    suspend fun stat(path: String, referer: String?): Result<ProviderStat> =
        withContext(Dispatchers.IO) {
            runCatching {
                val facts = HttpFacts.head(path, WebRequest.headers(path, referer))
                ProviderStat(
                    exists = true,
                    isDirectory = false,
                    size = facts?.size?.coerceAtLeast(0L) ?: 0L,
                )
            }
        }

    /**
     * Content facts from the response itself, not from the address.
     *
     * A HEAD that names a decisive type is enough: a page (or a concrete
     * non-text type) needs no body request, so the renderer can hand the URL to
     * the WebView and the page is fetched exactly once. Everything else — an
     * extensionless download, a server that mislabels HTML as `text/plain` — is
     * probed for magic bytes.
     */
    suspend fun describe(path: String): Result<ContentInfo> = describe(path, null)

    suspend fun describe(path: String, referer: String?): Result<ContentInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val headers = WebRequest.headers(path, referer)
                val facts = HttpFacts.head(path, headers)
                val name = facts?.fileName ?: WebRequest.displayName(path)
                val declared = facts?.mime
                val decisive = ContentTypes.isHtmlMime(declared) ||
                    (!ContentTypes.isGenericMime(declared) && declared != "text/plain")
                if (facts != null && decisive) {
                    ContentTypes.describe(
                        name = name,
                        size = facts.size.coerceAtLeast(0L),
                        head = ByteArray(0),
                        seekable = false,
                        declaredMime = declared,
                    )
                } else {
                    val meta = HttpFacts.probe(path, headers)
                    ContentTypes.describe(
                        name = meta.fileName ?: name,
                        size = meta.size.coerceAtLeast(0L),
                        head = meta.head,
                        seekable = meta.seekable,
                        declaredMime = meta.mime,
                    )
                }
            }
        }

    /**
     * The type the server declares, from a HEAD only.
     *
     * The caller may already have the real request in flight — an optimistic
     * page load hands the address to the browser before it knows what the
     * address is — so nothing here may touch the body: it would be transferred
     * twice. A server that refuses `HEAD`, or names no concrete type, fails
     * instead of guessing, and the caller keeps what it already chose.
     */
    suspend fun peek(path: String, referer: String? = null): Result<ContentInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val facts = HttpFacts.head(path, WebRequest.headers(path, referer))
                    ?: error("$path answered no headers")
                ContentTypes.describe(
                    name = facts.fileName ?: WebRequest.displayName(path),
                    size = facts.size.coerceAtLeast(0L),
                    head = ByteArray(0),
                    seekable = false,
                    declaredMime = facts.mime,
                )
            }
        }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> =
        source(path, null, mode)

    /**
     * The bytes at [path]. [referer] is the page the resource was reached from
     * and rides along with the range probe and every block request, so a
     * hotlink-protected or session-bound server still answers.
     */
    suspend fun source(path: String, referer: String?, mode: OpenMode): Result<FileSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(mode == OpenMode.READ) { "web resources are read-only" }
                val headers = WebRequest.headers(path, referer)
                val meta = HttpFacts.probe(path, headers)
                val displayName = meta.fileName ?: WebRequest.displayName(path)
                if (meta.seekable && meta.size > 0L) {
                    HttpRangeSource(path, meta.size, headers, displayName)
                } else {
                    HttpStreamSource(
                        inner = StreamFileSource(
                            inputStream = HttpFacts.openStream(path, headers),
                            size = meta.size.coerceAtLeast(0L),
                            absolutePath = path,
                        ),
                        url = path,
                        name = displayName,
                    )
                }
            }
        }

    override suspend fun createFile(path: String): Result<Unit> = readOnly(path)

    override suspend fun createDirectory(path: String): Result<Unit> = readOnly(path)

    override suspend fun delete(path: String): Result<Unit> = readOnly(path)

    override suspend fun rename(path: String, newName: String): Result<Unit> = readOnly(path)

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        throw UnsupportedOperationException("web resources are read-only")
    }

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> =
        flow { throw UnsupportedOperationException("web resources are read-only") }

    /** `http` and `https` are different origins: the address arrives intact. */
    override val fullUriPaths: Boolean get() = true

    /**
     * The address is one live resource: the node carries the URL the renderer
     * must load and the referer its requests need.
     */
    override suspend fun node(path: Path, innerPath: String): Result<VNode> =
        runCatching { HttpFile(innerPath) }

    override fun liveFile(address: String, referer: String?): VFile = HttpFile(address, referer)

    private fun readOnly(path: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$path is read-only"))
}
