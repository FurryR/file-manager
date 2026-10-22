package io.furryr.file

import android.content.Context
import io.furryr.file.agent.AgentSettings
import io.furryr.file.agent.SessionManager
import io.furryr.file.container.ContainerDaemonLauncher
import io.furryr.file.container.ContainerProvider
import io.furryr.file.container.ContainerRuntime
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.HubActions
import io.furryr.file.daemon.HubLocation
import io.furryr.file.provider.LocalProvider
import io.furryr.file.provider.LocationsRepository
import io.furryr.file.provider.DocumentFileProvider
import io.furryr.file.provider.FtpProvider
import io.furryr.file.provider.LocalLocationStore
import io.furryr.file.provider.SAFProvider
import io.furryr.file.provider.SftpProvider
import io.furryr.file.core.Vfs
import io.furryr.file.provider.remote.RemoteProviders

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
    LocalLocationStore.init(appContext)
    AgentSettings.install(appContext)
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

    ContainerRuntime.isRunning = SessionManager::hasContainerSession

    val local = LocalProvider()
    Vfs.setDefault(local)
    Vfs.register(local)
    ContainerProvider.init(appContext)
    Vfs.register(ContainerProvider)
    DocumentFileProvider.init(appContext)
    Vfs.register("document", DocumentFileProvider)
    SAFProvider.init(appContext)
    Vfs.register("saf", SAFProvider)
    FtpProvider.init(appContext)
    Vfs.register("ftp", FtpProvider)
    SftpProvider.init(appContext)
    Vfs.register("sftp", SftpProvider)
    HereNavigation.install(appContext)
    HubActions.locationTargets = {
        LocationsRepository.locations(appContext, local)
            .filter { it.kind != "local" }
            .map { HubLocation(kind = it.kind, label = it.title, root = it.root) }
    }
    RemoteProviders.install(appContext)
    RemoteProviders.providers(appContext).forEach {
        Vfs.register(it.scheme, it)
    }
    fileProvidersInstalled = true
}
