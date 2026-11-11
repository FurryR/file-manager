package io.furryr.file.provider.web

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live block-read progress for remote HTTP(S) handles.
 *
 * A range-backed [HttpRangeSource] registers itself here, reports every network
 * block request and the bytes it delivered, and unregisters on close. The tab
 * bar subscribes to [progress] and shows the same slim strip it uses for page
 * loads, so a remote archive or viewer read has visible feedback instead of an
 * apparently idle bar.
 *
 * The registry is process-wide: a handle may be opened by the forward provider
 * on behalf of another app while the file-manager UI is still the visible
 * activity. Active reads also keep the host's foreground notification alive;
 * the host binds that starter with [init], so this module owns the transfer
 * facts and the app owns the platform service.
 */
object WebTransfers {
    /** One live handle. [reading] counts block requests currently on the wire. */
    data class Progress(
        val id: Long,
        val url: String,
        /** Display name captured when the handle was opened. */
        val name: String,
        /** Range reads are lazy ("on demand"), a stream is a full download. */
        val onDemand: Boolean,
        val fetchedBytes: Long,
        val totalBytes: Long,
        val reading: Int,
        /** Monotonic stamp of the last block event; newest transfer wins the bar. */
        val updatedAt: Long,
    )

    private class Entry(
        val id: Long,
        val url: String,
        val name: String,
        val onDemand: Boolean,
        val totalBytes: Long,
        var fetchedBytes: Long = 0L,
        var reading: Int = 0,
        var updatedAt: Long = System.nanoTime(),
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    private val _progress = MutableStateFlow<List<Progress>>(emptyList())

    @Volatile
    private var onTransfer: (() -> Unit)? = null

    /** Snapshot list of live transfers; empty when no handle is open. */
    val progress: StateFlow<List<Progress>> = _progress.asStateFlow()

    /**
     * Bind the host's foreground service starter. Called by the app once; a
     * process without an Android host (agent, tests) leaves it null and the
     * transfer simply runs without a notification.
     */
    fun init(onTransfer: (() -> Unit)?) {
        this.onTransfer = onTransfer
    }

    fun open(
        url: String,
        totalBytes: Long,
        name: String,
        onDemand: Boolean,
    ): Long = synchronized(lock) {
        val value = nextId++
        entries[value] = Entry(
            id = value,
            url = url,
            name = name.ifBlank { WebRequest.displayName(url) },
            onDemand = onDemand,
            totalBytes = totalBytes.coerceAtLeast(0L),
        )
        publish()
        value
    }

    fun close(id: Long) {
        synchronized(lock) {
            entries.remove(id)
            publish()
        }
    }

    fun readStarted(id: Long) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            entry.reading++
            entry.updatedAt = System.nanoTime()
            publish()
        }
        ensureService()
    }

    /** Count bytes that arrived; the caller clamps at the handle's total. */
    fun advance(id: Long, bytes: Int) {
        if (bytes <= 0) return
        synchronized(lock) {
            val entry = entries[id] ?: return
            entry.fetchedBytes += bytes
            entry.updatedAt = System.nanoTime()
            publish()
        }
        ensureService()
    }

    fun readFinished(id: Long) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            entry.reading = (entry.reading - 1).coerceAtLeast(0)
            publish()
        }
    }

    private fun ensureService() {
        val starter = onTransfer ?: return
        runCatching { starter() }
            .onFailure { Log.w("WebTransfers", "foreground transfer service start failed", it) }
    }

    /** Caller holds [lock]. */
    private fun publish() {
        _progress.value = entries.values.map {
            Progress(
                id = it.id,
                url = it.url,
                name = it.name,
                onDemand = it.onDemand,
                fetchedBytes = it.fetchedBytes.coerceAtMost(it.totalBytes.coerceAtLeast(0L)),
                totalBytes = it.totalBytes,
                reading = it.reading,
                updatedAt = it.updatedAt,
            )
        }
    }
}
