package io.furryr.file.provider

import android.content.Context
import io.furryr.file.FileRepository
import io.furryr.file.daemon.DaemonLauncher

/**
 * Wire up the daemon configuration and register every [FileProvider].
 *
 * Idempotent, so it is safe to call from both the activity and any process
 * entry point that may run first (e.g. [ForwardFileProvider.onCreate]).
 */
fun installFileProviders(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    DaemonLauncher.init(appContext)
    DaemonLauncher.setRootEnabled(prefs.getBoolean("root_enabled", false))
    DaemonLauncher.setShellEnabled(prefs.getBoolean("shell_enabled", false))
    DaemonLauncher.setSuPath(prefs.getString("su_path", "") ?: "")

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
}
