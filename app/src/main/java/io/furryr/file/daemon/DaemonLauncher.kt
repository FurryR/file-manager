package io.furryr.file.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
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

    fun setRootEnabled(enabled: Boolean) { rootEnabled = enabled }
    fun setShellEnabled(enabled: Boolean) { shellEnabled = enabled }
    fun setSuPath(path: String) { suPath = path }

    /** Locate the native daemon binary and ensure it is executable. */
    @Synchronized fun init(context: Context) {
        appContext = context.applicationContext
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
        Log.i(TAG, "Starting root daemon: $suPath -c ${executable.absolutePath} --socket $socketName")
        val cmd = "${shellQuote(executable.absolutePath)} --socket $socketName"
        val process = ProcessBuilder(suPath, "-c", cmd)
            .directory(appContext.filesDir).redirectErrorStream(true).start()
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

    internal fun connectWithRetry(socketName: String): LocalSocket {
        val address = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
        var lastError: Exception? = null
        for (i in 0 until 50) {
            try {
                val sock = LocalSocket()
                sock.connect(address)
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
