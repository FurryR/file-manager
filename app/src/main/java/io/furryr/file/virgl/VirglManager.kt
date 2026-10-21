package io.furryr.file.virgl

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import io.furryr.file.agent.BindMount
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * VirGL host control and container wiring.
 *
 * VirGL is always enabled.  The `:virgl` process owns the vtest listener and
 * serves each accepted connection with its own renderer instance.  Containers
 * bind that socket at [CONTAINER_SOCKET].  The socket is passed as an
 * environment variable; containers never see file-manager paths.
 */
object VirglManager {
    private const val TAG = "VirglManager"
    private const val SOCKET_NAME = "virgl.sock"
    const val CONTAINER_SOCKET = "/dev/virgl"
    private const val START_TIMEOUT_MS = 15_000L
    private const val SOCKET_WAIT_MS = 10_000L

    fun socketDir(context: Context): File =
        File(context.cacheDir, "virgl").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Cannot create VirGL socket directory: $absolutePath")
            }
        }

    fun socketPath(context: Context): File = File(socketDir(context), SOCKET_NAME)

    /** Bind the renderer socket into the container at /dev/virgl. */
    fun bindMount(context: Context): BindMount =
        BindMount(socketPath(context).absolutePath, CONTAINER_SOCKET)

    fun environment(): Map<String, String> = mapOf(
        "GALLIUM_DRIVER" to "virpipe",
        "VTEST_SOCKET_NAME" to CONTAINER_SOCKET,
        "LIBGL_ALWAYS_SOFTWARE" to "1",
    )

    /**
     * Start the `:virgl` process if needed.  When called off the main thread,
     * wait briefly for the socket so a container daemon can bind it immediately.
     */
    fun ensureHost(context: Context) {
        val socket = socketPath(context)
        if (socketIsLive(socket)) return
        runCatching {
            startServiceCompat(
                context,
                Intent(context, VirglService::class.java).apply {
                    action = VirglService.ACTION_START
                    putExtra(VirglService.EXTRA_SOCKET, socket.absolutePath)
                },
            )
        }.onFailure {
            Log.w(TAG, "cannot start VirGL host", it)
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) return
        val deadline = System.currentTimeMillis() + SOCKET_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socketIsLive(socket)) return
            Thread.sleep(50)
        }
        Log.w(TAG, "VirGL socket did not become ready: ${socket.absolutePath}")
    }

    private fun socketIsLive(socket: File): Boolean {
        if (!socket.exists()) return false
        return runCatching {
            LocalSocket().use {
                it.connect(
                    LocalSocketAddress(
                        socket.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    )
                )
            }
            true
        }.getOrDefault(false)
    }

    /** Start the host and wait for the socket, returning an error on failure. */
    suspend fun start(context: Context): Result<String> = runCatching {
        val socket = socketPath(context).absolutePath
        withTimeout(START_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        if (!continuation.isActive) return
                        if (resultCode == VirglService.RESULT_OK) {
                            continuation.resume(
                                resultData?.getString(VirglService.EXTRA_SOCKET) ?: socket
                            )
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    resultData?.getString(VirglService.EXTRA_ERROR)
                                        ?: "VirGL renderer failed"
                                )
                            )
                        }
                    }
                }
                startServiceCompat(
                    context,
                    Intent(context, VirglService::class.java).apply {
                        action = VirglService.ACTION_START
                        putExtra(VirglService.EXTRA_SOCKET, socket)
                        putExtra(VirglService.EXTRA_RECEIVER, receiver)
                    },
                )
            }
        }
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
