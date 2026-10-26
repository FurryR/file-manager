package io.furryr.file.pulse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import io.furryr.file.uds.UdsEndpoint
import io.furryr.file.uds.UdsFdServer
import java.io.File
import java.util.concurrent.Executors

/**
 * PulseAudio host control and container wiring.
 *
 * Audio is on by default and can
 * be turned off in Settings → Container; when off, nothing binds the `:pulse`
 * process and containers get neither the socket mount nor the audio environment
 * variables. When on, the app owns a lightweight listener. Only the first audio
 * connection binds the `:pulse` service, which starts the embedded core off-thread.
 * Accepted descriptors queue here until the service connects, then in the service
 * until the core is ready. Container startup never waits for that process or core.
 *
 * Containers bind the socket directory at [CONTAINER_SOCKET_DIR].
 * [CONTAINER_SOCKET] is its stable guest alias; directory binds survive listener
 * recreation without pinning an obsolete socket inode in chroot containers.
 * Containers never see file-manager paths.
 */
object PulseManager {
    private const val TAG = "PulseManager"
    private const val SOCKET_NAME = "pulse"
    private const val MAX_PENDING_CLIENTS = 64
    private const val PREFS_NAME = "settings"
    private const val PREF_ENABLED = "pulse_enabled"
    private const val PREF_MIC = "pulse_mic_enabled"
    private const val PREF_BUFFER = "pulse_buffer_ms"

    /** Sink latency bounds in milliseconds; the default matches the old fixed cap. */
    const val DEFAULT_BUFFER_MS = 200
    const val MIN_BUFFER_MS = 10
    const val MAX_BUFFER_MS = 1000
    const val CONTAINER_SOCKET = "/dev/pulse"
    const val CONTAINER_SOCKET_DIR = "/dev/audio"
    const val CONTAINER_SOCKET_TARGET = "$CONTAINER_SOCKET_DIR/$SOCKET_NAME"

    private val lock = Any()
    private val control = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-dispatch").apply { isDaemon = true }
    }
    private var binding: ServiceConnection? = null
    private var boundContext: Context? = null
    private var messenger: Messenger? = null
    private var listener: UdsFdServer? = null
    private val pendingClients = ArrayDeque<ParcelFileDescriptor>()
    private var deliveryScheduled = false

    // Synced from the preference by every context-bearing entry point, so the
    // context-free [environment] sees the current value.
    @Volatile
    private var enabled = true

    private fun preferences(context: Context): SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Keep existing choices when upgrading from the replaced backend.
        val editor = prefs.edit()
        for (key in listOf(PREF_ENABLED, PREF_MIC, PREF_BUFFER)) {
            val legacy = key.replaceFirst("pulse_", "pipewire_")
            if (!prefs.contains(key) && prefs.contains(legacy)) {
                if (key == PREF_BUFFER) editor.putInt(key, prefs.getInt(legacy, DEFAULT_BUFFER_MS))
                else editor.putBoolean(key, prefs.getBoolean(legacy, key == PREF_ENABLED))
            }
        }
        editor.apply()
        return prefs
    }

    fun isEnabled(context: Context): Boolean {
        enabled = preferences(context).getBoolean(PREF_ENABLED, true)
        return enabled
    }

    fun isMicEnabled(context: Context): Boolean =
        preferences(context).getBoolean(PREF_MIC, false)

    /** Sink latency in milliseconds: lower is faster, too low underruns. */
    fun bufferMs(context: Context): Int =
        preferences(context)
            .getInt(PREF_BUFFER, DEFAULT_BUFFER_MS)
            .coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)

    /** Persist the preference and apply it: bind the app listener, or stop audio. */
    fun setEnabled(context: Context, value: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, value).apply()
        enabled = value
        if (value) ensureResident(appContext) else releaseHost()
    }

    /**
     * Persist the microphone preference. The core decides at creation time
     * whether to expose a source, so a running core is restarted to apply it —
     * in place, without touching the listener or the socket.
     */
    fun setMicEnabled(context: Context, value: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_MIC, value).apply()
        restartCore(appContext)
    }

    /**
     * Persist the sink latency. The core reads it when the sink is created,
     * so a running core is restarted to apply it — in place, without touching
     * the listener or the socket.
     */
    fun setBufferMs(context: Context, value: Int) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_BUFFER, value.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS))
            .apply()
        restartCore(appContext)
    }

    /**
     * Apply core-affecting settings by restarting the PulseAudio core inside the
     * resident service. Unbinding and re-binding can silently reuse the still-alive
     * service instance, never applying the change at all.
     * With [PulseService.MSG_RESTART_CORE] the app-side socket stays
     * connectable throughout; when no host is bound, the next connection
     * simply reads the current settings.
     */
    private fun restartCore(context: Context) {
        val appContext = context.applicationContext
        control.execute {
            val target = synchronized(lock) { messenger } ?: return@execute
            runCatching { target.send(coreMessage(appContext, PulseService.MSG_RESTART_CORE)) }
                .onFailure { error -> Log.w(TAG, "cannot restart PulseAudio core", error) }
        }
    }

    fun socketDir(context: Context): File =
        File(context.cacheDir, "pulse").apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("Cannot create PulseAudio socket directory: $absolutePath")
            }
        }

    fun socketPath(context: Context): File = File(socketDir(context), SOCKET_NAME)

    fun environment(): Map<String, String> =
        if (enabled) mapOf(
            "PULSE_SERVER" to "unix:$CONTAINER_SOCKET",
        ) else emptyMap()

    /**
     * Bind only the app-side listener. No service process or audio engine starts.
     */
    fun ensureResident(context: Context) {
        if (!isEnabled(context)) return
        val appContext = context.applicationContext
        synchronized(lock) {
            if (!enabled || listener != null) return
            val path = socketPath(appContext).absolutePath
            File(path).delete()
            val server = UdsFdServer.listen(UdsEndpoint.Path(path), MAX_PENDING_CLIENTS)
            try {
                Os.chmod(path, 0x1b6)
                listener = server
                Thread({ acceptClients(appContext, server) }, "pulse-accept").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "listening on $path; core starts on first audio connection")
            } catch (error: Throwable) {
                listener = null
                server.close()
                throw error
            }
        }
    }

    /**
     * Ensure the socket exists before mounting its directory. Audio is connection-triggered;
     * this never binds or waits for the service or initializes the core.
     */
    fun ensureHost(context: Context) {
        ensureResident(context)
    }

    private fun acceptClients(context: Context, server: UdsFdServer) {
        try {
            while (true) {
                val fd = server.acceptFd()
                val client = try {
                    ParcelFileDescriptor.adoptFd(fd)
                } catch (error: Throwable) {
                    server.closeFd(fd)
                    throw error
                }
                val needsBinding = synchronized(lock) {
                    if (listener !== server || !enabled) {
                        closeClient(client)
                        return
                    }
                    if (pendingClients.size < MAX_PENDING_CLIENTS) {
                        pendingClients.addLast(client)
                        scheduleDelivery()
                        messenger == null
                    } else {
                        Log.w(TAG, "audio connection queue full")
                        closeClient(client)
                        false
                    }
                }
                if (needsBinding) control.execute { bindHost(context) }
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (listener === server) {
                    Log.e(TAG, "audio listener failed", error)
                    listener = null
                    server.close()
                    closePendingClients()
                }
            }
        }
    }

    private fun coreMessage(context: Context, what: Int): Message = Message.obtain(
        null, what, if (isMicEnabled(context)) 1 else 0, bufferMs(context),
    )

    /** Called under lock. Never perform Binder work while holding the container-launch lock. */
    private fun scheduleDelivery() {
        if (deliveryScheduled || messenger == null || pendingClients.isEmpty()) return
        deliveryScheduled = true
        control.execute {
            val (target, clients) = synchronized(lock) {
                deliveryScheduled = false
                val target = messenger ?: return@execute
                target to pendingClients.toList().also { pendingClients.clear() }
            }
            clients.forEach { sendClient(target, it) }
        }
    }

    private fun sendClient(target: Messenger, client: ParcelFileDescriptor) {
        try {
            val message = Message.obtain(null, PulseService.MSG_CLIENT)
            message.data.putParcelable(PulseService.CLIENT_FD, client)
            target.send(message)
        } catch (error: Exception) {
            Log.w(TAG, "cannot deliver audio connection", error)
        } finally {
            closeClient(client)
        }
    }

    private fun closeClient(client: ParcelFileDescriptor) {
        runCatching { client.close() }
            .onFailure { Log.w(TAG, "cannot close audio connection", it) }
    }

    private fun closePendingClients() {
        while (pendingClients.isNotEmpty()) closeClient(pendingClients.removeFirst())
    }

    /** Disable audio: close the app listener, drop queued clients and unbind the core. */
    private fun releaseHost() {
        val stopped = synchronized(lock) {
            val connection = binding
            val context = boundContext
            binding = null
            boundContext = null
            messenger = null
            listener?.close()
            listener = null
            closePendingClients()
            if (connection != null && context != null) connection to context else null
        }
        if (stopped != null) {
            control.execute {
                runCatching { stopped.second.unbindService(stopped.first) }
                    .onFailure { error -> Log.w(TAG, "cannot stop PulseAudio host", error) }
            }
        }
    }

    private fun bindHost(context: Context) {
        val appContext = context.applicationContext
        val connection = synchronized(lock) {
            if (!enabled || listener == null || binding != null || pendingClients.isEmpty()) return
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) return
                    val target = Messenger(service)
                    control.execute {
                        synchronized(lock) { if (binding !== this) return@execute }
                        runCatching { target.send(coreMessage(appContext, PulseService.MSG_CONFIGURE)) }
                            .onFailure {
                                Log.w(TAG, "cannot configure PulseAudio core", it)
                                synchronized(lock) { if (binding === this) closePendingClients() }
                                return@execute
                            }
                        synchronized(lock) {
                            if (binding !== this) return@execute
                            messenger = target
                            scheduleDelivery()
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The binding is still registered; the system recreates the
                    // service and calls onServiceConnected again.
                    control.execute {
                        synchronized(lock) { if (binding === this) messenger = null }
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    synchronized(lock) {
                        if (binding === this) {
                            binding = null
                            boundContext = null
                            messenger = null
                            closePendingClients()
                        }
                    }
                    control.execute {
                        runCatching { appContext.unbindService(this) }
                            .onFailure { Log.w(TAG, "cannot unbind dead audio service", it) }
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    synchronized(lock) {
                        if (binding === this) {
                            binding = null
                            boundContext = null
                            messenger = null
                            closePendingClients()
                        }
                    }
                    control.execute {
                        runCatching { appContext.unbindService(this) }
                            .onFailure { Log.w(TAG, "cannot unbind unavailable audio service", it) }
                    }
                }
            }
            binding = connection
            boundContext = appContext
            connection
        }
        val bound = try {
            appContext.bindService(Intent(appContext, PulseService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            Log.w(TAG, "cannot bind PulseAudio host", error)
            false
        }
        if (!bound) {
            Log.w(TAG, "cannot bind PulseAudio host")
            synchronized(lock) {
                if (binding === connection) {
                    binding = null
                    boundContext = null
                    closePendingClients()
                }
            }
        }
    }
}
