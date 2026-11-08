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

    /** Stable identity used by history, selection and persistence. */
    fun ref(): ViewRef
}

/** A file node. It may also be an archive/container, but it is still bytes. */
interface VFile : VNode {
    suspend fun stat(): Result<ProviderStat>
    suspend fun open(mode: OpenMode): Result<FileSource>

    /**
     * Content facts for renderer selection. Providers may refine the MIME
     * type; the default implementation falls back to name/extension and a
     * small magic-byte sniff.
     */
    suspend fun describe(): Result<ContentInfo>
}

/** A directory node. */
interface VFolder : VNode {
    /** Raw listing DTOs, kept for existing callers and interop. */
    suspend fun list(): Result<List<FileEntry>>

    /** Object listing used by navigation and renderers. */
    suspend fun listNodes(): Result<List<VNode>>

    suspend fun stat(): Result<ProviderStat>
    suspend fun capabilities(): DirectoryCapabilities
    suspend fun info(): DirectoryStats

    /** Resolve a direct child by name, using the same provider binding. */
    suspend fun child(name: String): Result<VNode>

    suspend fun folder(name: String): Result<VFolder>
    suspend fun file(name: String): Result<VFile>
}

/**
 * Identity of a real Vfs node. It is deliberately only a URI: container and
 * document views are represented by [ViewRef], not by nesting this type.
 */
sealed interface NodeRef {
    data class Vfs(val uri: String) : NodeRef

    /**
     * A resource addressed outside Vfs. Remote files (HTTP(S)) live here, so a
     * view over one — an archive whose backing file is a URL — still has a
     * stable root instead of a provider-invented URI.
     */
    data class Url(val url: String) : NodeRef
}

/**
 * Flattened view identity.
 *
 * A file that offers a folder view (a container, a virtual document, ...) is
 * represented by [ViewRef.Node] with the opener's id in [ViewRef.Node.viewId]
 * and a flat inner [ViewRef.Node.path]. There is no recursive Nested type: a
 * container inside a container is just a longer path, and the resolver enters
 * intermediate files automatically when more segments follow. The view id is
 * chosen by the opener registry, so core never enumerates concrete formats.
 */
sealed interface ViewRef {
    data class Node(
        val root: NodeRef,
        val viewId: String,
        val path: List<String> = emptyList(),
    ) : ViewRef

    /** A web location; it has no Vfs node. */
    data class Url(val url: String) : ViewRef

    /** A live session (terminal / DevTools); owned by a service, not Vfs. */
    data class Session(val id: String, val kind: String) : ViewRef
}


