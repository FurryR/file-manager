package io.furryr.file.virgl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File

/**
 * VirGL host control and container wiring.
 *
 * VirGL is always enabled. The `:virgl` process is a resident listener:
 * [ensureResident] binds it early, it binds the filesystem vtest socket
 * immediately, and it accepts every connection in Kotlin. Loading the renderer
 * library is slow, so it only happens when a container actually runs, through
 * an asynchronous [VirglService.MSG_START_CORE] request. Container startup
 * therefore never waits for the service process or for virgl to initialize.
 *
 * Containers bind that socket at [CONTAINER_SOCKET]. The socket is passed as an
 * environment variable; containers never see file-manager paths.
 */
object VirglManager {
    private const val TAG = "VirglManager"
    private const val SOCKET_NAME = "virgl.sock"
    private const val LISTENER_WAIT_MS = 2_000L
    const val CONTAINER_SOCKET = "/dev/virgl"

    private val lock = Any()
    private var binding: ServiceConnection? = null
    private var messenger: Messenger? = null
    private var coreWanted = false

    fun socketDir(context: Context): File =
        File(context.cacheDir, "virgl").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Cannot create VirGL socket directory: $absolutePath")
            }
        }

    fun socketPath(context: Context): File = File(socketDir(context), SOCKET_NAME)

    fun environment(): Map<String, String> = mapOf(
        "GALLIUM_DRIVER" to "virpipe",
        "VTEST_SOCKET_NAME" to CONTAINER_SOCKET,
        "LIBGL_ALWAYS_SOFTWARE" to "1",
    )

    /**
     * Bind the resident listener early (for example when the UI starts) so a
     * container launch only has to use the socket that is already there.
     */
    fun ensureResident(context: Context) {
        bindHost(context)
    }

    /**
     * Ask the resident service to start the renderer asynchronously. This never
     * waits for virgl; connections that arrive before it is ready are queued by
     * the service.
     */
    fun ensureHost(context: Context) {
        val target = synchronized(lock) {
            coreWanted = true
            messenger
        }
        if (target != null) {
            requestCore(target)
        } else {
            // Not connected yet: bind (or wait for the pending binding) and let
            // onServiceConnected deliver the request.
            bindHost(context)
        }
        awaitListener(context)
    }

    /**
     * Only the listener is guarded here, never the renderer. In normal use the
     * resident service bound at UI start already created the socket, so this
     * returns immediately.
     */
    private fun awaitListener(context: Context) {
        val socket = socketPath(context)
        if (socket.exists()) return
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val deadline = System.currentTimeMillis() + LISTENER_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists()) return
            Thread.sleep(10)
        }
        Log.w(TAG, "VirGL listener did not become ready: ${socket.absolutePath}")
    }

    private fun requestCore(target: Messenger) {
        runCatching { target.send(Message.obtain(null, VirglService.MSG_START_CORE)) }
            .onFailure { error -> Log.w(TAG, "cannot start VirGL core", error) }
    }

    private fun bindHost(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (binding != null) return
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) return
                    val target = Messenger(service)
                    val wanted = synchronized(lock) {
                        messenger = target
                        coreWanted
                    }
                    if (wanted) requestCore(target)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The binding is still registered; the system recreates the
                    // service and calls onServiceConnected again.
                    synchronized(lock) { messenger = null }
                }

                override fun onBindingDied(name: ComponentName?) {
                    synchronized(lock) {
                        if (binding === this) binding = null
                        messenger = null
                    }
                    runCatching { appContext.unbindService(this) }
                }

                override fun onNullBinding(name: ComponentName?) {
                    synchronized(lock) {
                        if (binding === this) binding = null
                        messenger = null
                    }
                    runCatching { appContext.unbindService(this) }
                }
            }
            val intent = Intent(appContext, VirglService::class.java)
                .putExtra(VirglService.EXTRA_SOCKET, socketPath(appContext).absolutePath)
            if (appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = connection
            } else {
                Log.w(TAG, "cannot bind VirGL host")
            }
        }
    }
}
