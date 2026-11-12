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

    override suspend fun peek(): Result<ContentInfo> = HttpProvider.peek(address, referer)

    override fun withReferer(referer: String?): VFile =
        if (referer.isNullOrBlank() || referer == this.referer) this else HttpFile(address, referer)

    override suspend fun open(mode: OpenMode): Result<FileSource> =
        HttpProvider.source(address, referer, mode)
}
