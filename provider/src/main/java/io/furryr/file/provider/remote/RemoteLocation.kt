package io.furryr.file.provider.remote

import java.io.IOException

/**
 * A stored remote connection. Only identity and the address contract are
 * shared; credentials and protocol facts belong to the concrete type.
 */
sealed interface RemoteLocation {
    val id: String
    val name: String
    val basePath: String
    val sortOrder: Int

    /** Protocol scheme; the provider that owns this connection registers it. */
    val scheme: String

    /** Authority used in URIs and provider routing. */
    val pathAuthority: String

    /** Address of the connection with its configured base path. */
    val uri: String

    /** Address opened when the connection is selected. */
    val entryUri: String

    fun validate()

    /** Drawer row subtitle. */
    fun subtitle(): String

    /** Drawer icon; null when the protocol has no site icon. */
    fun iconUrl(): String? = null

    /** Name shown for the connection row at its protocol root. */
    fun rootEntryName(): String

    fun withName(name: String): RemoteLocation
    fun withSortOrder(sortOrder: Int): RemoteLocation
}

/** Protocol schemes the app treats as stored remote connections. */
object RemoteSchemes {
    const val ALIST = "alist"
    const val SSH = "ssh"
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
