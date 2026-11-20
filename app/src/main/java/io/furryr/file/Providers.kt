package io.furryr.file

import android.content.Context
import io.furryr.file.agent.AgentEnvironment
import io.furryr.file.agent.AgentSettings
import io.furryr.file.container.ContainerDaemonLauncher
import io.furryr.file.container.ContainerProvider
import io.furryr.file.container.ContainerRuntime
import io.furryr.file.container.ContainerUris
import io.furryr.file.core.ContentKind
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.FileProvider
import io.furryr.file.core.FileWorld
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.HubLocation
import io.furryr.file.editor.EditorFactory
import io.furryr.file.editor.EditorLanguages
import io.furryr.file.forward.ShareToken
import io.furryr.file.lsp.LspCatalog
import io.furryr.file.lsp.LspConfigStore
import io.furryr.file.home.HomeStore
import io.furryr.file.provider.DocumentFileProvider
import io.furryr.file.provider.FtpProvider
import io.furryr.file.provider.LocalProvider
import io.furryr.file.provider.ProviderSchemes
import io.furryr.file.provider.SAFProvider
import io.furryr.file.provider.SftpProvider
import io.furryr.file.provider.remote.RemoteProviders
import io.furryr.file.provider.remote.SshTerminals
import io.furryr.file.provider.web.HttpProvider
import io.furryr.file.provider.web.WebTransfers
import io.furryr.file.proxy.ArchiveProxy
import io.furryr.file.render.GenericFactory
import io.furryr.file.render.WebFactory
import io.furryr.file.render.WebTransferService
import io.furryr.file.settings.PulseSettings
import io.furryr.file.store.LocalLocationStore
import io.furryr.file.store.LocationStores
import io.furryr.file.store.LocationsRepository
import io.furryr.file.store.WebLocationStore

private var processInstalled = false
private var runtimeListenerInstalled = false

/**
 * Process-owned services. These are resources, not registrations: one daemon
 * per process, one set of preference/database stores, one foreground-transfer
 * notifier. Every world shares them.
 */
@Synchronized
fun ensureProcessServices(context: Context) {
    if (processInstalled) return
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    DaemonManager.init(appContext)
    LocationStores.init(appContext)
    LocalLocationStore.init(appContext)
    WebLocationStore.init(appContext)
    HomeStore.init(appContext)
    // A remote handle may be read after the activity is gone; the provider-side
    // registry asks the app to keep its foreground service alive.
    WebTransfers.init { WebTransferService.start(appContext) }
    AgentSettings.install(appContext)
    PulseSettings.install(appContext)
    // Bridge the old per-language command store once. Container/root were
    // workarounds; the new runtime derives both from the file's VFS node.
    LspConfigStore.migrateLegacy(appContext)
    // Languages added through the settings page still get an editor entry, so
    // a new extension is editable before any TextMate grammar exists.
    LspCatalog.load(appContext).editorLanguages().forEach { language ->
        if (language.id !in EditorLanguages.languageIds) {
            EditorLanguages.registerLanguage(
                EditorLanguages.LanguageSpec(language.id, language.extensions, language.label),
            )
        }
    }
    // Shared content:// URIs outlive the activity and the process, like picker
    // document ids; keep their signing key in app-private storage.
    ShareToken.install(java.io.File(appContext.filesDir, "share-token.bin"))
    DaemonManager.configure(
        rootEnabled = prefs.getBoolean("root_enabled", false),
        shellEnabled = prefs.getBoolean("shell_enabled", false),
        suPath = prefs.getString("su_path", "") ?: "",
    )
    // Let the app decide how named (container) daemons are launched.
    DaemonManager.setLauncher { request -> ContainerDaemonLauncher.command(appContext, request) }
    // Keep the runtime service (and its notification) alive while any
    // container daemon is running.
    if (!runtimeListenerInstalled) {
        runtimeListenerInstalled = true
        DaemonManager.addNamedChangeListener {
            if (DaemonManager.connectedNamedCount() > 0) {
                RuntimeForegroundService.start(appContext)
            }
        }
    }
    processInstalled = true
    // A persisted document URI names a world that may not be live yet. Rebuild
    // it from the persisted locations instead of failing the read.
    FileWorlds.factory = { id -> buildAppWorld(appContext, id, manager = id == "app") }
}

/**
 * Build one independent world. [manager] wires the features that only the full
 * file manager owns: container scope for the agent, `here` navigation and hub
 * location targets. A picker world builds the same file stack without them.
 */
fun buildAppWorld(context: Context, id: String, manager: Boolean): AppWorld {
    ensureProcessServices(context)
    val appContext = context.applicationContext

    val local = LocalProvider(appContext)
    val providers = mutableListOf<FileProvider>(
        local,
        ContainerProvider(appContext),
        DocumentFileProvider(appContext, LocationStores),
        SAFProvider(appContext, LocationStores),
        FtpProvider(LocationStores, appContext.cacheDir),
        SftpProvider(LocationStores, appContext.cacheDir),
        HttpProvider(ProviderSchemes.web),
    )
    providers += RemoteProviders.providers(appContext.cacheDir, LocationStores)

    val files = FileWorld()
    files.vfs.setDefault(local)
    for (provider in providers) files.vfs.register(provider)

    val picker = !manager
    val selection = if (picker) io.furryr.file.picker.PickerSelection() else null
    val opener = Opener(files)
    if (picker) {
        // A picker has no editor, browser or archive view. Every regular file
        // goes to the one select action, so a click means "choose this file".
        opener.registerFallback(
            io.furryr.file.picker.PickerSelectFactory(requireNotNull(selection)),
        )
    } else {
        // Structural views are world-local: `a.zip!/x` resolves through this
        // world's registry, never a process-global one.
        val archive = ArchiveProxy(appContext.cacheDir)
        files.proxies.register("application/zip", archive)
        opener.registerChooserOnly(
            ArchiveOpener(files, archive, appContext.getString(R.string.open_with_archive)),
        )

        val editor = EditorFactory(files, appContext.getString(R.string.open_with_editor))
        val web = WebFactory(files, appContext.getString(R.string.open_with_browser))
        opener.register("text/html", web)
        opener.register("application/xhtml+xml", web)
        for (mime in ContentTypes.mimes) {
            if (ContentTypes.isHtmlMime(mime)) continue
            if (ContentTypes.kindOf(mime, "") == ContentKind.TEXT ||
                ContentTypes.kindOf(mime, "") == ContentKind.CODE
            ) {
                opener.register(mime, editor)
            }
        }
        opener.registerFallback(GenericFactory { opener.options })
    }

    val world = AppWorld(id, files, opener, local, providers, selection)

    if (manager) {
        ContainerRuntime.isRunning = DaemonManager::isNamedConnected
        // The agent names a container scope through this seam, so `:agent` never
        // spells a container address itself.
        AgentEnvironment.namedTargetRoot = { name -> ContainerUris.forContainer(name) }
        HereNavigation.install(appContext)
        HubActions.locationTargets = {
            LocationsRepository.locations(appContext, local, files.vfs)
                .filter { it.kind != "local" }
                .map { HubLocation(kind = it.kind, label = it.title, root = it.root) }
        }
        SshTerminals.configure(LocationStores)
    }

    return world
}

/**
 * Process services plus the file manager's default world.
 *
 * Idempotent, so a content provider that starts before the activity can build
 * the same world the UI will use.
 */
object AppRuntime {
    @Synchronized
    fun appWorld(context: Context): AppWorld {
        ensureProcessServices(context)
        FileWorlds.app()?.let { return it }
        val world = buildAppWorld(context, id = "app", manager = true)
        FileWorlds.install(world, default = true)
        return world
    }

    /** The picker's own world; it never becomes the process default. */
    @Synchronized
    fun pickerWorld(context: Context): AppWorld {
        ensureProcessServices(context)
        // Every picker activity gets its own world: two pickers in the same
        // process must not overwrite each other's PickerSelection callback.
        val id = "picker-${++pickerSequence}"
        val world = buildAppWorld(context, id, manager = false)
        FileWorlds.install(world)
        return world
    }

    private var pickerSequence = 0
}
