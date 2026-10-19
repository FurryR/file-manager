package io.furryr.file.agent

import android.content.Context
import io.furryr.file.R
import io.furryr.file.core.DaemonCommand
import io.furryr.file.core.DaemonLaunchRequest
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.isSuAvailable
import io.furryr.file.core.shellQuote
import io.furryr.file.core.hub.Hub
import io.furryr.file.graphics.GraphicsManager

/**
 * App-owned daemon launcher: builds the daemon argv for a container,
 * honouring its fixed backend. This is where proot/chroot knowledge lives —
 * `:core` never sees it.
 *
 * - chroot: launched as root via `su -c '<daemon> --key <pub> --id <id> --chroot <rootfs> --bind …'`.
 *   The daemon isolates the mount tree and chroots itself.
 * - proot: launched as the app UID through proot, so the daemon process itself
 *   lives inside the container.
 */
object ContainerDaemonLauncher {
    internal const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun command(context: Context, request: DaemonLaunchRequest): DaemonCommand? {
        val container = ContainerManager.getContainer(context, request.name) ?: return null
        GraphicsManager.ensureHostIfEnabled(context, request.name)
        DaemonManager.startHub()
        ContainerServiceForegroundService.track(context, request.name)
        return when (container.backend) {
            ContainerBackend.CHROOT -> chrootCommand(context, request, container)
            ContainerBackend.PROOT -> prootCommand(context, request, container)
        }
    }

    private fun chrootCommand(
        context: Context,
        request: DaemonLaunchRequest,
        container: Container,
    ): DaemonCommand {
        check(DaemonManager.canStartRootDaemon() && isSuAvailable(request.suPath)) {
            context.getString(R.string.permission_denied)
        }
        val tokens = mutableListOf(
            shellQuote(request.daemonExecutable),
            "--key", shellQuote(request.publicKey),
            "--id", shellQuote(request.id),
            "--chroot", shellQuote(container.rootfsPath),
        )
        for ((source, target) in ProotRunner.chrootBinds(context, container)) {
            tokens.add("--bind")
            tokens.add(shellQuote("$source:$target"))
        }
        val su = request.suPath.ifBlank { "su" }
        val hubEnvironment = Hub.environment().entries.joinToString(" ") { "${it.key}=${shellQuote(it.value)}" }
        val command = "export PATH=${shellQuote(daemonPath(context))} $hubEnvironment; exec ${tokens.joinToString(" ")}"
        return DaemonCommand(listOf(su, "-c", command))
    }

    private fun prootCommand(
        context: Context,
        request: DaemonLaunchRequest,
        container: Container,
    ): DaemonCommand {
        val proot = ProotInstaller.install(context).getOrThrow()
        val argv = ProotRunner.buildProotDaemonCommand(
            context = context,
            container = container,
            prootPath = proot.absolutePath,
            daemonPath = request.daemonExecutable,
            publicKey = request.publicKey,
            id = request.id,
        )
        // proot needs a writable host temp dir; Android has no /tmp.
        val env = Hub.environment() + mapOf(
            "PROOT_TMP_DIR" to ProotRunner.prootTmpDir(context, container).absolutePath,
            "PATH" to daemonPath(context),
        )
        return DaemonCommand(argv, env)
    }

    private fun daemonPath(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}:$DEFAULT_PATH"
}
