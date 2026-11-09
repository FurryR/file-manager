package io.furryr.file.container

import android.content.Context
import io.furryr.file.daemon.DaemonCommand
import io.furryr.file.daemon.DaemonLaunchRequest
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.isSuAvailable
import io.furryr.file.daemon.shellQuote
import io.furryr.file.pulse.PulseManager
import io.furryr.file.wayland.WaylandManager

/**
 * App-owned daemon launcher: builds the daemon argv for a container,
 * honouring its fixed backend. This is where proot/chroot knowledge lives —
 * `:core` never sees it.
 *
 * - chroot: launched as root via `su -c '<daemon> --key <pub> --id <id> --chroot <rootfs> --bind …'`.
 *   It also owns the container cgroup (`--group <name>`): the daemon outlives the
 *   app on purpose (its parent is the su daemon, not the app) so that it can end
 *   the container when its last client goes away, and `cgroup::sweep` ends groups
 *   whose owner died. proot uses PRoot's `--cgroup` emulation instead (below).
 *   The daemon isolates the mount tree and chroots itself.
 * - proot: launched as the app UID through proot, so the daemon process itself
 *   lives inside the container.  proot's `--cgroup` emulation gives the daemon
 *   a private cgroup v2 hierarchy, so `--group` still owns the container
 *   lifetime even though the app cannot create real cgroups.
 */
object ContainerDaemonLauncher {
    const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun command(context: Context, request: DaemonLaunchRequest): DaemonCommand? {
        val container = ContainerManager.getContainer(context, request.name) ?: return null
        PulseManager.ensureHost(context)
        WaylandManager.ensureHost(context)
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
            "Permission denied"
        }
        val tokens = mutableListOf(
            shellQuote(request.daemonExecutable),
            "--key", shellQuote(request.publicKey),
            "--id", shellQuote(request.id),
            "--chroot", shellQuote(container.rootfsPath),
            "--group", shellQuote(container.name),
        )
        for ((source, target) in ProotRunner.chrootBinds(context, container)) {
            tokens.add("--bind")
            tokens.add(shellQuote("$source:$target"))
        }
        val su = request.suPath.ifBlank { "su" }
        val hostEnv = (PulseManager.environment() + WaylandManager.environment(context)).entries
            .joinToString(" ") { "${it.key}=${shellQuote(it.value)}" }
        val command = "export PATH=${shellQuote(daemonPath(context))}${if (hostEnv.isEmpty()) "" else " $hostEnv"}; " +
            "exec ${tokens.joinToString(" ")}"
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
        val env = mapOf(
            "PROOT_TMP_DIR" to ProotRunner.prootTmpDir(context, container).absolutePath,
            "PATH" to daemonPath(context),
        ) + PulseManager.environment() + WaylandManager.environment(context)
        return DaemonCommand(argv, env)
    }

    private fun daemonPath(context: Context): String =
        "$DEFAULT_PATH:${context.applicationInfo.nativeLibraryDir}"
}
