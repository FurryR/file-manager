package io.furryr.file.pulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.Executors

/**
 * Resident host process that owns the embedded PulseAudio core.
 *
 * The app owns the socket and binds this service only when an audio client
 * connects. Accepted descriptors arrive over Messenger and queue here while the
 * core initializes or restarts. The control executor hands batches to JNI;
 * native code connects them on the PulseAudio main loop, never on a Binder thread.
 *
 * Audio policy stays here, in Kotlin: the sink plays through a single
 * AudioTrack, and the microphone is opened only while the core reports the
 * source running AND the app holds RECORD_AUDIO. While the microphone is
 * open the service promotes itself to a microphone-type foreground service
 * (Android 11+ otherwise silences background capture).
 */
class PulseService : Service() {
    companion object {
        private const val TAG = "PulseService"
        const val MSG_CONFIGURE = 1
        const val MSG_RESTART_CORE = 2
        const val MSG_CLIENT = 3
        const val CLIENT_FD = "client_fd"
        private const val MIC_NOTIFICATION_ID = 1
        private const val MIC_CHANNEL_ID = "pulse_mic"
        private const val MAX_PENDING_CLIENTS = 64

        internal const val SINK_BUFFER_BYTES = 64 * 1024
        internal const val MIC_BUFFER_BYTES = 8 * 1024

        /** 48000 Hz × 2ch × 4 B = 384 bytes per millisecond of sink audio. */
        private const val SINK_BYTES_PER_MS = 384
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-control").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private val pendingClients = ArrayDeque<ParcelFileDescriptor>()
    private var drainScheduled = false
    private var handle = 0L
    private var coreScheduled = false
    private var restartPending = false
    private var micEnabled = false
    private var bufferMs = PulseManager.DEFAULT_BUFFER_MS
    private var stopping = false
    private var foregroundForMic = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_CONFIGURE || msg.what == MSG_RESTART_CORE) {
                synchronized(stateLock) {
                    micEnabled = msg.arg1 != 0
                    bufferMs = msg.arg2.coerceIn(PulseManager.MIN_BUFFER_MS, PulseManager.MAX_BUFFER_MS)
                }
            }
            when (msg.what) {
                MSG_CONFIGURE -> Unit
                MSG_RESTART_CORE -> restartCore()
                MSG_CLIENT -> {
                    @Suppress("DEPRECATION")
                    val client = msg.data.getParcelable<ParcelFileDescriptor>(CLIENT_FD)
                    if (client != null) enqueueClient(client)
                }
                else -> super.handleMessage(msg)
            }
        }
    })

    internal val sinkBuffer = java.nio.ByteBuffer.allocateDirect(SINK_BUFFER_BYTES)
    internal val micBuffer = java.nio.ByteBuffer.allocateDirect(MIC_BUFFER_BYTES)
    // One playback device per core, sized from the latency setting when the
    // core starts, so an in-place core restart picks up the new value.
    @Volatile
    private var audioOut: AudioOut? = null
    private var micInput: MicInput? = null

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        synchronized(stateLock) {
            stopping = true
            generation++
            closePendingClients()
        }
        stopMicLocked()
        executor.execute {
            destroyInstance()
            // Null the reference first: a late sink callback must be dropped,
            // not lazily rebuild an AudioTrack.
            audioOut?.release()
            audioOut = null
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun enqueueClient(client: ParcelFileDescriptor) {
        synchronized(stateLock) {
            if (stopping || pendingClients.size >= MAX_PENDING_CLIENTS) {
                Log.w(TAG, "dropping audio connection: service stopped or queue full")
                closeClient(client)
                return
            }
            pendingClients.addLast(client)
            if (coreScheduled) return
            if (handle == 0L) {
                coreScheduled = true
                executor.execute { startInstance() }
            } else {
                scheduleDrain()
            }
        }
    }

    /** Called under stateLock; executor serialization keeps the handle alive during JNI hand-off. */
    private fun scheduleDrain() {
        if (drainScheduled || stopping || coreScheduled || handle == 0L || pendingClients.isEmpty()) return
        drainScheduled = true
        executor.execute {
            val (current, clients) = synchronized(stateLock) {
                drainScheduled = false
                if (stopping || coreScheduled || handle == 0L) return@execute
                handle to pendingClients.toList().also { pendingClients.clear() }
            }
            try {
                if (!PulseNative.addClients(current, clients.map { it.fd }.toIntArray())) {
                    Log.w(TAG, "cannot hand audio connections to PulseAudio")
                }
            } catch (error: Throwable) {
                Log.e(TAG, "audio connection hand-off failed", error)
            } finally {
                clients.forEach { closeClient(it) }
            }
        }
    }

    private fun closeClient(client: ParcelFileDescriptor) {
        runCatching { client.close() }
            .onFailure { Log.w(TAG, "cannot close audio connection", it) }
    }

    /** Called under stateLock. A failed initialization must not retain clients indefinitely. */
    private fun closePendingClients() {
        while (pendingClients.isNotEmpty()) closeClient(pendingClients.removeFirst())
    }

    /**
     * Stop the running core and start a new one with the current settings.
     * The app listener is never closed: connections queue here while the
     * core is down, and clients dropped with the old core can reconnect immediately.
     * A core restart never affects the container view of the socket.
     */
    private fun restartCore() {
        synchronized(stateLock) {
            if (stopping) return
            if (coreScheduled) {
                restartPending = true
                return
            }
            if (handle == 0L) return
            coreScheduled = true
        }
        executor.execute {
            destroyInstance()
            // The source died with the core; drop the capture side here rather
            // than waiting for an onMicState(false) that may never come.
            mainHandler.post { stopMicLocked() }
            startInstance()
        }
    }

    /** Initialize only on the control executor, after at least one client arrives. */
    private fun startInstance() {
        synchronized(stateLock) { if (stopping || handle != 0L) return }

        // The previous core (if any) is already stopped, so no sink callbacks
        // are in flight while the playback device is swapped.
        audioOut?.release()
        // Settings arrive over Messenger: SharedPreferences caches are process-local.
        val settings = synchronized(stateLock) { micEnabled to bufferMs }
        audioOut = AudioOut(settings.second * SINK_BYTES_PER_MS)

        val created = try {
            PulseNative.create(
                callback = this,
                sinkBuffer = sinkBuffer,
                micBuffer = micBuffer,
                micEnabled = settings.first,
                bufferMs = settings.second,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "cannot allocate PulseAudio host", error)
            0L
        }
        if (created == 0L) {
            audioOut?.release()
            audioOut = null
            synchronized(stateLock) {
                coreScheduled = false
                restartPending = false
                closePendingClients()
            }
            Log.e(TAG, "PulseAudio core failed; see native log")
            return
        }
        val active = synchronized(stateLock) {
            if (stopping) {
                coreScheduled = false
                false
            } else {
                handle = created
                // A settings update during creation must restart before any clients connect.
                if (restartPending) {
                    restartPending = false
                    executor.execute {
                        destroyInstance()
                        mainHandler.post { stopMicLocked() }
                        startInstance()
                    }
                } else {
                    coreScheduled = false
                    scheduleDrain()
                }
                true
            }
        }
        if (!active) {
            PulseNative.destroy(created)
        }
    }

    internal fun nativeHandle(): Long = synchronized(stateLock) { handle }

    /** Serialize capture pushes with handle retirement; never join native threads under this lock. */
    internal fun postMicPcm(expected: Long, length: Int) = synchronized(stateLock) {
        if (handle == expected && handle != 0L) PulseNative.postMicPcm(handle, length)
    }

    private fun destroyInstance() {
        val current = synchronized(stateLock) {
            generation++
            handle.also { handle = 0L }
        }
        if (current != 0L) {
            runCatching { PulseNative.destroy(current) }
                .onFailure { Log.e(TAG, "cannot destroy PulseAudio core", it) }
        }
    }

    /** Called from the sink stream thread: mixed PCM, ready to play. */
    fun onSinkData(length: Int) {
        if (length <= 0) return
        audioOut?.write(sinkBuffer, length)
    }

    fun onSinkIdle() {
        audioOut?.finish()
    }

    /** Called from the source stream: capture demand changed. */
    fun onMicState(running: Boolean) {
        val epoch = synchronized(stateLock) { generation }
        mainHandler.post {
            if (synchronized(stateLock) { stopping || epoch != generation }) return@post
            if (running) startMic() else stopMicLocked()
        }
    }

    private fun startMic() {
        val input: MicInput
        synchronized(stateLock) {
            if (stopping || micInput != null) return
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "mic requested but RECORD_AUDIO not granted; source stays silent")
                return
            }
            if (!promoteForeground()) return
            input = MicInput(this)
            if (!input.start()) {
                demoteForeground()
                return
            }
            micInput = input
        }
    }

    private fun stopMicLocked() {
        val input: MicInput?
        synchronized(stateLock) {
            input = micInput.also { micInput = null }
        }
        input?.stop()
        demoteForeground()
    }

    private fun promoteForeground(): Boolean {
        if (foregroundForMic) return true
        foregroundForMic = true
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null && manager.getNotificationChannel(MIC_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MIC_CHANNEL_ID,
                    getString(R.string.pulse_mic_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification = Notification.Builder(this, MIC_CHANNEL_ID)
            .setContentTitle(getString(R.string.pulse_mic_active))
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(
                    MIC_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(MIC_NOTIFICATION_ID, notification)
            }
        }.onFailure { error ->
            Log.w(TAG, "cannot promote to microphone foreground service", error)
            foregroundForMic = false
        }
        return foregroundForMic
    }

    private fun demoteForeground() {
        if (!foregroundForMic) return
        foregroundForMic = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }
}
