package io.furryr.file.provider.remote

import java.util.UUID

/** One SSH server. SSH has no AList endpoint, token or directory password. */
data class SshLocation(
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "",
    override val basePath: String = "/",
    override val sortOrder: Int = 0,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /** Resolved from [identityId] when that identity holds a password. */
    val password: String = "",
    /** Resolved from [identityId] when that identity holds a private key. */
    val privateKey: String = "",
    val passphrase: String = "",
    val hostKey: String = "",
    /** App-owned identity reference; byte material is resolved by the location source. */
    val identityId: String = "",
) : RemoteLocation {
    override val scheme: String get() = RemoteSchemes.SSH

    override val pathAuthority: String get() = buildString {
        append(username).append('@')
        // IPv6 literals need brackets in a URI authority, otherwise the
        // address colons read as a port separator.
        if (':' in host) append('[').append(host).append(']') else append(host)
        if (port != 22) append(':').append(port)
    }

    override val uri: String get() = "$scheme://$pathAuthority${normalizeRemotePath(basePath)}"

    /**
     * SSH starts at the server root: the configured base path is a default
     * directory, not the connection root.
     */
    override val entryUri: String get() = "$scheme://$pathAuthority/"

    override fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9-]+")) && name.isNotBlank()) {
            "A connection name is required"
        }
        require(basePath.startsWith('/') && '\u0000' !in basePath) {
            "The starting directory must be an absolute path"
        }
        require(host.isNotBlank() &&
            host.none { it.isWhitespace() || it == '/' || it == '\u0000' } &&
            port in 1..65535 && username.isNotBlank()) {
            "SSH host, port and username are required"
        }
    }

    override fun subtitle(): String = "SSH · $username@$host:$port"

    override fun rootEntryName(): String = pathAuthority

    override fun withName(name: String): SshLocation = copy(name = name)
    override fun withSortOrder(sortOrder: Int): SshLocation = copy(sortOrder = sortOrder)
}
