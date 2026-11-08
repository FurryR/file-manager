package io.furryr.file.render

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live block-read progress for remote HTTP(S) handles.
 *
 * A range-backed [WebRangeFileSource] registers itself here, reports every
 * network block request and the bytes it delivered, and unregisters on close.
 * The tab bar subscribes to [progress] and shows the same slim strip it uses
 * for page loads, so a remote archive or viewer read has visible feedback
 * instead of an apparently idle bar.
 *
 * The registry is process-wide: a handle may be opened by
 * [io.furryr.file.forward.ForwardFileProvider] on behalf of another app while
 * the file-manager UI is still the visible activity. Active reads also drive
 * [WebTransferService], which keeps one foreground notification per file
 * while its bytes are on the wire.
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
    private var appContext: Context? = null

    /** Snapshot list of live transfers; empty when no handle is open. */
    val progress: StateFlow<List<Progress>> = _progress.asStateFlow()

    /** Bind the process-wide notification service to an application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun open(
        url: String,
        totalBytes: Long,
        name: String,
        onDemand: Boolean,
    ): Long {
        val id = synchronized(lock) {
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
        return id
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
        val context = appContext ?: return
        runCatching { WebTransferService.start(context) }
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
