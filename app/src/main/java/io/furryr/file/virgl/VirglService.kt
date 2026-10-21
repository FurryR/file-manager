package io.furryr.file.virgl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import io.furryr.file.MainActivity
import io.furryr.file.R
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsFdServer
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated process that owns the host VirGL renderer.
 *
 * The service binds one vtest socket.  Every accepted container connection is
 * served by its own native renderer instance on a bounded worker pool, so
 * containers render in parallel up to [DEFAULT_MAX_CONCURRENCY] (or a caller
 * supplied limit; 1 makes the pool serial).  Keeping all of this out of the UI
 * process means a renderer or driver crash cannot take down the file manager or
 * its daemon.
 */
class VirglService : Service() {
    companion object {
        private const val TAG = "VirglService"
        private const val CHANNEL_ID = "virgl"
        private const val NOTIFICATION_ID = 1003

        const val ACTION_START = "io.furryr.file.action.VIRGL_START"

        const val EXTRA_SOCKET = "io.furryr.file.extra.VIRGL_SOCKET"
        const val EXTRA_RECEIVER = "io.furryr.file.extra.VIRGL_RECEIVER"
        const val EXTRA_ERROR = "io.furryr.file.extra.VIRGL_ERROR"
        const val EXTRA_MAX_CONCURRENCY = "io.furryr.file.extra.VIRGL_MAX_CONCURRENCY"

        const val DEFAULT_MAX_CONCURRENCY = 4
        private const val MAX_CONCURRENCY_LIMIT = 8
        private const val CLIENT_QUEUE_CAPACITY = 32

        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
    }

    private class Session(val handle: Long)

    private class Instance(
        val socketPath: String,
        val listener: UdsFdServer,
        val acceptExecutor: ExecutorService,
        val workers: ThreadPoolExecutor,
    ) {
        @Volatile var closed = false
        val sessionLock = Any()
        val sessions = mutableSetOf<Session>()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val threadIndex = AtomicInteger()
    private val stateLock = Any()
    @Volatile private var instance: Instance? = null
    @Volatile private var stopping = false
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        startForegroundCompat()

        when (action) {
            ACTION_START -> {
                val socketPath = intent.getStringExtra(EXTRA_SOCKET)
                val receiver = receiverFrom(intent)
                if (socketPath.isNullOrBlank()) {
                    reply(receiver, RESULT_ERROR, error = "missing VirGL socket path")
                    stopSelf()
                    return START_NOT_STICKY
                }
                instance?.let {
                    reply(receiver, RESULT_OK, socket = it.socketPath)
                    return START_NOT_STICKY
                }
                val maxConcurrency = intent.getIntExtra(
                    EXTRA_MAX_CONCURRENCY,
                    DEFAULT_MAX_CONCURRENCY,
                ).coerceIn(1, MAX_CONCURRENCY_LIMIT)
                executor.execute { startInstance(socketPath, maxConcurrency, receiver) }
            }

        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val current = synchronized(stateLock) {
            stopping = true
            instance.also { instance = null }
        }
        current?.let { shutdownInstance(it) }
        executor.shutdownNow()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startInstance(socketPath: String, maxConcurrency: Int, receiver: ResultReceiver?) {
        synchronized(stateLock) {
            instance?.let {
                reply(receiver, RESULT_OK, socket = it.socketPath)
                return
            }
            if (stopping) return
        }

        val socketFile = File(socketPath)
        runCatching { socketFile.delete() }

        val listener = try {
            UdsFdServer.listen(UdsEndpoint.Path(socketPath))
        } catch (error: Throwable) {
            Log.e(TAG, "cannot bind VirGL socket", error)
            reply(receiver, RESULT_ERROR, error = error.message ?: "VirGL socket unavailable")
            stopSelf()
            return
        }
        runCatching { Os.chmod(socketPath, 0x1b6) }
            .onFailure { Log.w(TAG, "cannot make VirGL socket world-accessible", it) }

        if (stopping) {
            runCatching { listener.close() }
            reply(receiver, RESULT_ERROR, error = "VirGL service stopping")
            stopSelf()
            return
        }

        val state = try {
            val workers = ThreadPoolExecutor(
                maxConcurrency,
                maxConcurrency,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(CLIENT_QUEUE_CAPACITY),
                { runnable ->
                    Thread(runnable, "virgl-client-${threadIndex.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                },
                ThreadPoolExecutor.AbortPolicy(),
            )
            val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "virgl-accept").apply { isDaemon = true }
            }
            Instance(socketPath, listener, acceptExecutor, workers)
        } catch (error: Throwable) {
            runCatching { listener.close() }
            reply(receiver, RESULT_ERROR, error = error.message ?: "VirGL pool unavailable")
            stopSelf()
            return
        }

        val active = synchronized(stateLock) {
            if (stopping) {
                false
            } else {
                instance = state
                state.acceptExecutor.execute { acceptLoop(state) }
                Log.i(TAG, "serving $socketPath with max concurrency $maxConcurrency")
                true
            }
        }
        if (!active) {
            state.closed = true
            runCatching { listener.close() }
            state.acceptExecutor.shutdownNow()
            state.workers.shutdownNow()
            reply(receiver, RESULT_ERROR, error = "VirGL service stopping")
            stopSelf()
            return
        }

        reply(receiver, RESULT_OK, socket = socketPath)
    }

    private fun acceptLoop(state: Instance) {
        while (!state.closed) {
            val fd = try {
                state.listener.acceptFd()
            } catch (error: Throwable) {
                if (state.closed) return
                Log.w(TAG, "VirGL accept failed", error)
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }

            if (state.closed) {
                state.listener.closeFd(fd)
                return
            }

            try {
                state.workers.execute {
                    if (state.closed) {
                        state.listener.closeFd(fd)
                        return@execute
                    }

                    val handle = try {
                        VirglNative.create()
                    } catch (error: Throwable) {
                        Log.e(TAG, "cannot allocate VirGL instance", error)
                        state.listener.closeFd(fd)
                        return@execute
                    }
                    if (handle == 0L) {
                        state.listener.closeFd(fd)
                        return@execute
                    }

                    val session = Session(handle)
                    synchronized(state.sessionLock) {
                        // Recheck under the same lock shutdownInstance() uses,
                        // so a session can never be added after shutdown began.
                        if (state.closed) {
                            state.listener.closeFd(fd)
                            runCatching { VirglNative.destroy(handle) }
                            return@execute
                        }
                        state.sessions.add(session)
                    }

                    try {
                        VirglNative.serve(handle, fd)
                    } catch (error: Throwable) {
                        Log.e(TAG, "VirGL session failed", error)
                    } finally {
                        synchronized(state.sessionLock) {
                            state.sessions.remove(session)
                            runCatching { VirglNative.destroy(handle) }
                        }
                    }
                }
            } catch (error: RejectedExecutionException) {
                Log.w(TAG, "VirGL client queue full; rejecting connection")
                state.listener.closeFd(fd)
            }
        }
    }

    private fun shutdownInstance(current: Instance) {
        current.closed = true
        runCatching { current.listener.close() }
        current.acceptExecutor.shutdownNow()
        synchronized(current.sessionLock) {
            for (session in current.sessions) {
                runCatching { VirglNative.stop(session.handle) }
            }
        }
        current.workers.shutdown()
    }

    private fun reply(receiver: ResultReceiver?, code: Int, socket: String? = null, error: String? = null) {
        val result = receiver ?: return
        val bundle = Bundle().apply {
            socket?.let { putString(EXTRA_SOCKET, it) }
            error?.let { putString(EXTRA_ERROR, it) }
        }
        result.send(code, bundle)
    }

    @Suppress("DEPRECATION")
    private fun receiverFrom(intent: Intent): ResultReceiver? =
        intent.getParcelableExtra(EXTRA_RECEIVER) as? ResultReceiver

    private fun startForegroundCompat() {
        if (foregroundStarted) return
        foregroundStarted = true
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopForegroundCompat() {
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.virgl_notification_title))
            .setContentText(getString(R.string.virgl_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1003,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.virgl_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }
}
