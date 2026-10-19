package io.furryr.file.provider

import android.content.Context
import io.furryr.file.FileRepository
import io.furryr.file.HereNavigation
import io.furryr.file.archive.ArchiveProvider
import io.furryr.file.provider.remote.RemoteProviders
import io.furryr.file.agent.ContainerDaemonLauncher
import io.furryr.file.agent.AgentSettings
import io.furryr.file.core.DaemonManager

/**
 * Wire up the daemon configuration and register every [FileProvider].
 *
 * Idempotent, so it is safe to call from both the activity and any process
 * entry point that may run first (e.g. [ForwardFileProvider.onCreate]).
 */
fun installFileProviders(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    DaemonManager.init(appContext)
    AgentSettings.install(appContext)
    DaemonManager.configure(
        rootEnabled = prefs.getBoolean("root_enabled", false),
        shellEnabled = prefs.getBoolean("shell_enabled", false),
        suPath = prefs.getString("su_path", "") ?: "",
    )
    // Let the app decide how named (container) daemons are launched.
    DaemonManager.setLauncher { request -> ContainerDaemonLauncher.command(appContext, request) }

    FileRepository.setDefault(DaemonFileProvider())
    ContainerDaemonProvider.init(appContext)
    FileRepository.register("container", ContainerDaemonProvider)
    DocumentFileProvider.init(appContext)
    FileRepository.register("document", DocumentFileProvider)
    SAFProvider.init(appContext)
    FileRepository.register("saf", SAFProvider)
    FtpProvider.init(appContext)
    FileRepository.register("ftp", FtpProvider)
    SftpProvider.init(appContext)
    FileRepository.register("sftp", SftpProvider)
    HereNavigation.install(appContext)
    RemoteProviders.install(appContext)
    ArchiveProvider.install(appContext)
}
