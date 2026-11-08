package io.furryr.file.provider.remote

import io.furryr.file.core.source.FileSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.UUID

enum class RemoteKind(val scheme: String) { ALIST("alist"), SSH("ssh") }

data class RemoteLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: RemoteKind = RemoteKind.ALIST,
    val basePath: String = "/",
    val endpoint: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val token: String = "",
    val directoryPassword: String = "",
    val hostKey: String = "",
    val sortOrder: Int = 0,
) {
    /**
     * Authority used in URIs and provider routing.
     *
     * SSH connections use `user@host` (plus `:port` when it is not 22) so paths
     * stay readable in the UI and in agent tool calls. AList keeps the stored
     * connection id because the server has no user-qualified address.
     */
    val pathAuthority: String
        get() = when (kind) {
            RemoteKind.ALIST -> id
            RemoteKind.SSH -> buildString {
                append(username).append('@')
                // IPv6 literals need brackets in a URI authority, otherwise the
                // address colons read as a port separator.
                if (':' in host) append('[').append(host).append(']') else append(host)
                if (port != 22) append(':').append(port)
            }
        }

    val uri: String get() = "${kind.scheme}://$pathAuthority${normalizeRemotePath(basePath)}"

    /**
     * Entry point when the connection is opened. SSH starts at the server root:
     * the configured base path is a default directory, not the connection root.
     */
    val entryUri: String
        get() = if (kind == RemoteKind.SSH) "${kind.scheme}://$pathAuthority/"
        else uri

    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9-]+")) && name.isNotBlank()) { "A connection name is required" }
        require(basePath.startsWith('/') && '\u0000' !in basePath) { "The starting directory must be an absolute path" }
        if (kind == RemoteKind.ALIST) {
            val url = URI(endpoint)
            require(url.scheme in setOf("http", "https") && !url.host.isNullOrBlank() &&
                url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null) { "Invalid AList server URL" }
        } else {
            require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '\u0000' } &&
                port in 1..65535 && username.isNotBlank()) { "SSH host, port and username are required" }
        }
    }
    override fun toString(): String = "RemoteLocation($id, ${kind.scheme})"
}

internal data class RemoteEntry(
    val name: String, val isDirectory: Boolean, val size: Long = 0,
    val modifiedAt: Long = 0, val isSymlink: Boolean = false, val mode: Int = 0,
)

/** Capacity of the filesystem volume that holds a remote path. */
internal data class RemoteSpace(val totalBytes: Long, val usableBytes: Long)

internal interface RemoteBackend {
    fun list(path: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun download(path: String, destination: File)
    fun upload(path: String, source: File)
    fun createFile(path: String)
    fun createDirectory(path: String)
    fun delete(path: String)
    fun rename(path: String, newName: String)
    fun prepareReplacement(original: String, replacement: String) = Unit
    fun canWrite(path: String): Boolean = true

    /** Remaining capacity of the filesystem backing [path]; null when the backend cannot report it. */
    fun space(path: String): RemoteSpace? = null

    /** True when reads/writes can stream through live channels instead of staged cache files. */
    val streams: Boolean get() = false

    /** Sequential reader for [path]; only valid when [streams] is true. */
    fun openRead(path: String, size: Long): FileSource =
        throw UnsupportedOperationException("Backend cannot stream reads")

    /** Stream exactly [length] bytes from [source] into [path]; only valid when [streams] is true. */
    fun uploadStream(path: String, source: InputStream, length: Long): Unit =
        throw UnsupportedOperationException("Backend cannot stream writes")
}

internal fun backend(location: RemoteLocation): RemoteBackend = when (location.kind) {
    RemoteKind.ALIST -> AlistBackend(location)
    RemoteKind.SSH -> SshExecBackend(location)
}

internal fun normalizeRemotePath(path: String): String {
    require(path.startsWith('/') && '\u0000' !in path) { "Invalid remote path" }
    val segments = mutableListOf<String>()
    path.split('/').forEach { when (it) {
        "", "." -> Unit
        ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
        else -> segments.add(it)
    } }
    return "/" + segments.joinToString("/")
}

internal fun validName(name: String): String {
    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "Invalid file name" }
    return name
}

internal fun parentPath(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }
internal fun mutablePath(path: String): String = normalizeRemotePath(path).also {
    require(it != "/") { "Cannot modify a remote filesystem root" }
}
internal fun interrupted() { if (Thread.currentThread().isInterrupted) throw IOException("Operation cancelled") }
