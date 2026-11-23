package io.furryr.file

import io.furryr.file.core.ContentTypes
import io.furryr.file.core.FileWorld
import io.furryr.file.core.MimeType
import io.furryr.file.core.OpenOption
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.render.FolderPageState
import io.furryr.file.render.FolderRenderer
import io.furryr.file.render.GenericRenderer
import io.furryr.file.render.HomeRenderer
import io.furryr.file.render.RendererBase
import io.furryr.file.isHomePath
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException

/** What an open method produces. */
typealias Renderer = RendererBase<*>

/**
 * One open method. It owns the name and icon the chooser shows and builds the
 * page for a file; it is the only place a format knows how it is displayed.
 */
interface Factory {
    /** Icon and localized name the generic loader shows for this method. */
    val option: OpenOption

    /** Build the page for [file]. A failure is shown by the generic loader. */
    suspend fun create(file: VFile): Result<Renderer>
}

/**
 * The open-with table: MIME → registered [Factory] candidates.
 *
 * Routing is deliberately small. Structural views are owned by [Proxies] in
 * core and are consulted first; this table only chooses a renderer for a file
 * that is not a folder view. A caller's MIME hint wins over [VFile.stat];
 * neither reads the body. A MIME resolves to its highest-priority factory, and
 * a later registration wins a tie, so a plugin or a built-in can replace an
 * earlier entry without a separate rule list.
 *
 * A folder is not a format: it always becomes the one directory renderer. A
 * file whose MIME has no entry goes to the fallback factory, and a factory that
 * fails becomes a [GenericRenderer] carrying the error.
 */
class Opener(private val files: FileWorld) {
    private data class Entry(
        val factory: Factory,
        val priority: Int,
        val order: Int,
    )

    private companion object {
        /** Priority first, then registration order; the latest wins a tie. */
        val ENTRY_ORDER: Comparator<Entry> =
            compareBy<Entry> { it.priority }.thenBy { it.order }
    }

    private val byMime = LinkedHashMap<MimeType, MutableList<Entry>>()
    private val chooserOnly = mutableListOf<Factory>()
    private var nextOrder = 0
    private var fallback: Factory? = null

    /** Read-only view of the table: each MIME's winning factory. */
    val factories: Map<MimeType, Factory>
        get() = synchronized(this) {
            Collections.unmodifiableMap(
                byMime.mapValuesTo(LinkedHashMap()) { (_, entries) ->
                    entries.maxWithOrNull(ENTRY_ORDER)!!.factory
                },
            )
        }

    /**
     * Every distinct open method for the generic chooser.
     *
     * This list is intentionally not MIME-filtered: [routeFile] decides the
     * automatic renderer, while a generic chooser shows every method the world
     * can offer. Chooser-only methods (the archive view) are therefore visible
     * for an APK without making APK auto-open as an archive.
     */
    val options: List<Factory>
        get() = synchronized(this) {
            val registered = byMime.values
                .flatten()
                .sortedWith(ENTRY_ORDER.reversed())
                .map { it.factory }
            (chooserOnly + registered).distinctBy { it.option.id }
        }

    /** Register a method that is listed by the chooser but never auto-routed. */
    @Synchronized
    fun registerChooserOnly(factory: Factory) {
        chooserOnly += factory
    }

    @Synchronized
    fun register(mime: MimeType, factory: Factory, priority: Int = 0) {
        val key = requireNotNull(ContentTypes.normalizeMime(mime)) { "invalid MIME: $mime" }
        byMime.getOrPut(key) { mutableListOf() } += Entry(factory, priority, nextOrder++)
    }

    @Synchronized
    fun unregister(mime: MimeType) {
        ContentTypes.normalizeMime(mime)?.let(byMime::remove)
    }

    /** The factory for files no MIME entry claims. */
    @Synchronized
    fun registerFallback(factory: Factory) {
        fallback = factory
    }

    /**
     * Route one node to its renderer. [mimeHint] is an already-known MIME (a
     * response header or a caller's facts); it wins over the file's own
     * metadata and the extension.
     */
    /**
     * The renderer for a resolved node. Home is not a node and is built by
     * the shell directly.
     */
    suspend fun route(node: VNode, mimeHint: MimeType? = null): Renderer = when (node) {
        is VFile -> routeFile(node, mimeHint)
        is VFolder -> FolderRenderer(files.vfs, initialState = FolderPageState(node))
        else -> error("unsupported node: ${node.path}")
    }

    private suspend fun routeFile(file: VFile, mimeHint: MimeType?): Renderer {
        // Resolve MIME once. The caller's facts win; otherwise the file answers
        // from metadata and its name. Neither path reads the body.
        val mime = ContentTypes.normalizeMime(mimeHint)
            ?.takeUnless(ContentTypes::isGenericMime)
            ?: file.stat().getOrNull()?.mime
            ?: "text/html".takeIf { file.liveAddress != null }

        // A structural view is not a renderer: the core proxy owns the file →
        // folder step, so an archive is registered once, not in this table.
        files.proxies.forMime(mime)?.let { proxy ->
            return try {
                FolderRenderer(files.vfs, initialState = FolderPageState(proxy.open(file).getOrThrow()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                generic(file, error.message)
            }
        }

        val factory = mime?.let { synchronized(this) { byMime[it] } }
            ?.maxWithOrNull(ENTRY_ORDER)
            ?.factory
            ?: synchronized(this) { fallback }
        if (factory == null) return generic(file, "No opener for ${file.name}")
        return try {
            factory.create(file).fold(
                onSuccess = { it },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    generic(file, error.message)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            generic(file, error.message)
        }
    }

    private fun generic(file: VFile, error: String?): Renderer = GenericRenderer(
        file = file,
        options = options,
        error = error,
    )

    /** The open-with chooser for [file], even when a MIME factory claims it. */
    fun chooser(file: VFile): Renderer = generic(file, null)
}
