package io.furryr.file.agent

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Builds the proot command line used to launch a **container daemon**.
 *
 * The daemon is started *through* proot, so the daemon process itself lives
 * inside the container. From then on it can spawn the container shell directly
 * and own the whole process tree. This is why `/data` must be bound: the
 * daemon binary (and proot) live under `/data/app/...`.
 *
 * Bind mount policy mirrors the native chroot backend: individual device nodes
 * instead of the whole `/dev`, plus `/dev/pts`, `/dev/shm`, `/proc`, `/sys`,
 * `/system`, `/apex`, `/linkerconfig/ld.config.txt`, `/vendor`, `/storage`, `/sdcard`,
 * `/data`, writable `/tmp` and `/run`, and a generated
 * `/etc/resolv.conf` and `/etc/profile.d/hub.sh`.
 */
object ProotRunner {

    /** Every bind mount used by the proot backend, in order. */
    fun bindMounts(context: Context, container: Container): List<BindMount> =
        standardMounts() + runtimeMounts(context, container) + container.bindMounts

    /** Existing container-private paths, without creating dirs or rewriting configuration. */
    fun privateRuntimeMounts(context: Context, container: Container): List<BindMount> {
        val runtime = runtimePath(context, container)
        return listOf(
            BindMount(File(runtime, "tmp/guest").absolutePath, "/tmp"),
            BindMount(File(runtime, "tmp/run").absolutePath, "/run"),
            BindMount(File(runtime, "resolv.conf").absolutePath, "/etc/resolv.conf"),
            BindMount(File(runtime, "hub.sh").absolutePath, "/etc/profile.d/hub.sh"),
        )
    }

    /** Guest paths which can cover rootfs entries; inspecting these never prepares a mount. */
    fun overlaidGuestPaths(context: Context, container: Container): List<String> {
        val configured = (standardMounts() + privateRuntimeMounts(context, container) + container.bindMounts)
            .map { it.dstContainerPath }
        // Native chroot binds all of /dev and /linkerconfig, mounts a fresh
        // /proc and creates private tmpfs at /tmp and /run (container.rs).
        val nativeMounts = if (container.backend == ContainerBackend.CHROOT) {
            listOf("/dev", "/linkerconfig", "/proc", "/tmp", "/run")
        } else emptyList()
        return (configured + nativeMounts).distinct()
    }

    /**
     * Binds passed to the native chroot backend via `--bind`. The chroot daemon
     * mounts `/tmp` and `/run` as tmpfs itself, so only the resolver, profile and user mounts are
     * forwarded.
     */
    fun chrootBinds(context: Context, container: Container): List<Pair<String, String>> =
        (listOf(resolverMount(context, container), hubProfileMount(context, container)) + container.bindMounts)
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
    )

    private fun runtimeMounts(context: Context, container: Container): List<BindMount> =
        listOf(
            BindMount(tempSubdir(context, container, "guest").absolutePath, "/tmp"),
            BindMount(tempSubdir(context, container, "run").absolutePath, "/run"),
            resolverMount(context, container),
            hubProfileMount(context, container),
        )

    /** Host directory proot uses for its own temporary files (`PROOT_TMP_DIR`). */
    fun prootTmpDir(context: Context, container: Container): File =
        tempSubdir(context, container, "proot")

    private fun tempSubdir(context: Context, container: Container, name: String): File {
        val tmpDir = File(runtimeDir(context, container), "tmp/$name")
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            throw IllegalStateException("Failed to create container temp directory: ${tmpDir.absolutePath}")
        }
        return tmpDir
    }

    private fun resolverMount(context: Context, container: Container): BindMount {
        val resolverFile = File(runtimeDir(context, container), "resolv.conf")
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val dnsServers = connectivityManager?.activeNetwork
            ?.let { network -> connectivityManager.getLinkProperties(network)?.dnsServers }
            ?.map { address -> "nameserver ${address.hostAddress}" }
            ?.distinct()
            .orEmpty()
        resolverFile.writeText(dnsServers.joinToString(separator = "\n", postfix = "\n"))
        return BindMount(resolverFile.absolutePath, "/etc/resolv.conf")
    }

    private fun hubProfileMount(context: Context, container: Container): BindMount {
        val profileFile = File(runtimeDir(context, container), "hub.sh")
        // Login profiles can replace the inherited PATH even with `login -p`.
        profileFile.writeText(
            "if [ -n \"\${HUB_PATH-}\" ]; then\n" +
                "    export PATH=\"\${PATH:+\$PATH:}\$HUB_PATH\"\n" +
                "    unset HUB_PATH\n" +
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
