package io.furryr.file.provider.remote

import java.net.URI
import java.util.UUID

/** One AList server. AList has no host key, private key or shell. */
data class AlistLocation(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "",
    override val basePath: String = "/",
    override val sortOrder: Int = 0,
    val endpoint: String = "",
    val username: String = "",
    /** Resolved from [identityId] by the location source; never persisted inline. */
    val password: String = "",
    val token: String = "",
    val directoryPassword: String = "",
    /** App-owned password identity reference. */
    val identityId: String = "",
) : RemoteLocation {
    override val scheme: String get() = RemoteSchemes.ALIST
    override val pathAuthority: String get() = id
    override val uri: String get() = "$scheme://$pathAuthority${normalizeRemotePath(basePath)}"
    override val entryUri: String get() = uri

    override fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9-]+")) && name.isNotBlank()) {
            "A connection name is required"
        }
        require(basePath.startsWith('/') && '\u0000' !in basePath) {
            "The starting directory must be an absolute path"
        }
        val url = runCatching { URI(endpoint) }.getOrNull()
        require(url != null && url.scheme in setOf("http", "https") && !url.host.isNullOrBlank() &&
            url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null) {
            "Invalid AList server URL"
        }
    }

    override fun subtitle(): String = "AList · $endpoint"

    override fun iconUrl(): String? = runCatching {
        val endpoint = endpoint.trim()
        val base = URI(if (endpoint.endsWith('/')) endpoint else "$endpoint/")
        base.resolve("favicon.ico")?.toString()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrNull()

    override fun rootEntryName(): String = name

    override fun withName(name: String): AlistLocation = copy(name = name)
    override fun withSortOrder(sortOrder: Int): AlistLocation = copy(sortOrder = sortOrder)
}
