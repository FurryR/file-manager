package io.furryr.file.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.furryr.file.core.file.FileService
import io.furryr.file.core.hub.HubServer
import io.furryr.file.core.ipc.DaemonConnection
import io.furryr.file.core.process.ExecService
import io.furryr.file.uds.Transport
import io.furryr.file.uds.UdsClient
import io.furryr.file.uds.UdsEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File

/** Which daemon a caller wants to talk to. */
sealed interface DaemonTarget {
    /** The elevated daemon (root via `su`, else Shizuku) when available, otherwise the app daemon. */
    data object Primary : DaemonTarget

    /** A daemon forced to run under the app UID (never elevated). */
    data object App : DaemonTarget

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
    /** Derived abstract socket name (from [publicKey] and [id]). */
    val socketName: String,
    /** Hex-encoded Ed25519 public key the daemon authenticates against. */
    val publicKey: String,
    /** Target id the socket name is derived from (e.g. a container name). */
    val id: String,
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
 * SPI for launching a *named* daemon. Core owns the app / privileged (root or
 * Shizuku) launch strategies; hosts register a launcher for names they own
 * (e.g. containers via proot or chroot). Returning `null` means "not handled by
 * this launcher".
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
    private const val DaemonBinaryName = "libfiledaemon.so"

    /** Stable abstract endpoint the run0 communication hub listens on. */
    internal const val DefaultHubName = "io.furryr.file.hub"

    /** Target ids the daemon socket names are derived from. The elevated
     *  daemon is a single target: launched with `su` when root is available,
     *  otherwise via Shizuku. */
    private const val PrivilegedId = "privileged"
    private const val AppId = "app"

    private lateinit var appContext: Context
    private var executableFile: File? = null

    private val lock = Any()
    @Volatile private var primary: DaemonConnection? = null
    @Volatile private var app: DaemonConnection? = null
    private val named = mutableMapOf<String, DaemonConnection>()

    @Volatile private var hub: HubServer? = null

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
        // Load or create the app's Ed25519 identity before any daemon starts:
        // its public key is what a daemon is launched with and what socket
        // names are derived from.
        runCatching { DaemonIdentity.init(appContext) }
            .onFailure { Log.e(TAG, "Failed to initialize daemon identity", it) }
        // Lift the hidden-API restriction (needed for android.system.Os.recvmsg)
        // before any daemon I/O starts.
        HiddenApi.ensure()
        val nativeLib = File(appContext.applicationInfo.nativeLibraryDir, DaemonBinaryName)
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

    /** Live daemon endpoints with their actual sockets, ready for hub attach. */
    fun liveEndpoints(): List<DaemonEndpoint> = synchronized(lock) {
        val list = ArrayList<DaemonEndpoint>()
        primary?.takeIf { it.isAlive() }?.let {
            list.add(DaemonEndpoint("primary", it.socketName, rootActive))
        }
        app?.takeIf { it.isAlive() }?.let {
            list.add(DaemonEndpoint("app", it.socketName, false))
        }
        named.forEach { (name, connection) ->
            if (connection.isAlive()) list.add(DaemonEndpoint(name, connection.socketName, false))
        }
        list
    }

    /**
     * Start the run0 communication hub on [endpointName] (idempotent). The hub
     * relays frames, including descriptors, to whichever live daemon a client
     * attaches to — it parses only its own handshake.
     */
    fun startHub(endpointName: String = DefaultHubName) {
        synchronized(lock) {
            if (hub != null) return
            hub = HubServer.listen(UdsEndpoint.Abstract(endpointName))
            Log.i(TAG, "Hub listening at $endpointName")
        }
    }

    fun stopHub() = synchronized(lock) {
        hub?.close()
        hub = null
    }

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
        app?.close(); app = null
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
            DaemonTarget.App -> app?.exit()
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
            DaemonTarget.App -> {
                if (!rootEnabled && !shellEnabled) {
                    primary?.takeIf { it.isAlive() } ?: startPrimaryLocked()
                } else {
                    app?.takeIf { it.isAlive() } ?: startAppDaemon().also { app = it }
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

    private fun startPrimary(): DaemonConnection {
        // The elevated daemon is a single target with a single id: launched with
        // `su` when root is available, otherwise via Shizuku. Because the socket
        // name is shared, the two can never coexist — whoever is already running
        // is re-attached to.
        if (rootEnabled && suPath.isNotBlank()) {
            if (isSuAvailable(suPath)) {
                runCatching { return startRootDaemon() }
                    .onFailure { Log.w(TAG, "Root daemon start failed; falling back", it) }
            } else {
                Log.w(TAG, "su at '$suPath' is not usable; skipping root daemon")
            }
        }
        if (shellEnabled && isShizukuAvailable() && hasShizukuPermission()) {
            runCatching { return startShizukuDaemon() }
                .onFailure { Log.w(TAG, "Shizuku daemon start failed; falling back", it) }
        }
        return startAppDaemon()
    }

    private fun startAppDaemon(): DaemonConnection {
        attachExisting(AppId)?.let { return it }
        val id = AppId
        val socketName = DaemonIdentity.socketName(id)
        val executable = getExecutable()
        Log.i(TAG, "Starting daemon: ${executable.absolutePath} --id $id")
        val process = ProcessBuilder(
            executable.absolutePath, "--key", DaemonIdentity.publicKeyHex(), "--id", id,
        ).directory(appContext.filesDir).redirectErrorStream(true).start()
        return DaemonConnection(socketName, connectWithRetry(socketName), process)
    }

    private fun startRootDaemon(): DaemonConnection {
        attachExisting(PrivilegedId)?.let { rootActive = true; return it }
        val id = PrivilegedId
        val socketName = DaemonIdentity.socketName(id)
        val executable = getExecutable()
        val cmd = "${shellQuote(executable.absolutePath)} --key ${shellQuote(DaemonIdentity.publicKeyHex())} --id ${shellQuote(id)}"
        Log.i(TAG, "Starting root daemon: $suPath -c $cmd")
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        val connection = DaemonConnection(socketName, connectWithRetry(socketName), process)
        rootActive = true
        return connection
    }

    private fun startShizukuDaemon(): DaemonConnection {
        attachExisting(PrivilegedId)?.let { return it }
        val id = PrivilegedId
        val socketName = DaemonIdentity.socketName(id)
        val executable = getExecutable()
        Log.i(TAG, "Starting daemon via Shizuku: ${executable.absolutePath} --id $id")
        val process = shizukuNewProcess(
            arrayOf(executable.absolutePath, "--key", DaemonIdentity.publicKeyHex(), "--id", id), null,
            appContext.filesDir.absolutePath)
        return DaemonConnection(socketName, connectWithRetry(socketName), process)
    }

    private fun startNamedDaemon(name: String): DaemonConnection {
        // Prefix the id so container names can never collide with the reserved
        // primary ids (privileged/app).
        val id = namedDaemonId(name)
        attachExisting(id)?.let { return it }
        val activeLauncher = launcher
            ?: throw IllegalStateException("No DaemonLauncher registered for '$name'")
        val request = DaemonLaunchRequest(
            name = name,
            daemonExecutable = getExecutable().absolutePath,
            socketName = DaemonIdentity.socketName(id),
            publicKey = DaemonIdentity.publicKeyHex(),
            id = id,
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

    /** Socket id for a named (container) daemon, namespaced away from primary ids. */
    private fun namedDaemonId(name: String) = "container-$name"

    /**
     * Reuse a daemon that outlived a previous app process: connect to the
     * derived socket name and authenticate. Returns null when nothing is
     * listening, the daemon speaks a different identity, or the handshake fails.
     */
    private fun attachExisting(id: String): DaemonConnection? {
        val socketName = DaemonIdentity.socketName(id)
        return try {
            val transport = UdsClient.connect(UdsEndpoint.Abstract(socketName))
            runBlocking { DaemonAuth.authenticate(transport, socketName) }
            Log.i(TAG, "Reattached to existing daemon '$id' at $socketName")
            DaemonConnection(socketName, transport, null)
        } catch (e: Exception) {
            null
        }
    }

    private fun connectWithRetry(socketName: String): Transport {
        var lastError: Exception? = null
        for (i in 0 until 50) {
            val transport = try {
                UdsClient.connect(UdsEndpoint.Abstract(socketName))
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(100)
                continue
            }
            // Any connection must pass the identity handshake before use.
            runBlocking { DaemonAuth.authenticate(transport, socketName) }
            Log.i(TAG, "Connected via abstract socket: $socketName")
            return transport
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
