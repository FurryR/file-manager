package io.furryr.file.core

/**
 * A structural view: a file that also stands in as a folder for `!` addresses.
 *
 * It is not a renderer and not an open-with entry: it draws nothing and the
 * chooser never lists it. [Vfs.node] asks its [Proxies] for the one proxy
 * registered for the file's MIME before any renderer exists.
 */
interface Proxy {
    suspend fun open(file: VFile): Result<VFolder>
}

/**
 * The proxy registry of one [Vfs] world. One MIME has one structural view, so
 * it has no priority list: the last registration replaces the previous one.
 *
 * A world owns its own registry; registering an archive view in a picker world
 * never changes the file manager's route table.
 */
class Proxies {
    private val entries = mutableMapOf<MimeType, Proxy>()

    @Synchronized
    fun register(mime: MimeType, proxy: Proxy) {
        val key = requireNotNull(ContentTypes.normalizeMime(mime)) { "invalid MIME: $mime" }
        entries[key] = proxy
    }

    @Synchronized
    fun unregister(mime: MimeType) {
        ContentTypes.normalizeMime(mime)?.let(entries::remove)
    }

    fun forMime(mime: MimeType?): Proxy? {
        val key = ContentTypes.normalizeMime(mime) ?: return null
        return synchronized(this) { entries[key] }
    }

    /** Resolve [file]'s stat once and enter its structural view. */
    suspend fun open(file: VFile): Result<VFolder> {
        val handled = file.stat()
        val mime = handled.getOrElse { return Result.failure(it) }.mime
        val proxy = forMime(mime)
            ?: return Result.failure(IllegalStateException("No proxy for ${file.name}"))
        return proxy.open(file)
    }
}
