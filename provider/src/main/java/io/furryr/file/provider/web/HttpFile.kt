package io.furryr.file.provider.web

import io.furryr.file.core.ContentInfo
import io.furryr.file.core.Path
import io.furryr.file.core.RemoteFile
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/**
 * One remote HTTP(S) resource as a node.
 *
 * It is a [RemoteFile], so a document renderer can load [address] itself and
 * let the platform resolve the page's own subresources; for every other
 * consumer it is a [io.furryr.file.core.VFile] whose bytes come from
 * [HttpProvider]. [referer] is the page the resource was reached from and is
 * replayed with every request.
 *
 * A URL has no browsable [parent]: relative resources of a *document* are
 * resolved by the platform against [address], and there is nothing to list.
 */
internal class HttpFile(
    override val address: String,
    override val referer: String? = null,
) : RemoteFile {
    override val name: String = WebRequest.displayName(address)

    override val parent: VFolder? get() = null

    override val path: Path = Path.parse(address)

    override suspend fun stat(): Result<ProviderStat> = HttpProvider.stat(address, referer)

    override suspend fun describe(): Result<ContentInfo> = HttpProvider.describe(address, referer)

    override suspend fun open(mode: OpenMode): Result<FileSource> =
        HttpProvider.source(address, referer, mode)
}

/**
 * The node for a live [address].
 *
 * Two callers know the page a link came from — the browser renderer (a link
 * hit test) and the forward provider (a shared link) — so they mint the node
 * here instead of rebuilding the address text. Every other entry point goes
 * through `Vfs.node(address)` and gets the same node without a referer.
 */
fun httpFile(address: String, referer: String? = null): RemoteFile = HttpFile(address, referer)

/**
 * [this] as a node that replays [referer].
 *
 * Only a live file has a request context; a storage file is returned unchanged,
 * so a caller can carry a page's identity through a share without asking what
 * kind of address it holds.
 */
fun VFile.withReferer(referer: String?): VFile =
    if (referer.isNullOrBlank() || this !is RemoteFile) this else httpFile(address, referer)

/**
 * The type the server declares, from a HEAD only.
 *
 * The browser renderer may already be loading [address]; this answers "is that
 * really a page?" without spending a body request on it, and without the
 * magic-byte probe [describe] falls back to.
 */
suspend fun RemoteFile.peek(): Result<ContentInfo> = HttpProvider.peek(address, referer)
