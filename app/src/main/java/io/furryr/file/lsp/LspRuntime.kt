package io.furryr.file.lsp

import android.content.Context
import io.furryr.file.core.VFile
import io.furryr.file.core.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-memory LSP registry.
 *
 * Nothing here is persisted. A world owns its own server processes; opening a
 * file only starts one when a catalog entry matches, a workspace marker is
 * found, and the provider can execute the server. Any missing precondition is
 * a silent "no LSP" result.
 */
internal class LspRuntime private constructor(
    private val context: Context,
) {
    private val finder = WorkspaceFinder()
    private val sessions = ConcurrentHashMap<String, LspWorkspaceSession>()
    private val lock = Mutex()

    @Volatile
    private var catalog = LspCatalog.load(context)

    @Volatile
    private var catalogStamp = LspConfigStore.generation(context)

    suspend fun attach(
        file: VFile,
        editorLanguageId: String,
        text: String,
        revision: Long,
        diagnostics: (List<LspDiagnostic>, Long) -> Unit,
    ): LspDocumentHandle? = withContext(Dispatchers.IO) {
        lock.withLock {
            reloadCatalog()
            val override = catalog.overrideFor(file)
            if (override?.enabled == false) return@withLock null
            val language = override?.language?.let(catalog::language)
                ?: catalog.languageFor(file, editorLanguageId)
            val ownServer = override?.let(catalog::overrideServer)
            val servers = ownServer?.let(::listOf)
                ?: language?.let { spec ->
                    catalog.candidates(spec).map { server ->
                        override?.let { catalog.applyOverride(server, it) } ?: server
                    }
                }
                ?: emptyList()
            if (servers.isEmpty()) return@withLock null
            val lspLanguageId = language?.let { catalog.languageId(it, file) }
                ?: override?.language
                ?: editorLanguageId

            for (server in servers) {
                val root = if (server.workspace == LspWorkspaceMode.VIRTUAL) {
                    null
                } else {
                    finder.find(file, catalog.markers(language, server, override), server.rootMode) ?: continue
                }
                val host = LspHosts.create(context, root, server) ?: continue
                val uri = host.uri(file) ?: continue

                val key = host.key + '\u0000' + server.id
                val existing = sessions[key]?.takeIf { !it.isClosed }
                val session: LspWorkspaceSession
                if (existing != null) {
                    session = existing
                } else {
                    val created = LspWorkspaceSession(
                        server = server,
                        host = host,
                        rootName = root?.name ?: file.parent?.name ?: file.name,
                    ) { closed ->
                        sessions.remove(key, closed)
                    }
                    if (created.start().isFailure) {
                        created.close()
                        continue
                    }
                    sessions[key] = created
                    session = created
                }

                val handle = session.open(uri, lspLanguageId, text, revision, diagnostics)
                if (handle != null) return@withLock handle
                if (existing == null) {
                    sessions.remove(key, session)
                    session.close()
                }
            }
            null
        }
    }

    /** Reset sessions when the settings page saved a new config generation. */
    suspend fun refreshIfChanged(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val before = catalogStamp
            reloadCatalog()
            before != catalogStamp
        }
    }

    private fun reloadCatalog() {
        val stamp = LspConfigStore.generation(context)
        if (stamp == catalogStamp) return
        catalogStamp = stamp
        catalog = LspCatalog.load(context)
        // The next attach builds fresh sessions from the new directory. Existing
        // editors keep syntax highlighting and reconnect on their next open.
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        private val runtimes = ConcurrentHashMap<Vfs, LspRuntime>()

        fun forWorld(context: Context, vfs: Vfs): LspRuntime =
            runtimes.getOrPut(vfs) { LspRuntime(context.applicationContext) }

        suspend fun refreshAll() {
            runtimes.values.forEach { it.refreshIfChanged() }
        }
    }
}

/** Small facade used by the editor; LSP is optional, so null is a normal result. */
internal object LspRuntimeFacade {
    suspend fun refreshIfChanged(context: Context, vfs: Vfs): Boolean =
        LspRuntime.forWorld(context, vfs).refreshIfChanged()

    suspend fun refreshAll() = LspRuntime.refreshAll()

    suspend fun attach(
        context: Context,
        vfs: Vfs,
        file: VFile,
        editorLanguageId: String,
        text: String,
        revision: Long,
        diagnostics: (List<LspDiagnostic>, Long) -> Unit,
    ): LspDocumentHandle? = LspRuntime.forWorld(context, vfs)
        .attach(file, editorLanguageId, text, revision, diagnostics)
}
