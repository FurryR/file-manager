package io.furryr.file.agent

import android.content.Context
import io.furryr.file.core.DaemonCommand
import io.furryr.file.core.DaemonLaunchRequest
import io.furryr.file.core.shellQuote

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
    fun command(context: Context, request: DaemonLaunchRequest): DaemonCommand? {
        val container = ContainerManager.getContainer(context, request.name) ?: return null
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
        return DaemonCommand(listOf(su, "-c", tokens.joinToString(" ")))
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
        val env = mapOf(
            "PROOT_TMP_DIR" to ProotRunner.prootTmpDir(context, container).absolutePath
        )
        return DaemonCommand(argv, env)
    }
}
