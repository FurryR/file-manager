package io.furryr.file.core

import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import kotlinx.coroutines.flow.Flow

/**
 * Unified interface for file system operations, abstracting over
 * different backends: local daemon, containers, SAF, remote, etc.
 *
 * Each provider is identified by its [scheme] and optional [authority],
 * enabling URI-based routing like `container://alpine/home/`.
 */
interface FileProvider {
    val scheme: String
    val authority: String?
    val label: String
    val features: Set<ProviderFeature>

    /**
     * True when the provider addresses its files by the full URI instead of an
     * authority-first inner path.
     *
     * A storage provider strips the scheme (`saf://hex/dir` → `/hex/dir`)
     * because the scheme names the backend, not the resource. A live provider
     * cannot: `http` and `https` reach different origins, so the address *is*
     * the URI and must arrive intact.
     */
    val fullUriPaths: Boolean get() = false

    /** Whether [open] accepts a write mode for files of this provider. */
    val writable: Boolean get() = true

    /**
     * Provider-facing address for [path].
     *
     * A provider that addresses files by full URI receives the URI intact; a
     * storage provider receives the inner path. The local provider overrides
     * this because a bare filesystem path is its native spelling.
     */
    fun innerPath(path: Path): String =
        if (fullUriPaths) path.toFullUri() else path.toInnerPath()

    /**
     * The node for a live [address], or null when this provider serves no live
     * resources. A live node is still a [VFile]; [referer] is the page it was
     * reached from.
     */
    fun liveFile(address: String, referer: String? = null): VFile? = null

    suspend fun list(path: String): Result<List<FileEntry>>
    suspend fun stat(path: String): Result<ProviderStat>

    /**
     * The node at [path].
     *
     * A provider whose bytes are not a kernel storage path (a live remote
     * resource, a generated listing, …) returns its own [VNode] type here; the
     * default is the provider-backed [Folder]/[File] pair. [Vfs] never builds a
     * node itself, so a provider decides what its own addresses are — and the
     * renderer/opener pipeline only ever sees the resulting node.
     */
    suspend fun node(path: Path, innerPath: String): Result<VNode> = runCatching {
        val stat = stat(innerPath).getOrThrow()
        // A node is an *existing* file or folder. A provider that answers with
        // `exists = false` has no node at this address, and the caller must be
        // able to tell: a typed address that no provider owns falls through to
        // the search/browser fallback instead of becoming a phantom file.
        if (!stat.exists) error("no such file or folder: $path")
        if (stat.isDirectory) Folder(path, this, innerPath) else File(path, this, innerPath)
    }
    suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        return DirectoryCapabilities(
            canCreate = (ProviderFeature.CREATE_FILE in features || ProviderFeature.CREATE_DIR in features) &&
                list(path).isSuccess,
        )
    }
    suspend fun createFile(path: String): Result<Unit>
    suspend fun createDirectory(path: String): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun rename(path: String, newName: String): Result<Unit>

    /** Preserve backend-specific metadata on a staged file before replacing [original]. */
    suspend fun prepareReplacement(original: String, replacement: String): Result<Unit> = Result.success(Unit)

    /** Open a file and return a universally readable/writable [FileSource]. */
    suspend fun open(path: String, mode: OpenMode): Result<FileSource>

    /**
     * Same-provider copy: both [from] and [to] are native paths of this provider.
     * Daemon-native [stream_copy] is used — zero-copy, no handle open.
     */
    fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress>

    /**
     * Cross-provider copy: [src] is a [FileSource] obtained via another provider's
     * [open]. Destination [destPath] is a native path of this provider.
     */
    fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress>

    fun isAvailable(): Boolean
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
}

/**
 * One capability a provider may advertise.
 *
 * This is an open value type, not an enum: a plugin provider can name a
 * capability the app has never heard of without a core release. The companion
 * constants are the ones the app itself reads; anything else is preserved and
 * ignored by callers that do not understand it.
 */
@JvmInline
value class ProviderFeature(val id: String) {
    companion object {
        val LIST = ProviderFeature("LIST")
        val STAT = ProviderFeature("STAT")
        val CREATE_FILE = ProviderFeature("CREATE_FILE")
        val CREATE_DIR = ProviderFeature("CREATE_DIR")
        val DELETE = ProviderFeature("DELETE")
        val RENAME = ProviderFeature("RENAME")
        val STREAM_COPY = ProviderFeature("STREAM_COPY")
        val PTY = ProviderFeature("PTY")
        val SYMLINK = ProviderFeature("SYMLINK")
        val OPEN = ProviderFeature("OPEN")

        /** Every built-in capability, for providers that support all of them. */
        val entries: List<ProviderFeature> = listOf(
            LIST, STAT, CREATE_FILE, CREATE_DIR, DELETE,
            RENAME, STREAM_COPY, PTY, SYMLINK, OPEN,
        )
    }
}
