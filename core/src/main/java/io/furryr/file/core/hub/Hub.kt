package io.furryr.file.core.hub

import android.content.Context
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import java.io.File

/**
 * Hub lifecycle and the environment contract its clients need.
 *
 * App-spawned shells inherit a fresh abstract socket name for each hub start
 * and the directory containing packaged native client utilities. The socket
 * value is `<hub-name>@<daemon-key>`; the key authenticates a client to the
 * daemon that spawned its shell.
 */
object Hub {
    /** Environment variable carrying the hub's abstract socket name and key. */
    const val SOCKET_ENV = "HUB_SOCKET"

    /** Separates the open hub address from its per-daemon credential. */
    const val KEY_SEPARATOR = '@'

    @Volatile private var directory: File? = null

    /** Hub socket name, or null until [start] has run. */
    val socketName: String? get() = DaemonManager.hubSocketName

    /** Directory containing the packaged native utilities. */
    val binDir: File? get() = directory

    /** App-owned discovery information, independent of the client environment. */
    fun environment(target: DaemonTarget): Map<String, String> = buildMap {
        val name = socketName ?: return@buildMap
        val key = DaemonManager.hubKey(target)
        put(SOCKET_ENV, "$name$KEY_SEPARATOR$key")
    }

    /** Bring the hub up. Idempotent until the hub is stopped. */
    fun start(context: Context) {
        synchronized(this) {
            directory = File(context.applicationContext.applicationInfo.nativeLibraryDir)
            DaemonManager.startHub()
        }
    }
}
