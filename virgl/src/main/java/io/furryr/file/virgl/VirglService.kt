package io.furryr.file.virgl

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsFdServer
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Resident host process that owns the VirGL renderer.
 *
 * Binding the service is cheap and keeps the `:virgl` process alive with the
 * caller's process importance, so [VirglManager] binds it early and container
 * launches only need the vtest socket it already bound. The renderer library
 * (virglrenderer + EGL + the GPU driver) is loaded later, when
 * [MSG_START_CORE] arrives, on a background thread. The accept loop runs in
 * Kotlin: connections are queued until the native core is ready and then handed
 * over as raw descriptors.
 *
 * The native host serves every connection from one thread through one shared
 * renderer instance (vtest multi-client mode), so the number of connections is
 * not tied to the number of threads. Keeping this out of the UI process means a
 * renderer or driver crash cannot take down the file manager or its daemon.
 */
class VirglService : Service() {
    companion object {
        private const val TAG = "VirglService"

        const val EXTRA_SOCKET = "io.furryr.file.extra.VIRGL_SOCKET"
        const val MSG_START_CORE = 1

        private const val LISTEN_BACKLOG = 16

        private val descriptorField: Field by lazy {
            FileDescriptor::class.java.getDeclaredField("descriptor").apply { isAccessible = true }
        }

        private fun rawFd(fd: FileDescriptor): Int = descriptorField.getInt(fd)
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "virgl-control").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private var listener: UdsFdServer? = null
    private var handle = 0L
    private var renderer: Thread? = null
    private var stopping = false
    private var coreScheduled = false
    private val pending = ArrayDeque<Int>()

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_START_CORE -> startCore()
                else -> super.handleMessage(msg)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder? {
        val path = intent?.getStringExtra(EXTRA_SOCKET)
        if (path.isNullOrBlank()) {
            Log.e(TAG, "missing VirGL socket path")
            return null
        }
        if (!bindListener(path)) return null
        return messenger.binder
    }

    override fun onDestroy() {
        val currentListener: UdsFdServer?
        val currentHandle: Long
        val currentRenderer: Thread?
        val queued: List<Int>
        synchronized(stateLock) {
            stopping = true
            currentListener = listener.also { listener = null }
            currentHandle = handle.also { handle = 0L }
            currentRenderer = renderer.also { renderer = null }
            queued = pending.toList()
            pending.clear()
        }
        runCatching { currentListener?.close() }
        queued.forEach { closeRawFd(it) }
        if (currentHandle != 0L) runCatching { VirglNative.stop(currentHandle) }
        // Let the native loop close its pipes before the process can be bound
        // again; vtest state is process-global.
        runCatching { currentRenderer?.join(1000) }
        executor.shutdown()
        super.onDestroy()
    }

    /** Bind the vtest listener immediately; this does not load the renderer. */
    private fun bindListener(path: String): Boolean {
        val server = try {
            synchronized(stateLock) {
                if (stopping) return false
                if (listener != null) return true
                runCatching { File(path).delete() }
                UdsFdServer.listen(UdsEndpoint.Path(path), LISTEN_BACKLOG).also {
                    listener = it
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "cannot bind VirGL socket $path", error)
            return false
        }
        runCatching { Os.chmod(path, 0x1b6) }
            .onFailure { Log.w(TAG, "cannot make VirGL socket world-accessible", it) }
        Thread({ acceptLoop(server) }, "virgl-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on $path")
        return true
    }

    private fun acceptLoop(server: UdsFdServer) {
        while (true) {
            val fd = try {
                server.acceptFd()
            } catch (error: Throwable) {
                if (!synchronized(stateLock) { stopping }) {
                    Log.d(TAG, "VirGL accept stopped: ${error.message}")
                }
                return
            }
            val target = synchronized(stateLock) {
                when {
                    stopping -> -1L
                    handle != 0L -> handle
                    else -> {
                        pending.addLast(fd)
                        0L
                    }
                }
            }
            when {
                target > 0L -> forward(fd, target)
                target < 0L -> closeRawFd(fd)
            }
        }
    }

    /** Queue the client fd for the native server; ownership transfers on success. */
    private fun forward(fd: Int, target: Long) {
        var handedOver = false
        synchronized(stateLock) {
            if (!stopping && handle == target) {
                handedOver = try {
                    VirglNative.addClient(target, fd) == 0
                } catch (error: Throwable) {
                    Log.e(TAG, "cannot hand client to VirGL", error)
                    false
                }
            }
        }
        if (!handedOver) {
            if (!synchronized(stateLock) { stopping }) {
                Log.w(TAG, "VirGL rejected client fd=$fd")
            }
            closeRawFd(fd)
        }
    }

    private fun closeRawFd(fd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    /** Start the renderer core on demand; the listener is already accepting. */
    private fun startCore() {
        synchronized(stateLock) {
            if (stopping || coreScheduled || handle != 0L) return
            coreScheduled = true
        }
        executor.execute { startInstance() }
    }

    private fun resetCore() {
        synchronized(stateLock) {
            if (!stopping) coreScheduled = false
        }
    }

    private fun startInstance() {
        synchronized(stateLock) { if (stopping || handle != 0L) return }

        val handle = try {
            VirglNative.create()
        } catch (error: Throwable) {
            Log.e(TAG, "cannot allocate VirGL host", error)
            resetCore()
            return
        }
        if (handle == 0L) {
            Log.e(TAG, "VirGL host unavailable")
            resetCore()
            return
        }

        val startError = try {
            val result = VirglNative.startClients(handle)
            if (result == 0) null else VirglNative.error(handle) ?: "VirGL server failed ($result)"
        } catch (error: Throwable) {
            error.message ?: "VirGL server failed"
        }
        if (startError != null) {
            VirglNative.destroy(handle)
            Log.e(TAG, startError)
            resetCore()
            return
        }

        var queued = emptyList<Int>()
        val active = synchronized(stateLock) {
            if (stopping) {
                false
            } else {
                this.handle = handle
                queued = pending.toList()
                pending.clear()
                true
            }
        }
        if (!active) {
            // The render loop never started, so drain the server ourselves to
            // close its pipes before freeing the host.
            VirglNative.stop(handle)
            VirglNative.run(handle)
            VirglNative.destroy(handle)
            return
        }

        queued.forEach { forward(it, handle) }

        val thread = Thread({ runInstance(handle) }, "virgl-render").apply { isDaemon = true }
        synchronized(stateLock) { renderer = thread }
        thread.start()
    }

    private fun runInstance(handle: Long) {
        try {
            val result = VirglNative.run(handle)
            if (result < 0) {
                Log.w(TAG, "VirGL server exited: ${VirglNative.error(handle) ?: result}")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "VirGL server failed", error)
        } finally {
            synchronized(stateLock) {
                if (this.handle == handle) this.handle = 0L
                if (renderer === Thread.currentThread()) renderer = null
                if (!stopping) coreScheduled = false
            }
            VirglNative.destroy(handle)
        }
    }
}
