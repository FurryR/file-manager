package io.furryr.file.provider.web

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
    private val http: HttpProvider,
    override val address: String,
    override val referer: String? = null,
) : RemoteFile {
    override val name: String = WebRequest.displayName(address)

    override val parent: VFolder? get() = null

    override val path: Path = Path.parse(address)

    override suspend fun stat(): Result<ProviderStat> =
        http.stat(address, referer).map { stat ->
            // The server's type is authoritative. Never infer a web page's
            // type from its name: a dynamic `.asp`/`.php` URL is HTML, not
            // whatever an extension table might guess.
            val mime = stat.mime
                ?: "text/html".takeIf { address.startsWith("https://", ignoreCase = true) }
            if (mime == null) stat else stat.copy(mime = mime)
        }

    override fun withReferer(referer: String?): VFile =
        if (referer.isNullOrBlank() || referer == this.referer) this else HttpFile(http, address, referer)

    override suspend fun open(mode: OpenMode): Result<FileSource> =
        http.source(address, referer, mode)
}
