package io.furryr.file.provider.web

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
class HttpProvider(
    private val webSchemes: Set<String>,
) : FileProvider {
    override val schemes: Set<String> get() = webSchemes

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
                    mime = facts?.mime,
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
        runCatching { HttpFile(this, innerPath) }

    override fun liveFile(address: String, referer: String?): VFile = HttpFile(this, address, referer)

    private fun readOnly(path: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$path is read-only"))
}
