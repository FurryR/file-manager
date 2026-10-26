package io.furryr.file.wayland

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.Os
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.runBlocking
import nu.shel.wldroid.compositor.CompositorSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Resident, in-process compositor host. A temporary offscreen output lets
 * wlroots initialize before any viewer exists. Pausing only detaches that
 * output: the event loop, Wayland socket, globals and clients remain alive.
 * The application-context binding outlives every viewer activity.
 */
class WaylandService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wayland-control")
    }
    private lateinit var startup: Future<*>
    private var bootstrapOutput: ImageReader? = null
    private var attachedSurface: Surface? = null

    @Volatile
    private var session: CompositorSession? = null

    internal val input get() = session?.input

    inner class LocalBinder : Binder() {
        val service get() = this@WaylandService
    }

    override fun onCreate() {
        super.onCreate()
        startup = executor.submit {
            try {
                runBlocking { WaylandManager.ensurePrepared(applicationContext) }
                val output = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
                bootstrapOutput = output
                output.setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.close()
                }, Handler(Looper.getMainLooper()))
                val compositor = CompositorSession(WaylandManager.compositorConfig(this))
                // Retain even on partial startup failure so onDestroy can stop JNI.
                session = compositor
                compositor.start(output.surface)
                val socket = File(WaylandManager.socketDir(this), WaylandManager.SOCKET_NAME)
                check(socket.exists()) { "Wayland compositor did not create $socket" }
                // Chroot clients may use a non-root UID, unlike proot clients.
                Os.chmod(socket.parent!!, 0x1ed) // 0755; clients cannot replace the socket.
                Os.chmod(socket.absolutePath, 0x1b6) // 0666
                compositor.pause()
                Log.i(TAG, "Wayland host ready: $socket")
            } catch (error: Exception) {
                Log.e(TAG, "Cannot start Wayland host", error)
                throw error
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    internal fun awaitReady() {
        startup.get(5, TimeUnit.SECONDS)
    }

    internal fun attach(surface: Surface, width: Int, height: Int) {
        control { compositor ->
            if (!surface.isValid) return@control
            if (attachedSurface !== surface) {
                compositor.pause()
                compositor.resume(surface)
                attachedSurface = surface
            }
            if (width > 0 && height > 0) compositor.resizeOutput(width, height)
        }
    }

    internal fun detach() {
        control { compositor ->
            compositor.pause()
            attachedSurface = null
        }
    }

    private fun control(action: (CompositorSession) -> Unit) {
        executor.execute {
            runCatching {
                startup.get()
                session?.let(action)
            }.onFailure { Log.w(TAG, "Cannot update Wayland output", it) }
        }
    }

    override fun onDestroy() {
        executor.execute {
            try {
                session?.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Cannot stop Wayland host", error)
            } finally {
                session = null
                bootstrapOutput?.close()
                bootstrapOutput = null
            }
        }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WaylandService"
    }
}
