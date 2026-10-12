package io.furryr.file.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.furryr.file.core.file.FileService
import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.core.ipc.OsSocket
import io.furryr.file.core.process.ExecService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** Which daemon a caller wants to talk to. */
sealed interface DaemonTarget {
    /** The root daemon when configured, otherwise the app-UID daemon. */
    data object Primary : DaemonTarget

    /** A daemon forced to run under the app UID (never elevated). */
    data object AppUid : DaemonTarget

    /** A named daemon (e.g. a container), launched by a registered [DaemonLauncher]. */
    data class Named(val name: String) : DaemonTarget
}

/** Observable daemon lifecycle state for the primary connection. */
sealed interface DaemonStatus {
    data object Idle : DaemonStatus
    data object Connecting : DaemonStatus
    data object Connected : DaemonStatus
    data class Failed(val error: Throwable) : DaemonStatus
}

/** Everything a [DaemonLauncher] needs to build a daemon command line. */
data class DaemonLaunchRequest(
    val name: String,
    val daemonExecutable: String,
    val socketName: String,
    val filesDir: String,
    /** The configured `su` path, for launchers that need root escalation. */
    val suPath: String,
)

/** The argv (+ env) that starts a daemon, as produced by a [DaemonLauncher]. */
data class DaemonCommand(
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
)

/**
 * SPI for launching a *named* daemon. Core owns the app / root / shell launch
 * strategies; hosts register a launcher for names they own (e.g. containers
 * via proot or chroot). Returning `null` means "not handled by this launcher".
 *
 * This is the single seam that lets the UI side decide *how* a daemon starts;
 * core itself stays backend-agnostic.
 */
fun interface DaemonLauncher {
    fun launch(request: DaemonLaunchRequest): DaemonCommand?
}

/**
 * Owns daemon process lifecycle and hands out per-target [FileService] /
 * [ExecService] facades. This is the only type the UI layer needs to reach the
 * transport; sockets, framing and protobuf stay internal to `:core`.
 */
object DaemonManager {
    private const val TAG = "DaemonManager"
    private const val NativeLibName = "libfiledaemon.so"

    private lateinit var appContext: Context
    private var executableFile: File? = null

    private val lock = Any()
    @Volatile private var primary: DaemonConnection? = null
    @Volatile private var appUid: DaemonConnection? = null
    private val named = mutableMapOf<String, DaemonConnection>()

    @Volatile private var launcher: DaemonLauncher? = null
    @Volatile private var registry: DaemonRegistry = DaemonRegistry { emptyList() }

    @Volatile private var _generation = 0

    /** Bumped whenever a fresh primary connection is established. */
    val generation: Int get() = _generation

    private val _status = MutableStateFlow<DaemonStatus>(DaemonStatus.Idle)
    val status: StateFlow<DaemonStatus> = _status.asStateFlow()

    private var rootEnabled = false
    private var shellEnabled = false
    private var suPath = ""

    /** Set once a root daemon has actually connected. */
    @Volatile private var rootActive = false

    // ── Configuration ─────────────────────────────────────────────────

    @Synchronized
    fun init(context: Context) {
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

    fun configure(rootEnabled: Boolean, shellEnabled: Boolean, suPath: String) {
        setRootEnabled(rootEnabled)
        setShellEnabled(shellEnabled)
        setSuPath(suPath)
    }

    fun setRootEnabled(enabled: Boolean) { rootEnabled = enabled }
    fun setShellEnabled(enabled: Boolean) { shellEnabled = enabled }

    fun setSuPath(path: String) {
        // Auto-detect when unset so root availability is not reported as
        // "missing" merely because no custom su path was saved.
        suPath = path.ifBlank { detectSuPath() }
    }

    /** Install the launch strategy used for [DaemonTarget.Named] daemons. */
    fun setLauncher(launcher: DaemonLauncher?) { this.launcher = launcher }

    /** Install the endpoint registry (the run0 attach seam). */
    fun setRegistry(registry: DaemonRegistry) { this.registry = registry }

    /** Live endpoints an external client could attach to (future run0). */
    fun endpoints(): List<DaemonEndpoint> = registry.endpoints()

    // ── Privilege queries ─────────────────────────────────────────────

    /** Config says we may start a root daemon. */
    fun canStartRootDaemon(): Boolean = rootEnabled && suPath.isNotBlank()

    /**
     * Root is actually usable: either it is configured, or a root daemon is
     * already running (e.g. started before the current configuration).
     */
    fun isRootAvailable(): Boolean = canStartRootDaemon() || rootActive

    fun isShizukuAvailable(): Boolean =
        try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Exception) { false }

    fun hasShizukuPermission(): Boolean = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /** Start (or reuse) the primary daemon and drop the connection. */
    fun warmUp(): Result<Unit> = runCatching { connection(DaemonTarget.Primary) }

    /** Start (or reuse) a named daemon (e.g. a container) without using it. */
    fun warmNamed(name: String): Result<Unit> = runCatching { connection(DaemonTarget.Named(name)) }

    /** Kill every daemon (process tree included) and forget them. */
    fun reset() = synchronized(lock) {
        primary?.close(); primary = null
        appUid?.close(); appUid = null
        named.values.forEach { it.close() }; named.clear()
        rootActive = false
        _status.value = DaemonStatus.Idle
    }

    /** Kill a single named daemon; the next access starts a fresh one. */
    fun closeNamedConnection(name: String) = synchronized(lock) {
        named.remove(name)?.close()
    }

    /** Kill every named daemon. */
    fun closeNamedConnections() = synchronized(lock) {
        named.values.forEach { it.close() }; named.clear()
    }

    fun isNamedConnected(name: String): Boolean = synchronized(lock) {
        named[name]?.isAlive() == true
    }

    /**
     * Ask an already-connected daemon to shut down gracefully. Does not start
     * one. Any client may send this; the daemon exits on the first one.
     */
    fun exit(target: DaemonTarget = DaemonTarget.Primary) = synchronized(lock) {
        when (target) {
            DaemonTarget.Primary -> primary?.exit()
            DaemonTarget.AppUid -> appUid?.exit()
            is DaemonTarget.Named -> named[target.name]?.exit()
        }
    }

    // ── Service facades ───────────────────────────────────────────────

    fun fileService(target: DaemonTarget = DaemonTarget.Primary): FileService =
        FileService { connection(target) }

    fun execService(target: DaemonTarget = DaemonTarget.Primary): ExecService =
        ExecService { connection(target) }

    // ── Internal ──────────────────────────────────────────────────────

    internal fun connection(target: DaemonTarget): DaemonConnection = synchronized(lock) {
        when (target) {
            DaemonTarget.Primary -> primary?.takeIf { it.isAlive() } ?: startPrimaryLocked()
            DaemonTarget.AppUid -> {
                if (!rootEnabled && !shellEnabled) {
                    primary?.takeIf { it.isAlive() } ?: startPrimaryLocked()
                } else {
                    appUid?.takeIf { it.isAlive() } ?: startAppDaemon().also { appUid = it }
                }
            }
            is DaemonTarget.Named -> named[target.name]?.takeIf { it.isAlive() } ?: run {
                named.remove(target.name)?.close()
                startNamedDaemon(target.name).also { named[target.name] = it }
            }
        }
    }

    private fun startPrimaryLocked(): DaemonConnection {
        primary?.let { if (it.isAlive()) return it; it.close(); primary = null }
        _status.value = DaemonStatus.Connecting
        return try {
            startPrimary().also {
                primary = it
                _generation++
                _status.value = DaemonStatus.Connected
            }
        } catch (e: Exception) {
            _status.value = DaemonStatus.Failed(e)
            throw e
        }
    }

    private fun startPrimary(): DaemonConnection = when {
        rootEnabled && suPath.isNotBlank() -> startRootDaemon()
        shellEnabled && isShizukuAvailable() && hasShizukuPermission() -> startShellDaemon()
        else -> startAppDaemon()
    }

    private fun startAppDaemon(): DaemonConnection {
        val socketName = newSocketName()
        val executable = getExecutable()
        Log.i(TAG, "Starting daemon: ${executable.absolutePath} --socket $socketName")
        val process = ProcessBuilder(executable.absolutePath, "--socket", socketName)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        return DaemonConnection(socketName, connectWithRetry(socketName), process)
    }

    private fun startRootDaemon(): DaemonConnection {
        val socketName = newSocketName()
        val executable = getExecutable()
        val cmd = "${shellQuote(executable.absolutePath)} --socket ${shellQuote(socketName)}"
        Log.i(TAG, "Starting root daemon: $suPath -c $cmd")
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        rootActive = true
        return DaemonConnection(socketName, connectWithRetry(socketName), process)
    }

    private fun startShellDaemon(): DaemonConnection {
        val socketName = newSocketName()
        val executable = getExecutable()
        Log.i(TAG, "Starting shell daemon via Shizuku: ${executable.absolutePath} --socket $socketName")
        val process = shizukuNewProcess(
            arrayOf(executable.absolutePath, "--socket", socketName), null,
            appContext.filesDir.absolutePath)
        return DaemonConnection(socketName, connectWithRetry(socketName), process)
    }

    private fun startNamedDaemon(name: String): DaemonConnection {
        val activeLauncher = launcher
            ?: throw IllegalStateException("No DaemonLauncher registered for '$name'")
        val request = DaemonLaunchRequest(
            name = name,
            daemonExecutable = getExecutable().absolutePath,
            socketName = newSocketName(),
            filesDir = appContext.filesDir.absolutePath,
            suPath = suPath,
        )
        val command = activeLauncher.launch(request)
            ?: throw IllegalStateException("No launcher handled daemon '$name'")
        Log.i(TAG, "Starting '$name' daemon: ${command.command.joinToString(" ")}")
        val process = ProcessBuilder(command.command)
            .directory(appContext.filesDir).redirectErrorStream(true)
            .apply { environment().putAll(command.env) }
            .start()
        return DaemonConnection(request.socketName, connectWithRetry(request.socketName), process)
    }

    private fun getExecutable(): File = executableFile
        ?: throw IllegalStateException("Daemon binary not installed. Call init() first.")

    private fun newSocketName() = "file-daemon-${UUID.randomUUID()}"

    private fun connectWithRetry(socketName: String): OsSocket {
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

    private fun shizukuNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val m = clazz.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, cmd, env, dir) as Process
    }
}
