package io.furryr.file.core.hub

import android.content.Context
import io.furryr.file.core.DaemonManager
import java.io.File

/**
 * Hub lifecycle and the environment contract its clients need.
 *
 * App-spawned shells inherit a fresh abstract socket name for each hub start
 * and the directory containing packaged native client utilities.
 */
object Hub {
    /** Environment variable carrying the hub's abstract socket name. */
    const val SOCKET_ENV = "HUB_SOCKET"

    @Volatile private var directory: File? = null

    /** Hub socket name, or null until [start] has run. */
    val socketName: String? get() = DaemonManager.hubSocketName

    /** Directory containing the packaged native utilities. */
    val binDir: File? get() = directory

    /** App-owned discovery information, independent of the client environment. */
    fun environment(): Map<String, String> = buildMap {
        socketName?.let { put(SOCKET_ENV, it) }
    }

    /** Bring the hub up. Idempotent until the hub is stopped. */
    fun start(context: Context) {
        synchronized(this) {
            directory = File(context.applicationContext.applicationInfo.nativeLibraryDir)
            DaemonManager.startHub()
        }
    }
}
