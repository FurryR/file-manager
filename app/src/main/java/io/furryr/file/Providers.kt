package io.furryr.file

import android.content.Context
import android.util.Log
import io.furryr.file.agent.AgentEnvironment
import io.furryr.file.agent.AgentSettings
import io.furryr.file.terminal.SessionManager
import io.furryr.file.container.ContainerDaemonLauncher
import io.furryr.file.container.ContainerProvider
import io.furryr.file.container.ContainerRuntime
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.HubLocation
import io.furryr.file.home.HomeStore
import io.furryr.file.provider.LocalProvider
import io.furryr.file.provider.DocumentFileProvider
import io.furryr.file.provider.FtpProvider
import io.furryr.file.provider.SAFProvider
import io.furryr.file.provider.SftpProvider
import io.furryr.file.core.Vfs
import io.furryr.file.core.Plugins
import io.furryr.file.provider.remote.RemoteProviders
import io.furryr.file.provider.remote.SshTerminals
import io.furryr.file.opener.ArchiveOpener
import io.furryr.file.editor.EditorRendererFactory
import io.furryr.file.core.ViewOpeners
import io.furryr.file.render.Renderers
import io.furryr.file.render.WebRendererFactory
import io.furryr.file.render.WebTransferService
import io.furryr.file.provider.web.HttpProvider
import io.furryr.file.provider.web.WebTransfers
import io.furryr.file.settings.PulseSettings
import io.furryr.file.store.LocalLocationStore
import io.furryr.file.store.LocationStores
import io.furryr.file.store.LocationsRepository
import io.furryr.file.store.WebLocationStore

private var runtimeListenerInstalled = false
private var fileProvidersInstalled = false

/**
 * Wire up the daemon configuration and register every file provider.
 *
 * Idempotent, so it is safe to call from both the activity and any process
 * entry point that may run first (e.g. the forwarded-content provider).
 */
@Synchronized
fun installFileProviders(context: Context) {
    if (fileProvidersInstalled) return
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

    ContainerRuntime.isRunning = DaemonManager::isNamedConnected

    val local = LocalProvider()
    Vfs.setDefault(local)
    Vfs.register(local)
    ContainerProvider.init(appContext)
    Vfs.register(ContainerProvider)
    DocumentFileProvider.init(appContext, LocationStores)
    Vfs.register(DocumentFileProvider)
    SAFProvider.init(appContext, LocationStores)
    Vfs.register(SAFProvider)
    FtpProvider.init(LocationStores, appContext.cacheDir)
    Vfs.register(FtpProvider)
    SftpProvider.init(LocationStores, appContext.cacheDir)
    Vfs.register(SftpProvider)
    // A URL is an address like any other: both schemes reach the same
    // read-only provider, and nothing above it inspects the scheme.
    Vfs.register(HttpProvider)
    HereNavigation.install(appContext)
    HubActions.locationTargets = {
        LocationsRepository.locations(appContext, local)
            .filter { it.kind != "local" }
            .map { HubLocation(kind = it.kind, label = it.title, root = it.root) }
    }
    RemoteProviders.providers(appContext.cacheDir, LocationStores).forEach {
        Vfs.register(it)
    }
    SshTerminals.configure(LocationStores)
    // Container openers and renderers are Kotlin-side; they never touch a
    // daemon API. Directory tabs are shell-owned location renderers, so the
    // open-with table only handles files.
    ViewOpeners.register(100, ArchiveOpener(appContext.cacheDir))
    Renderers.register(15, EditorRendererFactory())
    Renderers.register(20, WebRendererFactory())
    OpenTable.install()
    // Plugin contributions go through the same registries as the built-ins.
    Plugins.installAll().forEach { failure ->
        Log.w("Plugins", "Plugin '${failure.id}' failed to install", failure.cause)
    }
    fileProvidersInstalled = true
}
