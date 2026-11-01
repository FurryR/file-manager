package io.furryr.file.wayland

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resident compositor host. The Smithay compositor runs its own thread inside
 * this process; this service only owns its lifecycle and the viewer surface, so
 * the Wayland socket and every connected client survive closing or recreating
 * the viewer activity.
 */
class WaylandService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wayland-control")
    }
    @Volatile
    private var failure: String? = null
    @Volatile
    private var started = false
    private var attachedSurface: Surface? = null
    private var attachedSize = 0 to 0

    inner class LocalBinder : Binder() {
        val service get() = this@WaylandService
    }

    override fun onCreate() {
        super.onCreate()
        executor.execute {
            try {
                WaylandManager.ensurePrepared(applicationContext)
                val metrics = resources.displayMetrics
                // Output geometry must exist before any client connects; the
                // viewer's real surface size replaces it on attach.
                val error = NativeCompositor.nativeStart(
                    WaylandManager.socketDir(applicationContext).absolutePath,
                    WaylandManager.xkbDir(applicationContext).absolutePath,
                    metrics.widthPixels,
                    metrics.heightPixels,
                    WaylandManager.displayScale(applicationContext),
                )
                if (error != null) {
                    failure = error
                    Log.e(TAG, "Cannot start Wayland host: $error")
                    return@execute
                }
                val socket = File(WaylandManager.socketDir(applicationContext), WaylandManager.SOCKET_NAME)
                if (!socket.exists()) {
                    val message = "compositor did not create $socket"
                    failure = message
                    Log.e(TAG, message)
                    return@execute
                }
                started = true
                Log.i(TAG, "Wayland host ready: $socket")
            } catch (error: Exception) {
                failure = error.message ?: error.toString()
                Log.e(TAG, "Cannot start Wayland host", error)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    internal fun awaitReady() {
        executor.submit {}.get(10, TimeUnit.SECONDS)
        check(started) { "Wayland compositor is not running: ${failure ?: "unknown reason"}" }
    }

    internal fun attach(surface: Surface, width: Int, height: Int) {
        executor.execute {
            attachedSurface = surface
            attachedSize = width to height
            NativeCompositor.nativeAttachSurface(surface, width, height)
        }
    }

    internal fun detach() {
        executor.execute {
            NativeCompositor.nativeDetachSurface()
            attachedSurface = null
        }
    }

    internal fun setScale(scale: Float) {
        executor.execute { NativeCompositor.nativeSetScale(scale) }
    }

    override fun onDestroy() {
        executor.execute {
            NativeCompositor.nativeStop()
        }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WaylandService"
    }
}
