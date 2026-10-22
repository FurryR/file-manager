package io.furryr.file.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.furryr.file.daemon.file.FileService
import io.furryr.file.daemon.ipc.DaemonConnection
import io.furryr.file.daemon.process.ExecService
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
    /** The host daemon (elevated via `su`/Shizuku when available, otherwise the app daemon). */
    data object Host : DaemonTarget

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
    /** Target id used with this launch's public key to derive its socket name. */
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
    private val startingNamed = mutableSetOf<String>()
    private val namedChangeListeners = mutableSetOf<() -> Unit>()

    /** Lock-free snapshot for the UI/notification thread. */
    @Volatile private var namedCountSnapshot = 0

    @Volatile var hubSocketName: String? = null
        private set

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

    /** Install the endpoint registry for external clients. */
    fun setRegistry(registry: DaemonRegistry) { this.registry = registry }

    /** Live endpoints an external client could attach to. */
    fun endpoints(): List<DaemonEndpoint> = registry.endpoints()

    /** Live daemon endpoints with their actual sockets, ready for hub attach. */
    fun liveEndpoints(): List<DaemonEndpoint> = synchronized(lock) {
        val list = ArrayList<DaemonEndpoint>()
        primary?.takeIf { it.isAlive() }?.let {
            list.add(DaemonEndpoint("host", it.socketName, rootActive))
        }
        app?.takeIf { it.isAlive() }?.let {
            list.add(DaemonEndpoint("app", it.socketName, false))
        }
        named.forEach { (name, connection) ->
            if (connection.isAlive()) {
                list.add(DaemonEndpoint("container-$name", connection.socketName, false))
            }
        }
        list
    }

    /** Stable hub id for a target: `host`, `app`, or `container-<name>`. */
    fun hubId(target: DaemonTarget): String = when (target) {
        DaemonTarget.Host -> "host"
        DaemonTarget.App -> "app"
        is DaemonTarget.Named -> "container-${target.name}"
    }

    /** Resolve the per-daemon key to a live target, if any. */
    fun targetForKey(key: String): DaemonTarget? = synchronized(lock) {
        primary?.takeIf { it.isAlive() && it.key == key }?.let { return DaemonTarget.Host }
        app?.takeIf { it.isAlive() && it.key == key }?.let { return DaemonTarget.App }
        named.entries.firstOrNull { it.value.isAlive() && it.value.key == key }?.let {
            return DaemonTarget.Named(it.key)
        }
        null
    }

    /** Key of the live daemon for [target], starting it if necessary. */
    fun hubKey(target: DaemonTarget): String = connection(target).key

    /** Publish the hub socket name; the :hub module owns the server. */
    fun attachHub(socketName: String) {
        hubSocketName = socketName
        Log.i(TAG, "Hub listening at $socketName")
    }

    /** Clear the published hub socket name. */
    fun detachHub() {
        hubSocketName = null
    }

    // ── Privilege queries ─────────────────────────────────────────────

    /** Config says we may start a root daemon. */
    fun canStartRootDaemon(): Boolean = rootEnabled && suPath.isNotBlank()

    /** Root was verified by connecting a root daemon and is still allowed. */
    fun isRootAvailable(): Boolean = rootEnabled && rootActive

    fun isShizukuAvailable(): Boolean =
        try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Exception) { false }

    fun hasShizukuPermission(): Boolean = try {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /** Start (or reuse) the primary daemon and retain its connection. */
    fun warmUp(): Result<Unit> = runCatching { connection(DaemonTarget.Host) }

    /** Start (or reuse) a named daemon (e.g. a container) without using it. */
    fun warmNamed(name: String): Result<Unit> = runCatching { connection(DaemonTarget.Named(name)) }

    /** Kill every daemon (process tree included) and forget them. */
    fun reset() {
        val hadNamed = synchronized(lock) {
            val had = named.isNotEmpty()
            (listOfNotNull(primary, app) + named.values).distinct().forEach { it.shutdown() }
            primary = null
            app = null
            named.clear()
            updateNamedSnapshot()
            rootActive = false
            _status.value = DaemonStatus.Idle
            had
        }
        if (hadNamed) notifyNamedChanged()
    }

    /** Kill a single named daemon; the next access starts a fresh one. */
    fun closeNamedConnection(name: String) {
        val removed = synchronized(lock) {
            named.remove(name)?.also { it.shutdown() }.also { updateNamedSnapshot() }
        }
        if (removed != null) notifyNamedChanged()
    }

    /** Kill every named daemon. */
    fun closeNamedConnections() {
        val hadNamed = synchronized(lock) {
            val had = named.isNotEmpty()
            named.values.forEach { it.shutdown() }
            named.clear()
            updateNamedSnapshot()
            had
        }
        if (hadNamed) notifyNamedChanged()
    }

    fun isNamedConnected(name: String): Boolean = synchronized(lock) {
        named[name]?.isAlive() == true
    }

    /** Number of live named daemons (running or starting containers). */
    fun connectedNamedCount(): Int = namedCountSnapshot

    private fun updateNamedSnapshot() {
        namedCountSnapshot =
            (named.filterValues { it.isAlive() }.keys + startingNamed).size
    }

    /** Listeners notified whenever the live named daemon set changes. */
    fun addNamedChangeListener(listener: () -> Unit) {
        synchronized(lock) { namedChangeListeners.add(listener) }
    }

    fun removeNamedChangeListener(listener: () -> Unit) {
        synchronized(lock) { namedChangeListeners.remove(listener) }
    }

    private fun notifyNamedChanged() {
        val listeners = synchronized(lock) { namedChangeListeners.toList() }
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Ask an already-connected daemon to shut down gracefully. Does not start
     * one. Any client may send this; the daemon exits on the first one.
     */
    fun exit(target: DaemonTarget = DaemonTarget.Host) = synchronized(lock) {
        when (target) {
            DaemonTarget.Host -> primary?.exit()
            DaemonTarget.App -> app?.exit()
            is DaemonTarget.Named -> named[target.name]?.exit()
        }
    }

    // ── Service facades ───────────────────────────────────────────────

    fun fileService(target: DaemonTarget = DaemonTarget.Host): FileService =
        FileService { connection(target) }

    fun execService(target: DaemonTarget = DaemonTarget.Host): ExecService =
        ExecService { connection(target) }

    // ── Internal ──────────────────────────────────────────────────────

    fun connection(target: DaemonTarget): DaemonConnection {
        var namedAdded = false
        val result = synchronized(lock) {
            when (target) {
                DaemonTarget.Host -> primary?.takeIf { it.isAlive() } ?: startPrimaryLocked()
                DaemonTarget.App -> {
                    if (!rootEnabled && !shellEnabled) {
                        primary?.takeIf { it.isAlive() } ?: startPrimaryLocked()
                    } else {
                        startAppDaemon()
                    }
                }
                is DaemonTarget.Named -> named[target.name]?.takeIf { it.isAlive() } ?: run {
                    startingNamed.add(target.name)
                    updateNamedSnapshot()
                    try {
                        named.remove(target.name)?.close()
                        startNamedDaemon(target.name).also {
                            named[target.name] = it
                            namedAdded = true
                        }
                    } finally {
                        startingNamed.remove(target.name)
                        updateNamedSnapshot()
                    }
                }
            }
        }
        if (namedAdded) notifyNamedChanged()
        return result
    }

    private fun startPrimaryLocked(): DaemonConnection {
        primary?.let { if (it.isAlive()) return it; it.close(); primary = null }
        rootActive = false
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
        // Prefer root, then Shizuku, then the cached app-UID daemon.
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
        app?.let { if (it.isAlive()) return it; it.close(); app = null }
        val identity = DaemonIdentity(AppId)
        val executable = getExecutable()
        Log.i(TAG, "Starting daemon: ${executable.absolutePath} --id ${identity.id}")
        val process = ProcessBuilder(
            executable.absolutePath, "--key", identity.publicKeyHex(), "--id", identity.id,
        ).directory(appContext.filesDir).redirectErrorStream(true)
            .apply { environment()["PATH"] = daemonPath() }.start()
        return connectStarted(identity, process).also { app = it }
    }

    private fun startRootDaemon(): DaemonConnection {
        val identity = DaemonIdentity(PrivilegedId)
        val executable = getExecutable()
        val cmd = "export PATH=${shellQuote(daemonPath())}; exec ${shellQuote(executable.absolutePath)} --key ${shellQuote(identity.publicKeyHex())} --id ${shellQuote(identity.id)}"
        Log.i(TAG, "Starting root daemon: $suPath -c $cmd")
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        val connection = connectStarted(identity, process)
        rootActive = true
        return connection
    }

    private fun startShizukuDaemon(): DaemonConnection {
        val identity = DaemonIdentity(PrivilegedId)
        val executable = getExecutable()
        Log.i(TAG, "Starting daemon via Shizuku: ${executable.absolutePath} --id ${identity.id}")
        val process = shizukuNewProcess(
            arrayOf(executable.absolutePath, "--key", identity.publicKeyHex(), "--id", identity.id),
            arrayOf("PATH=${daemonPath()}"),
            appContext.filesDir.absolutePath)
        return connectStarted(identity, process)
    }

    private fun startNamedDaemon(name: String): DaemonConnection {
        // Prefix the id so container names can never collide with the reserved
        // primary ids (privileged/app).
        val identity = DaemonIdentity("container-$name")
        val activeLauncher = launcher
            ?: throw IllegalStateException("No DaemonLauncher registered for '$name'")
        val request = DaemonLaunchRequest(
            name = name,
            daemonExecutable = getExecutable().absolutePath,
            socketName = identity.socketName,
            publicKey = identity.publicKeyHex(),
            id = identity.id,
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
        return connectStarted(identity, process) {
            synchronized(lock) { updateNamedSnapshot() }
            notifyNamedChanged()
        }
    }

    private fun daemonPath(): String =
        "/system/bin:/system/xbin:/vendor/bin:${appContext.applicationInfo.nativeLibraryDir}"

    private fun getExecutable(): File = executableFile
        ?: throw IllegalStateException("Daemon binary not installed. Call init() first.")

    private fun connectStarted(
        identity: DaemonIdentity,
        process: Process,
        onClosed: (() -> Unit)? = null,
    ): DaemonConnection {
        return try {
            DaemonConnection(identity, connectWithRetry(identity), process, onClosed)
        } catch (e: Exception) {
            runCatching { process.destroy() }
                .onFailure { Log.w(TAG, "Failed to stop daemon after startup failure", it) }
            throw e
        }
    }

    private fun connectWithRetry(identity: DaemonIdentity): Transport {
        val socketName = identity.socketName
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
            try {
                runBlocking { DaemonAuth.authenticate(transport, identity) }
            } catch (e: Exception) {
                transport.close()
                throw e
            }
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
