package io.furryr.file.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import android.util.Log
import io.furryr.file.core.SystemAccount
import io.furryr.file.core.SystemAccounts
import io.furryr.file.daemon.file.FileService
import io.furryr.file.daemon.ipc.DaemonConnection
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.daemon.process.ExecService
import io.furryr.file.uds.Transport
import io.furryr.file.uds.UdsClient
import io.furryr.file.uds.UdsEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Which daemon a caller wants to talk to. */
sealed interface DaemonTarget {
    /** The one host daemon (root via `su` when available, otherwise the app UID). */
    data object Host : DaemonTarget

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
 * SPI for launching a *named* daemon. Core owns the host (root, Shizuku or app
 * UID) launch strategies; hosts register a launcher for names they own
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

    /** Packaged library name → command name exposed through [binDir]. */
    private val NativeUtilities = mapOf(
        "run0" to "librun0.so",
        "here" to "libhere.so",
        "agent" to "libagent.so",
    )

    /** Adb shell can only search `/data/local/tmp`, not app-private storage. */
    private const val ShellUtilityDir = "/data/local/tmp/io.furryr.file-bin"

    /** Target id the primary daemon socket name is derived from (and the
     *  automatic cgroup name). One launch strategy: `su` when root is
     *  available, otherwise the app UID. */
    private const val HostId = "host"

    /**
     * Internal app-UID daemon for app-private files. It is deliberately not a
     * [DaemonTarget] and never appears in the hub target catalog: the provider
     * uses it only when the host daemon's identity (root or Shizuku shell)
     * cannot reach `filesDir`/`cacheDir`.
     */
    private const val AppId = "app"

    /** Environment variable carrying the hub socket name and daemon key. */
    const val HUB_SOCKET_ENV = "HUB_SOCKET"

    /** Environment variable tying a nested hub client to its terminal. */
    const val HUB_TERMINAL_ENV = "HUB_TERMINAL"

    /** Separator between the hub address and its per-daemon key. */
    const val HUB_KEY_SEPARATOR = '@'

    private lateinit var appContext: Context
    private var executableFile: File? = null

    private val lock = Any()
    @Volatile private var primary: DaemonConnection? = null
    /** App-UID daemon for app-private storage; never exposed as a hub target. */
    private var appDaemon: DaemonConnection? = null
    private val named = mutableMapOf<String, DaemonConnection>()
    private val startingNamed = mutableSetOf<String>()
    private val namedChangeListeners = mutableSetOf<() -> Unit>()

    /** Lock-free snapshot for the UI/notification thread. */
    @Volatile private var namedCountSnapshot = 0

    @Volatile var hubSocketName: String? = null
        private set

    @Volatile private var launcher: DaemonLauncher? = null

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

    /** Directory holding the packaged native utilities (`run0`, `here`, `agent`). */
    val nativeLibraryDir: String get() = appContext.applicationInfo.nativeLibraryDir

    /**
     * Directory of original-name symlinks into [nativeLibraryDir]. Android only
     * extracts `lib*.so`, so `run0`, `here` and `agent` are recreated here on
     * every process start.
     */
    val binDir: String get() = binDirectory.absolutePath

    private val binDirectory: File by lazy { createBinDirectory() }

    private fun createBinDirectory(): File {
        val dir = File(appContext.filesDir, "bin")
        dir.mkdirs()
        for ((name, target) in NativeUtilities) {
            val link = File(dir, name)
            val binary = File(nativeLibraryDir, target)
            if (runCatching { Os.readlink(link.absolutePath) }.getOrNull() == binary.absolutePath) continue
            runCatching {
                link.delete()
                Os.symlink(binary.absolutePath, link.absolutePath)
            }.onFailure { Log.w(TAG, "Failed to link native utility $name", it) }
        }
        return dir
    }

    /** Set while the primary host daemon runs as the adb shell UID (Shizuku). */
    @Volatile private var primaryShellUid = false

    @Volatile private var shellBinReady = false

    private val shellScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Directory a spawned host shell can search for `run0`/`here`/`agent`.
     * The app-private [binDir] is unreachable to the adb shell UID, so a
     * Shizuku daemon maintains a second original-name link set in
     * [ShellUtilityDir]; root and app-UID daemons keep using [binDir].
     */
    suspend fun utilityDir(): String {
        withContext(Dispatchers.IO) { connection(DaemonTarget.Host) }
        if (!primaryShellUid) return binDir
        return ensureShellBinDir()
    }

    private suspend fun ensureShellBinDir(): String {
        if (shellBinReady) return ShellUtilityDir
        val dir = File(ShellUtilityDir)
        val script = buildString {
            append("mkdir -p ").append(shellQuote(dir.absolutePath))
            for ((name, target) in NativeUtilities) {
                append(" && ln -sf ")
                    .append(shellQuote(File(nativeLibraryDir, target).absolutePath))
                    .append(' ')
                    .append(shellQuote(File(dir, name).absolutePath))
            }
        }
        val channel = execService(DaemonTarget.Host).spawnPipes(
            CommandSpec(command = "/system/bin/sh", args = listOf("-c", script)),
        ).getOrElse {
            Log.w(TAG, "Cannot create shell utility directory", it)
            return binDir
        }
        val exit = runCatching { channel.waitFor().getOrThrow() }.getOrNull()
        runCatching { channel.closeProcess() }
        if (exit != 0) {
            Log.w(TAG, "Shell utility directory setup exited with $exit")
            return binDir
        }
        shellBinReady = true
        return ShellUtilityDir
    }

    // ── Configuration ─────────────────────────────────────────────────

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        // Lift the hidden-API restriction (needed for android.system.Os.recvmsg)
        // before any daemon I/O starts.
        HiddenApi.ensure()
        binDir
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

    /** Live daemon endpoints with their actual sockets, ready for hub attach. */
    fun liveEndpoints(): List<DaemonEndpoint> = synchronized(lock) {
        val list = ArrayList<DaemonEndpoint>()
        primary?.takeIf { it.isAlive() }?.let {
            list.add(DaemonEndpoint(HOST_ID, it.socketName, rootActive))
        }
        named.forEach { (name, connection) ->
            if (connection.isAlive()) {
                list.add(DaemonEndpoint("${CONTAINER_PREFIX}$name", connection.socketName, false))
            }
        }
        list
    }

    /** Hub id prefix for a named (container) daemon. */
    const val CONTAINER_PREFIX = "container-"

    /** Hub id of the one host daemon. */
    const val HOST_ID = "host"

    /** One execution target as every consumer (agent, hub, prompt) sees it. */
    data class TargetInfo(
        val id: String,
        val label: String,
        val root: String,
        val container: Boolean,
        val running: Boolean,
        val elevated: Boolean,
    )

    /**
     * The one target catalog: host, live daemons and known containers,
     * ordered the same way in the agent prompt, the tools and the hub.
     */
    fun targetCatalog(): List<TargetInfo> {
        val live = liveEndpoints().associateBy { it.name }
        val names = LinkedHashSet<String>()
        names.add(HOST_ID)
        names.addAll(live.keys)
        runCatching { HubActions.containerIds?.invoke().orEmpty() }
            .getOrDefault(emptyList())
            .let(names::addAll)
        val ordered = names.filter { it == HOST_ID } +
            names.filterNot { it == HOST_ID }.sorted()
        return ordered.map { name ->
            val endpoint = live[name]
            TargetInfo(
                id = name,
                label = hubDisplayName(name),
                root = targetRoot(name),
                container = isContainerHubId(name),
                running = endpoint != null,
                elevated = endpoint?.elevated == true,
            )
        }
    }

    /** The VFS root that addresses [id]'s filesystem. */
    fun targetRoot(id: String): String = when {
        id == HOST_ID -> "file:///"
        isContainerHubId(id) -> io.furryr.file.core.Path.of(
            "container",
            host = hubDisplayName(id),
        ).toString()
        else -> ""
    }

    /** Resolve a hub id back to its daemon target. */
    fun targetForId(id: String): DaemonTarget = when (id) {
        HOST_ID -> DaemonTarget.Host
        else -> {
            require(isContainerHubId(id)) { "invalid target id '$id'" }
            DaemonTarget.Named(hubDisplayName(id))
        }
    }

    /** Display name of a hub id: `container-foo` becomes `foo`. */
    fun hubDisplayName(id: String): String = id.removePrefix(CONTAINER_PREFIX)

    fun isContainerHubId(id: String): Boolean = id.startsWith(CONTAINER_PREFIX)

    /** Stable hub id for a target: `host` or `container-<name>`. */
    fun hubId(target: DaemonTarget): String = when (target) {
        DaemonTarget.Host -> HOST_ID
        is DaemonTarget.Named -> "$CONTAINER_PREFIX${target.name}"
    }

    /** Resolve the per-daemon key to a live target, if any. */
    fun targetForKey(key: String): DaemonTarget? = synchronized(lock) {
        primary?.takeIf { it.isAlive() && it.key == key }?.let { return DaemonTarget.Host }
        named.entries.firstOrNull { it.value.isAlive() && it.value.key == key }?.let {
            return DaemonTarget.Named(it.key)
        }
        null
    }

    /** Key of the live daemon for [target], starting it if necessary. */
    fun hubKey(target: DaemonTarget): String = connection(target).key

    /**
     * Environment a spawned shell needs to reach the hub on behalf of
     * [target]. Returns an empty map until the hub is attached; [terminalId]
     * identifies the shell for nested `run0` requests.
     */
    fun hubEnvironment(target: DaemonTarget, terminalId: String? = null): Map<String, String> {
        val name = hubSocketName ?: return emptyMap()
        val key = hubKey(target)
        return buildMap {
            put(HUB_SOCKET_ENV, "$name$HUB_KEY_SEPARATOR$key")
            terminalId?.takeIf { it.isNotEmpty() }?.let { put(HUB_TERMINAL_ENV, it) }
        }
    }

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
            (listOfNotNull(primary, appDaemon) + named.values).distinct().forEach { it.shutdown() }
            primary = null
            appDaemon = null
            named.clear()
            updateNamedSnapshot()
            rootActive = false
            primaryShellUid = false
            shellBinReady = false
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
            is DaemonTarget.Named -> named[target.name]?.exit()
        }
    }

    // ── Service facades ───────────────────────────────────────────────

    fun fileService(target: DaemonTarget = DaemonTarget.Host): FileService =
        FileService({ connection(target) }, appAccounts = target == DaemonTarget.Host)

    /**
     * File service for app-private storage. A root or Shizuku host daemon may
     * not be able to read `filesDir`; this connection always runs as the app
     * UID and is intentionally absent from [targetCatalog] and the hub.
     */
    fun appFileService(): FileService = FileService({ appConnection() }, appAccounts = true)

    /**
     * Android's passwd files do not list application accounts, so the daemon
     * answers with root/system only. Installed packages are the source of the
     * app uids, and their labels/package names let the picker show the app.
     */
    internal fun mergeInstalledAccounts(accounts: SystemAccounts): SystemAccounts {
        if (!::appContext.isInitialized) return accounts
        val manager = appContext.packageManager
        val users = mutableListOf<SystemAccount>()
        val groups = mutableListOf<SystemAccount>()
        val seen = mutableSetOf<Int>()
        for (info in manager.getInstalledPackages(0)) {
            val app = info.applicationInfo ?: continue
            val uid = app.uid
            if (!seen.add(uid)) continue
            val label = runCatching {
                manager.getApplicationLabel(app).toString()
            }.getOrDefault("")
            users += SystemAccount(uid, info.packageName, label, info.packageName)
            groups += SystemAccount(uid, info.packageName, label, info.packageName)
        }
        fun merge(base: List<SystemAccount>, extra: List<SystemAccount>): List<SystemAccount> {
            val ids = base.mapTo(mutableSetOf()) { it.id }
            return base + extra.filter { ids.add(it.id) }
        }
        return SystemAccounts(
            users = merge(accounts.users, users),
            groups = merge(accounts.groups, groups),
        )
    }

    /** Exec facade for app-private storage; same internal app-UID daemon. */
    fun appExecService(): ExecService = ExecService(resolve = { appConnection() })

    /**
     * Exec facade for [target]. Children inherit the daemon's credentials, so
     * a host shell started by a root daemon is a root shell and can enter any
     * directory the daemon itself can reach.
     */
    fun execService(target: DaemonTarget = DaemonTarget.Host): ExecService =
        ExecService(resolve = { connection(target) })

    // ── Internal ──────────────────────────────────────────────────────

    /**
     * Start or reuse the app-UID daemon. It has its own identity and socket, so
     * it coexists with a root or Shizuku host daemon without sharing a target
     * id or a process tree.
     */
    private fun appConnection(): DaemonConnection = synchronized(lock) {
        appDaemon?.takeIf { it.isAlive() } ?: run {
            appDaemon?.close()
            appDaemon = null
            startAppDaemon().also { appDaemon = it }
        }
    }

    fun connection(target: DaemonTarget): DaemonConnection {
        var namedAdded = false
        val result = synchronized(lock) {
            when (target) {
                DaemonTarget.Host -> primary?.takeIf { it.isAlive() } ?: startPrimaryLocked()
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
        shellBinReady = false
        _status.value = DaemonStatus.Connecting
        return try {
            startPrimary().also {
                primary = it
                _generation++
                _status.value = DaemonStatus.Connected
                if (primaryShellUid) shellScope.launch { ensureShellBinDir() }
            }
        } catch (e: Exception) {
            _status.value = DaemonStatus.Failed(e)
            throw e
        }
    }

    private fun startPrimary(): DaemonConnection {
        // Root can both serve privileged file operations and drop a terminal to
        // the app's uid/domain. Shizuku (shell UID) is the middle fallback; a
        // shell daemon can only serve terminals when it runs as root.
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
        return startDirectDaemon()
    }

    /**
     * The host daemon under the app UID (no `su`). Terminals already run as the
     * app; file operations keep the app's own credentials.
     */
    private fun startDirectDaemon(): DaemonConnection {
        primary?.let { if (it.isAlive()) return it; it.close(); primary = null }
        return startDirectDaemon(HostId)
    }

    private fun startAppDaemon(): DaemonConnection = startDirectDaemon(AppId)

    private fun startDirectDaemon(id: String): DaemonConnection {
        val identity = DaemonIdentity(id)
        val executable = getExecutable()
        if (id == HostId) primaryShellUid = false
        Log.i(TAG, "Starting daemon: ${executable.absolutePath} --id ${identity.id}")
        val process = ProcessBuilder(
            executable.absolutePath, "--key", identity.publicKeyHex(), "--id", identity.id,
        ).directory(appContext.filesDir).redirectErrorStream(true)
            .apply { environment()["PATH"] = daemonPath() }.start()
        return connectStarted(identity, process)
    }

    private fun startRootDaemon(): DaemonConnection {
        val identity = DaemonIdentity(HostId)
        val executable = getExecutable()
        primaryShellUid = false
        val cmd = "export PATH=${shellQuote(daemonPath())}; exec ${shellQuote(executable.absolutePath)} --key ${shellQuote(identity.publicKeyHex())} --id ${shellQuote(identity.id)}"
        Log.i(TAG, "Starting root daemon: $suPath -c $cmd")
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
        val connection = connectStarted(identity, process)
        rootActive = true
        return connection
    }

    private fun startShizukuDaemon(): DaemonConnection {
        val identity = DaemonIdentity(HostId)
        val executable = getExecutable()
        primaryShellUid = true
        Log.i(TAG, "Starting daemon via Shizuku: ${executable.absolutePath} --id ${identity.id}")
        val process = shizukuNewProcess(
            arrayOf(executable.absolutePath, "--key", identity.publicKeyHex(), "--id", identity.id),
            arrayOf("PATH=${shellPath()}"),
            appContext.filesDir.absolutePath)
        return connectStarted(identity, process)
    }

    private fun startNamedDaemon(name: String): DaemonConnection {
        // Prefix the id so container names can never collide with the reserved
        // host id.
        val identity = DaemonIdentity("$CONTAINER_PREFIX$name")
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
        "/system/bin:/system/xbin:/vendor/bin:${binDir}"

    /** Shizuku children run as the adb shell UID, which cannot read [binDir]. */
    private fun shellPath(): String =
        "/system/bin:/system/xbin:/vendor/bin:$ShellUtilityDir"

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
