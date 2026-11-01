package io.furryr.file.wayland

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Nested Wayland host for the app.
 *
 * The compositor is the Smithay crate in `native/wayland` (packaged as
 * `libwayland_compositor.so`), not an embedded wlroots stack: it owns the
 * Android output surface, the input mapping and the output scale, which the
 * previous prebuilt stack hardcoded to 1.
 *
 * A resident bound service keeps the compositor alive independently of the
 * viewer. Containers get the socket the same way they get PulseAudio: the
 * socket *directory* is bound at [CONTAINER_RUNTIME_DIR], so guest paths stay
 * valid across viewer restarts (only the socket inode inside changes), and
 * guests find it through `XDG_RUNTIME_DIR` + `WAYLAND_DISPLAY`.
 *
 * A wl_shm pool is an fd the *client* creates, and a memfd inode carries the
 * creating process's SELinux label. Proot containers share this process's
 * domain; a root container (chroot backend through `su`) does not, and the
 * compositor's read is denied unless the daemon redirects the pool. It needs
 * this domain to decide that ([SHM_CONTEXT_ENV]).
 */
object WaylandManager {
    private const val TAG = "WaylandManager"

    /** The compositor binds `wayland-0` inside its runtime directory. */
    const val SOCKET_NAME = "wayland-0"

    /** Where the socket directory appears inside containers. */
    const val CONTAINER_RUNTIME_DIR = "/dev/wayland"

    /**
     * Environment variable carrying this process's SELinux context to the
     * container daemon, which compares it with its own to decide whether shared
     * memory needs to be redirected into the compositor's domain.
     */
    const val SHM_CONTEXT_ENV = "WAYLAND_SHM_CONTEXT"

    private val lock = Any()

    private var binding: ServiceConnection? = null
    private var hostConnection = CompletableFuture<WaylandService>()
    private var host: WaylandService? = null
    private var viewerSurface: Surface? = null
    private var viewerWidth = 0
    private var viewerHeight = 0

    /** Root for everything the compositor creates on the host. */
    fun runtimeRoot(context: Context): File =
        File(context.cacheDir, "wayland").apply { mkdirs() }

    /** Host directory whose bind at [CONTAINER_RUNTIME_DIR] holds `wayland-0`. */
    fun socketDir(context: Context): File =
        File(runtimeRoot(context), "wayland-runtime").apply { mkdirs() }

    /** Extracted xkeyboard-config tree handed to libxkbcommon. */
    fun xkbDir(context: Context): File = File(context.filesDir, "xkb")

    /**
     * UI scale factor implied by the system display density (mdpi = 1, so a
     * 420 dpi panel yields 2.625). Fractional values stay fractional: the
     * compositor advertises them through `wp_fractional_scale_v1` and only
     * rounds up for clients that need an integer output scale.
     */
    fun displayScale(context: Context): Float {
        val dpi = context.resources.displayMetrics.densityDpi
        if (dpi <= 0) return 1f
        return dpi / 160f
    }

    /** This process's SELinux context, handed to containers as [SHM_CONTEXT_ENV]. */
    fun selinuxContext(): String? = runCatching {
        File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Host-side preparation: the runtime directories containers bind-mount and
     * the xkb data libxkbcommon needs before the compositor starts.
     */
    fun ensurePrepared(context: Context) {
        val appContext = context.applicationContext
        socketDir(appContext)
        XkbData.ensure(appContext)
    }

    /**
     * Bind the resident compositor. Off the main thread this waits until the
     * socket exists so container launches never race compositor startup; an
     * existing file alone could be stale after a process crash.
     */
    fun ensureHost(context: Context) {
        val appContext = context.applicationContext
        ensurePrepared(appContext)
        bindHost(appContext)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val connection = synchronized(lock) { hostConnection }
            try {
                connection.get(10, TimeUnit.SECONDS).awaitReady()
            } catch (error: Exception) {
                Log.w(TAG, "Wayland host did not become ready", error)
            }
        }
    }

    private fun bindHost(context: Context) {
        synchronized(lock) {
            if (binding != null) return
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val target = (service as? WaylandService.LocalBinder)?.service
                    synchronized(lock) {
                        if (binding !== this) return
                        if (target == null) {
                            hostConnection.completeExceptionally(
                                IllegalStateException("Wayland host returned an invalid binder"),
                            )
                            return
                        }
                        host = target
                        viewerSurface?.let { target.attach(it, viewerWidth, viewerHeight) }
                        hostConnection.complete(target)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(lock) {
                        host = null
                        hostConnection = CompletableFuture()
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    clearBinding("Wayland host binding died")
                    context.unbindService(this)
                    bindHost(context)
                }

                override fun onNullBinding(name: ComponentName?) {
                    clearBinding("Wayland host refused binding")
                    context.unbindService(this)
                }

                private fun clearBinding(message: String) {
                    synchronized(lock) {
                        if (binding !== this) return
                        binding = null
                        host = null
                        hostConnection.completeExceptionally(IllegalStateException(message))
                        hostConnection = CompletableFuture()
                    }
                    Log.w(TAG, message)
                }
            }
            binding = connection
            try {
                check(context.bindService(
                    Intent(context, WaylandService::class.java), connection, Context.BIND_AUTO_CREATE,
                )) { "Cannot bind Wayland host" }
            } catch (error: Exception) {
                binding = null
                throw error
            }
        }
    }

    internal fun attach(surface: Surface, width: Int, height: Int) {
        synchronized(lock) {
            viewerSurface = surface
            viewerWidth = width
            viewerHeight = height
            host?.attach(surface, width, height)
        }
    }

    internal fun detach(surface: Surface) {
        synchronized(lock) {
            if (viewerSurface !== surface) return
            viewerSurface = null
            host?.detach()
        }
    }

    /** Re-apply the device scale, e.g. after a density configuration change. */
    internal fun refreshScale(context: Context) {
        synchronized(lock) {
            host?.setScale(displayScale(context))
        }
    }

    /**
     * Environment for container processes. Always exposes the Wayland socket
     * location, including while no viewer surface is attached.
     *
     * Rendering is the guest's own business: containers run their native GL
     * stack (turnip + zink against KGSL) and hand dma-bufs to the compositor, so
     * no virgl/shim variables are needed.
     */
    fun environment(context: Context): Map<String, String> {
        val vars = mutableMapOf(
            "WAYLAND_DISPLAY" to SOCKET_NAME,
            "XDG_RUNTIME_DIR" to CONTAINER_RUNTIME_DIR,
        )
        // Lets the daemon tell whether container clients share this domain; it
        // arms its shm gate only when they do not. See the object documentation.
        selinuxContext()?.let { vars[SHM_CONTEXT_ENV] = it }
        return vars
    }

    /**
     * Host→container bind mounts for the Wayland stack. The socket directory is
     * created eagerly so the bind is always live.
     */
    fun bindMounts(context: Context): List<Pair<String, String>> {
        val appContext = context.applicationContext
        return listOf(socketDir(appContext).absolutePath to CONTAINER_RUNTIME_DIR)
    }
}
