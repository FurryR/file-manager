package io.furryr.file.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.furryr.file.agent.Container
import io.furryr.file.agent.ContainerBackend
import io.furryr.file.agent.ContainerManager
import io.furryr.file.agent.ProotInstaller
import io.furryr.file.agent.ProotRunner
import io.furryr.file.util.HiddenApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

object DaemonLauncher {
    private const val TAG = "DaemonLauncher"
    private const val NativeLibName = "libfiledaemon.so"

    private lateinit var appContext: Context
    private var executableFile: File? = null

    private val connectionLock = Any()
    @Volatile private var cachedConnection: DaemonConnection? = null
    @Volatile private var appCachedConnection: DaemonConnection? = null

    /** One dedicated daemon per container, keyed by container name. */
    private val containerConnections = mutableMapOf<String, DaemonConnection>()

    @Volatile private var _readyGeneration: Int = 0
    val readyGeneration: Int get() = _readyGeneration

    private val _daemonState = MutableStateFlow<DaemonState>(DaemonState.Idle)
    val daemonState: StateFlow<DaemonState> = _daemonState.asStateFlow()

    sealed interface DaemonState {
        data object Idle : DaemonState
        data object Connecting : DaemonState
        data class Connected(val connection: DaemonConnection) : DaemonState
        data class Failed(val error: Throwable) : DaemonState
    }

    private var rootEnabled = false
    private var shellEnabled = false
    private var suPath = ""

    /** Set once a root daemon has actually connected. */
    @Volatile private var rootDaemonActive = false

    fun setRootEnabled(enabled: Boolean) { rootEnabled = enabled }
    fun setShellEnabled(enabled: Boolean) { shellEnabled = enabled }

    fun setSuPath(path: String) {
        // Auto-detect when unset so root availability is not reported as
        // "missing" merely because no custom su path was saved.
        suPath = path.ifBlank { detectSuPath() }
    }

    /** Config says we may start a root daemon. */
    fun canStartRootDaemon(): Boolean = rootEnabled && suPath.isNotBlank()

    /**
     * Root is actually usable: either it is configured, or a root daemon is
     * already running (e.g. started before the current configuration).
     */
    fun isRootAvailable(): Boolean = canStartRootDaemon() || rootDaemonActive

    /**
     * Get (or start) the dedicated daemon for [container].
     *
     * - chroot: launched as root via `su -c '<daemon> --socket … --chroot <rootfs> --bind …'`.
     *   The daemon isolates the mount tree and chroots itself.
     * - proot: launched as the app UID through proot, so the daemon process
     *   itself lives inside the container.
     */
    fun connectContainer(container: Container): DaemonConnection = synchronized(connectionLock) {
        containerConnections[container.name]?.takeIf { it.isAlive() }?.let {
            return@connectContainer it
        }
        containerConnections.remove(container.name)?.close()
        val conn = when (container.backend) {
            ContainerBackend.CHROOT -> {
                if (!canStartRootDaemon()) {
                    throw IllegalStateException(
                        "Container '${container.name}' uses the chroot backend but root is unavailable"
                    )
                }
                startChrootContainerDaemon(container)
            }
            ContainerBackend.PROOT -> startProotContainerDaemon(container)
        }
        containerConnections[container.name] = conn
        conn
    }

    /** [connectContainer] resolved by container name. */
    fun connectContainerByName(name: String): DaemonConnection {
        val container = ContainerManager.getContainer(appContext, name)
            ?: throw IllegalStateException("Container '$name' not found")
        return connectContainer(container)
    }

    /** Kill every container daemon (and its process tree) and forget them. */
    fun closeContainerConnections() = synchronized(connectionLock) {
        containerConnections.values.forEach { it.close() }
        containerConnections.clear()
    }

    /**
     * Kill a single container's dedicated daemon (and its process tree) and
     * forget it. The next access to the container starts a fresh daemon.
     */
    fun closeContainerConnection(name: String) = synchronized(connectionLock) {
        containerConnections.remove(name)?.close()
    }

    /** Locate the native daemon binary and ensure it is executable. */
    @Synchronized fun init(context: Context) {
        appContext = context.applicationContext
        // Lift the hidden-API restriction (needed for android.system.Os.recvmsg)
        // before any daemon I/O starts.
        HiddenApi.ensure()
        val nativeLib = File(appContext.applicationInfo.nativeLibraryDir, NativeLibName)
        if (!nativeLib.exists()) {
            Log.w(TAG, "Daemon binary not found at ${nativeLib.absolutePath}")
            return
        }
        try {
            nativeLib.setExecutable(true, true)
            executableFile = nativeLib
            Log.i(TAG, "Daemon binary at ${nativeLib.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make daemon binary executable", e)
        }
    }

    /** Connect to the daemon, starting it if needed. */
    fun connect(): DaemonConnection = synchronized(connectionLock) {
        cachedConnection?.let {
            if (it.isAlive()) return@connect it
            it.close()
            cachedConnection = null
        }
        _daemonState.value = DaemonState.Connecting
        try {
            val conn = startDaemon()
            cachedConnection = conn
            _readyGeneration++
            _daemonState.value = DaemonState.Connected(conn)
            conn
        } catch (e: Exception) {
            _daemonState.value = DaemonState.Failed(e)
            throw e
        }
    }

    /** Kill the daemon and remove the cached connection. */
    fun reset() = synchronized(connectionLock) {
        cachedConnection?.close()
        cachedConnection = null
        appCachedConnection?.close()
        appCachedConnection = null
        containerConnections.values.forEach { it.close() }
        containerConnections.clear()
        rootDaemonActive = false
        _daemonState.value = DaemonState.Idle
    }

    /** Get the current (or lazily-created) daemon connection.
     *  @param appDaemon when true returns a connection running under the app UID
     *         (for container:// and terminal access). If the main daemon already
     *         runs under the app UID (no root/shell), it is reused. */
    fun getConnection(appDaemon: Boolean = false): DaemonConnection = synchronized(connectionLock) {
        if (appDaemon) {
            if (!rootEnabled && !shellEnabled) {
                cachedConnection?.takeIf { it.isAlive() } ?: connect()
            } else {
                appCachedConnection?.takeIf { it.isAlive() } ?: startAppDaemon().also {
                    appCachedConnection = it
                }
            }
        } else {
            cachedConnection?.takeIf { it.isAlive() } ?: connect()
        }
    }

    /** Start a separate daemon instance for container (app UID) operations. */
    fun connectAppUid(): DaemonConnection = synchronized(connectionLock) {
        appCachedConnection?.takeIf { it.isAlive() } ?: startAppDaemon().also {
            appCachedConnection = it
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun startDaemon(): DaemonConnection = when {
        rootEnabled && suPath.isNotBlank() -> startRootDaemon()
        shellEnabled && isShizukuAvailable() && hasShizukuPermission() -> startShellDaemon()
        else -> startAppDaemon()
    }

    private fun getExecutable(): File {
        return executableFile ?: throw IllegalStateException(
            "Daemon binary not installed. Call init() first.")
    }

    private fun startAppDaemon(): DaemonConnection {
        val socketName = "file-daemon-${UUID.randomUUID()}"
        val executable = getExecutable()
        Log.i(TAG, "Starting daemon: ${executable.absolutePath} --socket $socketName")
        val process = ProcessBuilder(executable.absolutePath, "--socket", socketName)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        val socket = connectWithRetry(socketName)
        return DaemonConnection(socket, process)
    }

    private fun startRootDaemon(): DaemonConnection {
        val socketName = "file-daemon-${UUID.randomUUID()}"
        val executable = getExecutable()
        val cmd = "${shellQuote(executable.absolutePath)} --socket ${shellQuote(socketName)}"
        Log.i(TAG, "Starting root daemon: $suPath -c $cmd")
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        val socket = connectWithRetry(socketName)
        rootDaemonActive = true
        return DaemonConnection(socket, process)
    }

    private fun startChrootContainerDaemon(container: Container): DaemonConnection {
        val socketName = "file-daemon-${UUID.randomUUID()}"
        val executable = getExecutable()
        val binds = ProotRunner.chrootBinds(appContext, container)
        val tokens = mutableListOf(
            shellQuote(executable.absolutePath),
            "--socket", shellQuote(socketName),
            "--chroot", shellQuote(container.rootfsPath),
        )
        for ((source, target) in binds) {
            tokens.add("--bind")
            tokens.add(shellQuote("$source:$target"))
        }
        val cmd = tokens.joinToString(" ")
        Log.i(TAG, "Starting chroot daemon for '${container.name}': $suPath -c $cmd")
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        val socket = connectWithRetry(socketName)
        return DaemonConnection(socket, process)
    }

    private fun startProotContainerDaemon(container: Container): DaemonConnection {
        val socketName = "file-daemon-${UUID.randomUUID()}"
        val executable = getExecutable()
        val proot = ProotInstaller.install(appContext).getOrThrow()
        val cmd = ProotRunner.buildProotDaemonCommand(
            context = appContext,
            container = container,
            prootPath = proot.absolutePath,
            daemonPath = executable.absolutePath,
            socketName = socketName,
        )
        Log.i(TAG, "Starting proot daemon for '${container.name}': ${cmd.joinToString(" ")}")
        val process = ProcessBuilder(cmd)
            .directory(appContext.filesDir).redirectErrorStream(true)
            .apply {
                // proot needs a writable host temp dir; Android has no /tmp.
                environment()["PROOT_TMP_DIR"] = ProotRunner.prootTmpDir(appContext, container).absolutePath
            }
            .start()
        val socket = connectWithRetry(socketName)
        return DaemonConnection(socket, process)
    }

    private fun startShellDaemon(): DaemonConnection {
        val socketName = "file-daemon-${UUID.randomUUID()}"
        val executable = getExecutable()
        Log.i(TAG, "Starting shell daemon via Shizuku: ${executable.absolutePath} --socket $socketName")
        val process = shizukuNewProcess(
            arrayOf(executable.absolutePath, "--socket", socketName), null, appContext.filesDir.absolutePath)
        val socket = connectWithRetry(socketName)
        return DaemonConnection(socket, process)
    }

    internal fun connectWithRetry(socketName: String): OsSocket {
        var lastError: Exception? = null
        for (i in 0 until 50) {
            try {
                val sock = OsSocket.connect(socketName)
                Log.i(TAG, "Connected via abstract socket: $socketName")
                return sock
            } catch (e: Exception) { lastError = e; Thread.sleep(100) }
        }
        throw IllegalStateException("Failed to connect to daemon", lastError)
    }

    fun isShizukuAvailable() = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Exception) { false }
    fun hasShizukuPermission() = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun shizukuNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val m = clazz.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, cmd, env, dir) as Process
    }

    private fun shellQuote(path: String) = "'${path.replace("'", "'\\''")}'"
}
