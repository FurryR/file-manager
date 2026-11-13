package io.furryr.file.core

import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode

/**
 * A resolved virtual node.
 *
 * This is the currency of the UI and of every renderer: callers hold a
 * [VFile] or a [VFolder] and call [VNode.parent], [VFolder.child],
 * [VFolder.list] or [VFile.open]. They never parse or format a provider path
 * themselves; only [Vfs] and provider implementations know about schemes and
 * native inner paths.
 */
interface VNode {
    /** Display name. For a container root this is the backing file's name. */
    val name: String

    /** Parent folder, or null at a provider root / detached root. */
    val parent: VFolder?

    /**
     * Complete, stable address of this node: the provider path plus any view
     * levels entered inside a file. This is the only identity the app persists;
     * providers only ever see [Path.toFullUri].
     */
    val path: Path

    /**
     * Rename inside the parent folder and return this node under its new name.
     * Callers keep working with nodes; no path is ever rebuilt from strings.
     * Read-only views (archives, URLs) refuse.
     */
    suspend fun rename(newName: String): Result<VNode> =
        Result.failure(UnsupportedOperationException("$path cannot be renamed"))

    /** Delete through the owning provider; read-only views refuse. */
    suspend fun delete(): Result<Unit> =
        Result.failure(UnsupportedOperationException("$path is read-only"))
}

/** A file node. It may also be an archive/container, but it is still bytes. */
interface VFile : VNode {
    suspend fun stat(): Result<ProviderStat>
    suspend fun open(mode: OpenMode): Result<FileSource>

    /**
     * Whether [open] accepts a write mode. Read-only views and live resources
     * answer false; the caller never inspects a provider or a scheme.
     */
    val writable: Boolean get() = true

    /**
     * Absolute address a document renderer loads itself (so the platform can
     * resolve the page's subresources against its origin), or null when this
     * file is stored bytes.
     */
    val liveAddress: String? get() = null

    /** The page a live resource was reached from, or null. */
    val liveReferer: String? get() = null

    /**
     * Content facts for renderer selection. Providers may refine the MIME
     * type; the default implementation falls back to name/extension and a
     * small magic-byte sniff.
     */
    suspend fun describe(): Result<ContentInfo>

    /**
     * Facts without transferring the body. The default is [describe]; a live
     * provider that can answer from headers overrides it.
     */
    suspend fun peek(): Result<ContentInfo> = describe()

    /** [this] with the page a live request came from; stored files ignore it. */
    fun withReferer(referer: String?): VFile = this
}

/** A directory node. */
interface VFolder : VNode {
    /** True when this folder is a view (archive/document) rather than a provider directory. */
    val isView: Boolean get() = path.isViewed

    /** True when this folder is addressed directly by its path string. */
    val addressable: Boolean get() = !isView

    /** Raw listing DTOs, kept for existing callers and interop. */
    suspend fun list(): Result<List<FileEntry>>

    /** Object listing used by navigation and renderers. */
    suspend fun listNodes(): Result<List<VNode>>

    /**
     * Entries and their nodes from one listing.
     *
     * Renderers need both; backends that build nodes from the entries they
     * already read override this so a folder is never listed twice.
     */
    suspend fun listWithNodes(): Result<Pair<List<FileEntry>, List<VNode>>> =
        list().mapCatching { entries -> entries to listNodes().getOrThrow() }

    suspend fun stat(): Result<ProviderStat>
    suspend fun capabilities(): DirectoryCapabilities
    suspend fun info(): DirectoryStats

    /** Resolve a direct child by name, using the same provider binding. */
    suspend fun child(name: String): Result<VNode>

    suspend fun folder(name: String): Result<VFolder>
    suspend fun file(name: String): Result<VFile>

    /** Create [name] here and return the new file node. */
    suspend fun create(name: String): Result<VFile> =
        Result.failure(UnsupportedOperationException("$path is read-only"))

    /** Create [name] here and return the new folder node. */
    suspend fun mkdir(name: String): Result<VFolder> =
        Result.failure(UnsupportedOperationException("$path is read-only"))

    /**
     * The child file [name], created when this folder does not have it yet.
     *
     * Copy destinations are nodes: the caller resolves one of these first and
     * hands it to [NodeOps.copyTo], so a provider path is never formatted for an
     * operation.
     */
    suspend fun destination(name: String): Result<VFile> =
        when (val node = child(name).getOrNull()) {
            null -> create(name)
            is VFile -> Result.success(node)
            is VFolder -> Result.failure(IllegalStateException("$name is a folder"))
            else -> Result.failure(IllegalStateException("unsupported child: $name"))
        }

    /** The child folder [name], created when missing. */
    suspend fun destinationFolder(name: String): Result<VFolder> =
        when (val node = child(name).getOrNull()) {
            null -> mkdir(name)
            is VFolder -> Result.success(node)
            is VFile -> Result.failure(IllegalStateException("$name is a file"))
            else -> Result.failure(IllegalStateException("unsupported child: $name"))
        }
}
