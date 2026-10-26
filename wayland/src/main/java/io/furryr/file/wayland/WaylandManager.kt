package io.furryr.file.wayland

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.XkbExtractor
import nu.shel.wldroid.shims.ShimExtractor
import nu.shel.wldroid.virgl.GpuCapabilityDetector
import nu.shel.wldroid.virgl.GpuMode
import nu.shel.wldroid.virgl.VirglConfig
import nu.shel.wldroid.virgl.VirglSession
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Wayland viewer control and container wiring over the embedded wldroid
 * compositor (wlroots with an Android `ANativeWindow` backend, linked into
 * the app process through `:wayland`'s AARs — no cross-process IPC for the
 * compositor itself).
 *
 * A resident bound service owns the compositor independently of the viewer.
 * Container launch waits for its socket; the viewer only attaches a display
 * surface and forwards input. Kotlin does no window management here — wlroots
 * is the nested compositor and every
 * container client (a whole desktop environment or single apps) is just a
 * Wayland client drawing into it. The socket is shared by all containers, so
 * "one desktop at a time" is a usage constraint: clients from several
 * containers may connect, their windows all land on the single nested desktop.
 *
 * Containers get the socket the same way they get PulseAudio: the socket
 * *directory* is bound at [CONTAINER_RUNTIME_DIR] so the container view stays
 * valid across viewer restarts (only the socket inode inside changes), and
 * guests find it through `XDG_RUNTIME_DIR` + `WAYLAND_DISPLAY`. The socket
 * and client connections survive closing or recreating the viewer.
 *
 * GPU modes follow wldroid's model: on Qualcomm devices with an accessible
 * KGSL command device the existing turnip + zink pass-through keeps working
 * (containers render natively and just hand their dma-bufs to the compositor);
 * everywhere else a `virgl_test_server` (vtest) is started on the host and
 * containers reach it through the bound vtest socket plus the bundled guest
 * shims.
 */
object WaylandManager {
    private const val TAG = "WaylandManager"

    /**
     * wlroots names the first socket `wayland-0`; the host cleans stale files
     * only when starting a new compositor, never when attaching a viewer.
     */
    const val SOCKET_NAME = "wayland-0"

    /** Where the socket directory appears inside containers. */
    const val CONTAINER_RUNTIME_DIR = "/dev/wayland"

    /** Where the vtest socket directory appears inside containers. */
    const val CONTAINER_VIRGL_DIR = "/dev/virgl"

    /** Where the guest-side shim libraries appear inside containers. */
    const val CONTAINER_SHIMS_DIR = "/dev/shims"

    private const val VTEST_SOCKET_NAME = "vtest.sock"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var mode: GpuMode? = null

    private var virglSession: VirglSession? = null
    private var binding: ServiceConnection? = null
    private var hostConnection = CompletableFuture<WaylandService>()
    private var host: WaylandService? = null
    private var viewerSurface: Surface? = null
    private var viewerWidth = 0
    private var viewerHeight = 0

    /**
     * Root for everything the compositor creates on the host. The socket
     * itself lives in the `wayland-runtime` subdirectory — that name is fixed
     * by wldroid's [CompositorSession][nu.shel.wldroid.compositor.CompositorSession].
     */
    fun runtimeRoot(context: Context): File =
        File(context.cacheDir, "wayland").apply { mkdirs() }

    /** Host directory whose bind at [CONTAINER_RUNTIME_DIR] holds `wayland-0`. */
    fun socketDir(context: Context): File =
        File(runtimeRoot(context), "wayland-runtime").apply { mkdirs() }

    /** Host-side AHB registry socket (compositor ↔ virgl buffer exchange). */
    fun ahbSocketPath(context: Context): File =
        File(runtimeRoot(context), "ahb-registry.sock")

    /** Extracted xkb keymap data for the compositor's keyboard handling. */
    fun xkbDir(context: Context): File = File(context.filesDir, "xkb")

    /** Host directory holding the vtest socket, bound at [CONTAINER_VIRGL_DIR]. */
    fun virglDir(context: Context): File =
        File(context.cacheDir, "virgl").apply { mkdirs() }

    /** Guest-side shim libraries, bound at [CONTAINER_SHIMS_DIR]. */
    fun shimsDir(context: Context): File = File(context.filesDir, "wldroid-shims")

    /**
     * The GPU mode for this device. TURNIP_DIRECT keeps the existing KGSL
     * pass-through (no host server, no shims); the virgl modes run the
     * vtest server and need the guest shims.
     */
    fun gpuMode(context: Context): GpuMode {
        mode?.let { return it }
        val appContext = context.applicationContext
        val virglAvailable = File(
            appContext.applicationInfo.nativeLibraryDir,
            "libvirgl-test-server.so",
        ).exists()
        val detected = GpuCapabilityDetector(appContext)
            .detectBestGpuMode(virglAvailable = virglAvailable)
        mode = detected
        return detected
    }

    /**
     * The configuration the resident service hands to the compositor. The xkb
     * data must already be extracted ([ensurePrepared]); XWayland stays off
     * until a container-provided Xwayland wrapper is wired up.
     */
    fun compositorConfig(context: Context): CompositorConfig = CompositorConfig(
        cacheDir = runtimeRoot(context).absolutePath,
        xkbBasePath = xkbDir(context).absolutePath,
        xwaylandEnabled = false,
        gpuMode = gpuMode(context).name,
        ahbRegistrySocketPath = ahbSocketPath(context).absolutePath,
    )

    /**
     * Idempotent host-side preparation: xkb data extraction and the runtime
     * directories containers bind-mount. Safe to call from any thread.
     */
    suspend fun ensurePrepared(context: Context) {
        val appContext = context.applicationContext
        XkbExtractor(appContext).extract(xkbDir(appContext).absolutePath)
        socketDir(appContext)
        val detected = gpuMode(appContext)
        if (detected.requiresVirglServer) {
            virglDir(appContext)
            ShimExtractor(appContext).extractForGpuMode(
                shimsDir(appContext).absolutePath,
                detected.name,
            )
        }
    }

    /**
     * Bind the resident compositor and start the optional virgl server. Off the
     * main thread, wait for actual compositor readiness before launching guests;
     * an existing socket file alone could be stale after a process crash.
     */
    fun ensureHost(context: Context) {
        val appContext = context.applicationContext
        bindHost(appContext)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val connection = synchronized(lock) { hostConnection }
            connection.get(5, TimeUnit.SECONDS).awaitReady()
        }
        val detected = gpuMode(appContext)
        if (!detected.requiresVirglServer) return
        synchronized(lock) {
            if (virglSession != null) return
            val config = VirglConfig(
                virglBinaryPath = File(
                    appContext.applicationInfo.nativeLibraryDir,
                    "libvirgl-test-server.so",
                ).absolutePath,
                socketPath = File(virglDir(appContext), VTEST_SOCKET_NAME).absolutePath,
                gpuMode = detected,
            )
            virglSession = VirglSession(config).also { session ->
                scope.launch {
                    runCatching { session.start() }
                        .onFailure { error -> Log.w(TAG, "cannot start virgl server", error) }
                }
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

    internal val input get() = synchronized(lock) { host?.input }

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

    /**
     * Environment for container processes. Always exposes the Wayland socket
     * location, including while no viewer surface is attached.
     *
     * TURNIP_DIRECT deliberately adds nothing else: containers already run the
     * native turnip + zink stack against the KGSL device nodes, and their
     * dma-bufs go straight to the compositor.
     */
    fun environment(context: Context): Map<String, String> {
        val vars = mutableMapOf(
            "WAYLAND_DISPLAY" to SOCKET_NAME,
            "XDG_RUNTIME_DIR" to CONTAINER_RUNTIME_DIR,
        )
        when (gpuMode(context)) {
            GpuMode.SOFTWARE -> vars["LIBGL_ALWAYS_SOFTWARE"] = "1"
            GpuMode.VIRGL_GLES -> {
                vars["MESA_GL_VERSION_OVERRIDE"] = "3.3"
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VTEST_SOCK"] = "$CONTAINER_VIRGL_DIR/$VTEST_SOCKET_NAME"
            }
            GpuMode.VIRGL_ZINK -> {
                vars["MESA_GL_VERSION_OVERRIDE"] = "4.0"
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VTEST_SOCK"] = "$CONTAINER_VIRGL_DIR/$VTEST_SOCKET_NAME"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"
            }
            GpuMode.VENUS -> {
                vars["GALLIUM_DRIVER"] = "zink"
                vars["MESA_GL_VERSION_OVERRIDE"] = "4.0"
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VTEST_SOCK"] = "$CONTAINER_VIRGL_DIR/$VTEST_SOCKET_NAME"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/virtio_icd.json"
            }
            GpuMode.TURNIP_DIRECT, GpuMode.AUTO -> Unit
        }
        return vars
    }

    /**
     * Host→container bind mounts for the Wayland stack. Callers filter by
     * existence; the socket directory is created eagerly so the wayland bind
     * is always live, while the virgl/shim binds only exist for virgl modes.
     */
    fun bindMounts(context: Context): List<Pair<String, String>> {
        val appContext = context.applicationContext
        val mounts = mutableListOf(socketDir(appContext).absolutePath to CONTAINER_RUNTIME_DIR)
        if (gpuMode(appContext).requiresVirglServer) {
            mounts.add(
                virglDir(appContext).apply { mkdirs() }.absolutePath to CONTAINER_VIRGL_DIR,
            )
            val shims = shimsDir(appContext)
            if (shims.isDirectory) {
                mounts.add(shims.absolutePath to CONTAINER_SHIMS_DIR)
            }
        }
        return mounts
    }
}
