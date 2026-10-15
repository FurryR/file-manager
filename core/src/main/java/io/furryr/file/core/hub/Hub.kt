package io.furryr.file.core.hub

import android.content.Context
import android.util.Log
import io.furryr.file.core.DaemonManager
import java.io.File

/**
 * Hub lifecycle and the environment contract its clients need.
 *
 * The hub is shared by run0 (today) and agents (later): it is started once at
 * app launch and exposes a stable, unauthenticated socket that speaks the hub's
 * own protocol ([HubServer]). This object installs the current client utility
 * (`run0`) and publishes where to find it and the hub socket through
 * environment variables that app-spawned shells inherit.
 */
object Hub {
    private const val TAG = "Hub"
    private const val CLIENT_BINARY = "run0"

    /** Environment variable carrying the hub's abstract socket name. */
    const val SOCKET_ENV = "RUN0_SOCKET"

    @Volatile private var name: String? = null
    @Volatile private var directory: File? = null

    /** Hub socket name, or null until [start] has run. */
    val socketName: String? get() = name

    /** Directory containing the installed client binary, if any. */
    val binDir: File? get() = directory

    /** Install the client binary and bring the hub up. Idempotent. */
    fun start(context: Context) {
        if (name != null) return
        synchronized(this) {
            if (name != null) return
            val app = context.applicationContext
            directory = installClient(app)
            val endpoint = DaemonManager.DefaultHubName
            runCatching { DaemonManager.startHub(endpoint) }
                .onFailure { Log.w(TAG, "hub start failed", it) }
            name = endpoint
            Log.i(TAG, "hub ready: socket=$endpoint bin=${directory?.absolutePath}")
        }
    }

    private fun installClient(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "bin").apply { mkdirs() }
        val binary = File(dir, CLIENT_BINARY)
        context.assets.open(CLIENT_BINARY).use { input ->
            binary.outputStream().use { output -> input.copyTo(output) }
        }
        binary.setExecutable(true, false)
        binary.setReadable(true, false)
        binary
    }.onFailure {
        Log.w(TAG, "client binary not installed (${it.message}); run0 will be unavailable")
    }.getOrNull()
}
