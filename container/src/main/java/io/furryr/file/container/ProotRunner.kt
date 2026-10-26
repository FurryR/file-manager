package io.furryr.file.container

import android.content.Context
import android.net.ConnectivityManager
import io.furryr.file.pulse.PulseManager
import io.furryr.file.wayland.WaylandManager
import java.io.File
import java.net.InetAddress

/**
 * Builds the proot command line used to launch a **container daemon**.
 *
 * The daemon is started *through* proot, so the daemon process itself lives
 * inside the container. From then on it can spawn the container shell directly
 * and own the whole process tree. This is why `/data` must be bound: the
 * daemon binary (and proot) live under `/data/app/...`.
 *
 * Bind mount policy mirrors the native chroot backend: individual device
 * nodes instead of the whole `/dev`, plus `/dev/pts`, `/dev/shm`, `/proc`,
 * `/sys`, `/system`, `/apex`, `/linkerconfig/ld.config.txt`, `/vendor`,
 * `/storage`, `/sdcard`, `/data`, writable `/tmp` and `/run`, a generated
 * `/etc/resolv.conf` and `/etc/profile.d/hub.sh`, the PulseAudio and Wayland
 * socket directories, and — when the KGSL command device is present — the
 * Adreno GPU nodes for turnip + zink.
 */
object ProotRunner {

    /** Every bind mount used by the proot backend, in order. */
    fun bindMounts(context: Context, container: Container): List<BindMount> =
        standardMounts() + runtimeMounts(context, container) + container.bindMounts

    /** Inspect the configured namespace without preparing runtime files. */
    fun pathMounts(context: Context, container: Container): List<BindMount> =
        if (container.backend == ContainerBackend.PROOT) {
            standardMounts() + privateRuntimeMounts(context, container) + container.bindMounts
        } else {
            listOf("/dev", "/sys", "/system", "/apex", "/linkerconfig", "/vendor", "/storage", "/data")
                .map { BindMount(it, it) } +
                BindMount("/storage/emulated/0", "/sdcard") +
                privateRuntimeMounts(context, container).filter {
                    it.dstContainerPath != "/tmp" && it.dstContainerPath != "/run"
                } + container.bindMounts
        }

    /** Existing container-private paths, without creating dirs or rewriting configuration. */
    fun privateRuntimeMounts(context: Context, container: Container): List<BindMount> {
        val runtime = runtimePath(context, container)
        return listOf(
            BindMount(File(runtime, "tmp/guest").absolutePath, "/tmp"),
            BindMount(File(runtime, "tmp/run").absolutePath, "/run"),
            BindMount(sharedResolverFile(context).absolutePath, "/etc/resolv.conf"),
            BindMount(sharedHubProfileFile(context).absolutePath, "/etc/profile.d/hub.sh"),
        )
    }

    /**
     * Binds passed to the native chroot backend via `--bind`. The chroot daemon
     * mounts `/tmp` and `/run` as tmpfs itself, so only the resolver, profile and user mounts are
     * forwarded.
     */
    fun chrootBinds(context: Context, container: Container): List<Pair<String, String>> =
        (listOfNotNull(
            resolverMount(context),
            hubProfileMount(context),
            if (PulseManager.isEnabled(context)) pulseMount(context) else null,
        ) + waylandMounts(context) + container.bindMounts)
            .filter { File(it.srcAndroidPath).exists() }
            .map { it.srcAndroidPath to it.dstContainerPath }

    /**
     * Full argv to launch the container daemon under proot:
     *   proot -l -0 -r rootfs -b ... -w / <daemon> --key <pub> --id <id> --proot
     */
    fun buildProotDaemonCommand(
        context: Context,
        container: Container,
        prootPath: String,
        daemonPath: String,
        publicKey: String,
        id: String,
    ): List<String> {
        // `-l` (--link2symlink) is required: Android's app data is mounted
        // with SELinux policies that forbid hard links, so any hardlink(2)
        // inside the container fails with EACCES. apk-tools publishes files
        // atomically via O_TMPFILE + linkat("/proc/self/fd/N"), so without
        // this every `apk` index/database write fails with "Permission denied".
        val args = mutableListOf(prootPath, "-l", "-0", "-r", container.rootfsPath)
        // Directory binds resolve fresh socket inodes after listener recreation.
        prepareGuestDir(File(container.rootfsPath, PulseManager.CONTAINER_SOCKET_DIR.trimStart('/')))
        preparePulseTarget(File(container.rootfsPath, PulseManager.CONTAINER_SOCKET.trimStart('/')))
        prepareGuestDir(File(container.rootfsPath, WaylandManager.CONTAINER_RUNTIME_DIR.trimStart('/')))
        for (mount in bindMounts(context, container)) {
            if (!File(mount.srcAndroidPath).exists()) continue
            args.add("-b")
            args.add("${mount.srcAndroidPath}:${mount.dstContainerPath}")
        }
        args.add("-w")
        args.add("/")
        args.add(daemonPath)
        args.add("--key")
        args.add(publicKey)
        args.add("--id")
        args.add(id)
        args.add("--proot")
        return args
    }

    private fun standardMounts(): List<BindMount> = listOf(
        BindMount("/dev/null", "/dev/null"),
        BindMount("/dev/zero", "/dev/zero"),
        BindMount("/dev/random", "/dev/random"),
        BindMount("/dev/urandom", "/dev/urandom"),
        BindMount("/dev/tty", "/dev/tty"),
        BindMount("/dev/ptmx", "/dev/ptmx"),
        BindMount("/dev/pts", "/dev/pts"),
        BindMount("/dev/shm", "/dev/shm"),
        BindMount("/proc", "/proc"),
        BindMount("/sys", "/sys"),
        BindMount("/system", "/system"),
        // Android 10+ keeps the dynamic linker and bionic in /apex, with its
        // config in /linkerconfig. Needed so the Android-built daemon binary can
        // be exec'd under proot.
        BindMount("/apex", "/apex"),
        // App UIDs can read this file even when stat on /linkerconfig is denied,
        // which makes proot discard a bind of the whole directory.
        BindMount("/linkerconfig/ld.config.txt", "/linkerconfig/ld.config.txt"),
        BindMount("/vendor", "/vendor"),
        BindMount("/storage", "/storage"),
        BindMount("/storage/emulated/0", "/sdcard"),
        BindMount("/data", "/data"),
    ) + gpuMounts()

    /**
     * Qualcomm Adreno GPU pass-through, gated on probing the KGSL command
     * device: when `/dev/kgsl-3d0` exists, expose it plus the buffer
     * allocation devices turnip needs, so proot containers get the same GPU
     * acceleration as the host (turnip + zink). The chroot backend needs no
     * equivalent — its private `/dev` tmpfs already replicates these nodes
     * from the host device tree.
     */
    private fun gpuMounts(): List<BindMount> {
        if (!File("/dev/kgsl-3d0").exists()) return emptyList()
        return listOfNotNull(
            BindMount("/dev/kgsl-3d0", "/dev/kgsl-3d0"),
            File("/dev/dma_heap").takeIf { it.isDirectory }
                ?.let { BindMount("/dev/dma_heap", "/dev/dma_heap") },
            File("/dev/ion").takeIf { it.exists() }
                ?.let { BindMount("/dev/ion", "/dev/ion") },
        )
    }

    private fun runtimeMounts(context: Context, container: Container): List<BindMount> =
        listOfNotNull(
            BindMount(tempSubdir(context, container, "guest").absolutePath, "/tmp"),
            BindMount(tempSubdir(context, container, "run").absolutePath, "/run"),
            if (PulseManager.isEnabled(context)) pulseMount(context) else null,
            resolverMount(context),
            hubProfileMount(context),
        ) + waylandMounts(context)

    /** Wayland viewer mounts: the socket dir, plus vtest/shims for virgl modes. */
    private fun waylandMounts(context: Context): List<BindMount> =
        WaylandManager.bindMounts(context).map { (src, dst) -> BindMount(src, dst) }

    private fun pulseMount(context: Context): BindMount =
        BindMount(PulseManager.socketDir(context).absolutePath, PulseManager.CONTAINER_SOCKET_DIR)

    /** Keep the public endpoint /dev/pulse while binding its parent directory. */
    private fun preparePulseTarget(target: File) {
        target.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
            if (java.nio.file.Files.readSymbolicLink(target.toPath()).toString() == PulseManager.CONTAINER_SOCKET_TARGET) return
            if (!target.delete()) error("Cannot replace legacy PulseAudio socket alias: $target")
        } else if (target.exists() && !target.delete()) {
            error("Cannot replace legacy PulseAudio socket target: $target")
        }
        android.system.Os.symlink(PulseManager.CONTAINER_SOCKET_TARGET, target.absolutePath)
    }

    /** Host directory proot uses for its own temporary files (`PROOT_TMP_DIR`). */
    fun prootTmpDir(context: Context, container: Container): File =
        tempSubdir(context, container, "proot")

    /** The guest bind target must be a directory; older launches left a file. */
    private fun prepareGuestDir(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists() && !target.isDirectory && !target.delete()) {
            throw IllegalStateException("Cannot replace container bind target with a directory: $target")
        }
        if (!target.exists() && !target.mkdirs()) {
            throw IllegalStateException("Cannot create container bind target directory: $target")
        }
    }

    private fun tempSubdir(context: Context, container: Container, name: String): File {
        val tmpDir = File(runtimeDir(context, container), "tmp/$name")
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw IllegalStateException("Failed to create container temp directory: ${tmpDir.absolutePath}")
        }
        return tmpDir
    }

    /**
     * The resolver and login profile are shared by every container: one file,
     * written **in place** (truncate + write, never delete + recreate) so live
     * binds see updates — the proot bind is path translation and re-reads
     * the file on every open, and a chroot `mount --bind` pins the inode but
     * still shows in-place writes.
     */
    private fun sharedRuntimeDir(context: Context): File =
        File(context.cacheDir, "container").apply { mkdirs() }

    private fun sharedResolverFile(context: Context): File =
        File(sharedRuntimeDir(context), "resolv.conf")

    private fun sharedHubProfileFile(context: Context): File =
        File(sharedRuntimeDir(context), "hub.sh")

    /** Refresh the shared resolv.conf from the active network (launch + link changes). */
    fun refreshResolverFromActive(context: Context) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val dnsServers = connectivityManager?.activeNetwork
            ?.let { network -> connectivityManager.getLinkProperties(network)?.dnsServers }
            .orEmpty()
        writeResolver(context, dnsServers)
    }

    private fun resolverMount(context: Context): BindMount {
        refreshResolverFromActive(context)
        return BindMount(sharedResolverFile(context).absolutePath, "/etc/resolv.conf")
    }

    private fun writeResolver(context: Context, dnsServers: List<InetAddress>) {
        // A network flap can momentarily report no DNS; keep the last known
        // servers instead of publishing an empty resolver.
        if (dnsServers.isEmpty()) return
        val content = dnsServers
            .mapNotNull { address -> address.hostAddress }
            .map { address -> "nameserver $address" }
            .distinct()
            .joinToString(separator = "\n", postfix = "\n")
        val file = sharedResolverFile(context)
        if (file.exists() && runCatching { file.readText() }.getOrNull() == content) return
        file.writeText(content)
    }

    private fun hubProfileMount(context: Context): BindMount {
        val profileFile = sharedHubProfileFile(context)
        // Login profiles can replace the inherited PATH even with `login -p`.
        profileFile.writeText(
            "if [ -n \"\${HUB_PATH-}\" ]; then\n" +
                "    export PATH=\"\${PATH:+\$PATH:}\$HUB_PATH\"\n" +
                "    unset HUB_PATH\n" +
                "fi\n" +
                "if [ -n \"\${CONTAINER_START_PATH-}\" ]; then\n" +
                "    cd \"\$CONTAINER_START_PATH\" 2>/dev/null\n" +
                "    unset CONTAINER_START_PATH\n" +
                "fi\n",
        )
        return BindMount(profileFile.absolutePath, "/etc/profile.d/hub.sh")
    }

    private fun runtimeDir(context: Context, container: Container): File {
        val dir = runtimePath(context, container)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Failed to create container runtime directory: ${dir.absolutePath}")
        }
        return dir
    }

    private fun runtimePath(context: Context, container: Container): File {
        val id = container.name.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
        }.joinToString("").ifEmpty { "container" }
        return File(context.cacheDir, "container/$id")
    }
}
