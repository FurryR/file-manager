package io.furryr.file.agent

import android.content.Context

/**
 * Builds proot command-line arguments for running commands inside containers.
 *
 * The proot binary is expected at `filesDir/proot-aarch64` (installed by [ProotInstaller]).
 * Default bind mounts (sdcard, system, app data) are always included first,
 * followed by any user-configured mounts from [Container.bindMounts].
 *
 * Proot command format:
 *   proot -r rootfs [-b src:dst]... [-w workdir] command [args...]
 */
object ProotRunner {
    private const val DEFAULT_SHELL = "/bin/sh"

    /**
     * Builds a full proot command line including the binary path from
     * [ProotInstaller.expectedPath].
     *
     * If [command] is non-empty, the command is executed via `/bin/sh -c <command>`.
     * If [command] is empty, an interactive `/bin/sh` shell is started.
     *
     * Output format (command mode):
     *   [proot-binary, -r, rootfsPath, -b, src:dst, ..., -w, /, /bin/sh, -c, command...]
     *
     * Output format (interactive mode):
     *   [proot-binary, -r, rootfsPath, -b, src:dst, ..., -w, /, /bin/sh]
     */
    fun buildProotCommand(
        context: Context,
        container: Container,
        command: List<String>,
    ): List<String> {
        val binaryPath = ProotInstaller.expectedPath(context).absolutePath
        val args = buildProotArgs(context, container).toMutableList()
        if (command.isNotEmpty()) {
            args.add("-c")
            args.addAll(command)
        }
        return listOf(binaryPath) + args
    }

    /**
     * Builds proot arguments only (without the binary path), suitable for an
     * interactive shell session.
     *
     * Output format:
     *   [-r, rootfsPath, -b, src:dst, ..., -w, /, /bin/sh]
     */
    fun buildProotArgs(
        context: Context,
        container: Container,
    ): List<String> {
        val args = mutableListOf("-r", container.rootfsPath)
        args.addAll(buildBindMountArgs(container))
        args.add("-w")
        args.add("/")
        args.add(DEFAULT_SHELL)
        return args
    }

    /**
     * Generates bind mount arguments (`-b src:dst`) for proot.
     *
     * Default mounts (always present, in this order):
     *   1. /storage/emulated/0/   → /android/sdcard
     *   2. /system/               → /android/system
     *   3. /data/data/io.furryr.file/ → /android/data
     *
     * User-configured mounts from [Container.bindMounts] are appended after defaults.
     */
    private fun buildBindMountArgs(container: Container): List<String> {
        val defaultMounts = listOf(
            BindMount("/storage/emulated/0/", "/sdcard"),
            BindMount("/system/", "/system"),
            BindMount("/vendor/", "/vendor")
        )
        val allMounts = defaultMounts + container.bindMounts
        val result = mutableListOf<String>()
        for (mount in allMounts) {
            result.add("-b")
            result.add("${mount.srcAndroidPath}:${mount.dstContainerPath}")
        }
        return result
    }
}
