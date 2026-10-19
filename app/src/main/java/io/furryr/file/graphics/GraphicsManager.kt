package io.furryr.file.graphics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.system.Os
import androidx.core.app.NotificationCompat
import io.furryr.file.R
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.HubActions
import io.furryr.file.core.shellQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

data class GraphicsState(val running: Boolean = false, val starting: Boolean = false, val error: String? = null)

object GraphicsManager {
    internal val mutableState = MutableStateFlow(GraphicsState())
    val state = mutableState.asStateFlow()
    private const val UNIT = "file-manager-virgl.service"
    private const val SOCKET = "/run/file-manager/virgl.sock"
    private fun preferences(context: Context) = context.getSharedPreferences("graphics", Context.MODE_PRIVATE)
    fun isEnabled(context: Context, container: String) = preferences(context).getBoolean(container, false)
    fun containerEnvironment(context: Context, container: String): Map<String, String> =
        if (!isEnabled(context, container)) emptyMap() else mapOf(
            "GALLIUM_DRIVER" to "virpipe", "VTEST_SOCKET_NAME" to SOCKET, "LIBGL_ALWAYS_SOFTWARE" to "1")
    fun ensureHostIfEnabled(context: Context, container: String) {
        if (isEnabled(context, container)) startHost(context)
    }
    @Synchronized private fun startHost(context: Context) {
        if (state.value.running || state.value.starting) return
        mutableState.value = GraphicsState(starting = true)
        try { context.startForegroundService(Intent(context, GraphicsForegroundService::class.java)) }
        catch (e: Exception) { mutableState.value = GraphicsState(error = e.message); throw e }
    }
    suspend fun setEnabled(context: Context, container: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val services = DaemonManager.containerServices(container)
        if (enabled) {
            startHost(context)
            val host = withTimeout(20_000) { state.first { !it.starting } }
            check(host.running) { host.error ?: "Renderer did not start" }
            val binary = File(context.applicationInfo.nativeLibraryDir, "virgl_bridge").absolutePath
            val unit = """
                [Unit]
                Description=File Manager VirGL transport
                [Service]
                Type=simple
                ExecStart=${shellQuote(binary)} --socket $SOCKET
                Restart=on-failure
                RestartSec=2
            """.trimIndent()
            services.control("write-unit", listOf(UNIT), unit).getOrThrow()
            services.control("start", listOf(UNIT)).getOrThrow().also { check(it.exitCode == 0) { it.text } }
            services.control("enable", listOf(UNIT)).getOrThrow()
        } else {
            services.control("disable", listOf(UNIT)).getOrThrow()
            services.control("stop", listOf(UNIT)).getOrThrow()
        }
        preferences(context).edit().putBoolean(container, enabled).apply()
        io.furryr.file.agent.ContainerManager.getContainer(context, container)?.let {
            io.furryr.file.agent.ProotRunner.refreshHubProfile(context, it)
        }
        if (!enabled && preferences(context).all.values.none { it == true }) {
            context.stopService(Intent(context, GraphicsForegroundService::class.java))
        }
    }
}

/** Host renderer owns no container privileges and exposes only its private vtest socket. */
class GraphicsForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    @Volatile private var destroyed = false
    private var process: Process? = null
    private var jobStarted = false
    private lateinit var socket: File
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        socket = File(cacheDir, "virgl/vtest.sock").apply { parentFile!!.mkdirs() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("virgl", getString(R.string.tools_graphics), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 4203, Intent(this, GraphicsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4203, Intent(this, GraphicsForegroundService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(4203, NotificationCompat.Builder(this, "virgl").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.tools_graphics)).setContentText(getString(R.string.graphics_host_notification))
            .setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.tools_stop), stop).build())
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (jobStarted) return START_NOT_STICKY
        jobStarted = true; GraphicsManager.mutableState.value = GraphicsState(starting = true)
        scope.launch {
            var ownedProcess: Process? = null
            try {
                val renderer = File(applicationInfo.nativeLibraryDir, "libvirglserver.so")
                check(renderer.isFile) { getString(R.string.graphics_binary_missing) }
                if (socket.exists()) check(socket.delete()) { "Cannot clear stale renderer socket" }
                val log = File(socket.parentFile, "renderer.log")
                val child = ProcessBuilder(renderer.absolutePath, "--use-gles", "--use-egl-surfaceless", "--multi-clients",
                    "--socket-path", socket.absolutePath).redirectErrorStream(true).redirectOutput(log)
                    .apply { environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir }.start()
                ownedProcess = child
                synchronized(lifecycleLock) {
                    check(!destroyed) { "Renderer startup was cancelled" }
                    process = child
                }
                withTimeout(15_000) {
                    while (!socket.exists()) {
                        check(child.isAlive) { "Renderer exited: ${tail(log)}" }
                        delay(50)
                    }
                }
                synchronized(lifecycleLock) {
                    check(!destroyed) { "Renderer startup was cancelled" }
                    HubActions.graphicsConnector = {
                        check(child.isAlive) { "VirGL renderer stopped" }
                        LocalSocket().use { connection ->
                            connection.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                            Os.dup(connection.fileDescriptor)
                        }
                    }
                    GraphicsManager.mutableState.value = GraphicsState(running = true)
                }
                while (child.isAlive) delay(500)
                error("Renderer exited (${child.exitValue()}): ${tail(log)}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                synchronized(lifecycleLock) { if (!destroyed) GraphicsManager.mutableState.value = GraphicsState(error = e.message) }
                if (!destroyed) stopSelf()
            } finally {
                ownedProcess?.let(::terminate)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        val owned = synchronized(lifecycleLock) {
            destroyed = true
            HubActions.graphicsConnector = null
            val child = process; process = null
            GraphicsManager.mutableState.value = GraphicsManager.state.value.copy(running = false, starting = false)
            child
        }
        scope.cancel()
        owned?.let(::terminate)
        if (socket.exists() && !socket.delete()) android.util.Log.w("VirGL", "Renderer socket remains after shutdown")
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    private fun terminate(child: Process) {
        child.destroy()
        try { if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly() }
        catch (e: InterruptedException) { child.destroyForcibly(); Thread.currentThread().interrupt() }
    }
    private fun tail(log: File): String {
        if (!log.isFile) return ""
        return java.io.RandomAccessFile(log, "r").use { file ->
            file.seek((file.length() - 4096).coerceAtLeast(0))
            val bytes = ByteArray(minOf(4096L, file.length()).toInt()); file.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }
}
