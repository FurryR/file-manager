package io.furryr.file.provider.remote

import android.util.Base64
import com.jcraft.jsch.Channel
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.security.MessageDigest

/**
 * One SSH session of a remote location's pooled connection set.
 *
 * Sessions are never used directly: [SshConnections] hands them out as
 * [SshLease]s, so callers always release them again. A dead session is replaced
 * once per open before the caller observes the failure.
 */
internal class SshConnection(private val location: RemoteLocation) {
    private var session: Session? = null

    fun acquire(): Session = synchronized(this) {
        session?.takeIf { it.isConnected }?.let { return it }
        closeLocked()
        return connect(location).also { session = it }
    }

    fun invalidate(expected: Session) {
        synchronized(this) {
            if (session === expected) closeLocked()
        }
    }

    fun <T : Channel> open(type: String, configure: (T) -> Unit): T {
        var failure: JSchException? = null
        repeat(2) { attempt ->
            val current = acquire()
            var channel: T? = null
            try {
                @Suppress("UNCHECKED_CAST")
                channel = current.openChannel(type) as T
                configure(channel)
                return channel
            } catch (error: JSchException) {
                channel?.disconnect()
                failure = error
                if (attempt == 0) invalidate(current)
            } catch (error: Exception) {
                channel?.disconnect()
                throw error
            }
        }
        throw failure ?: JSchException("SSH channel failed")
    }

    fun close() {
        synchronized(this) { closeLocked() }
    }

    private fun closeLocked() {
        session?.let { runCatching { it.disconnect() } }
        session = null
    }

    private fun connect(location: RemoteLocation): Session {
        val jsch = JSch()
        val repository = PinnedHostKey(location.hostKey)
        jsch.setHostKeyRepository(repository)
        if (location.privateKey.isNotBlank()) {
            try {
                jsch.addIdentity(
                    "remote-${location.id}",
                    location.privateKey.toByteArray(Charsets.UTF_8),
                    null,
                    location.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
                )
            } catch (failure: Exception) {
                throw IOException("Could not unlock the SSH private key; check its format and passphrase")
            }
        }
        val session = jsch.getSession(location.username, location.host, location.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            if (location.privateKey.isNotBlank()) "publickey,password" else "password",
        )
        session.setPassword(location.password.toByteArray())
        session.timeout = 30_000
        session.serverAliveInterval = 15_000
        try {
            session.connect(30_000)
            return session
        } catch (failure: Exception) {
            session.disconnect()
            repository.observed?.let { key ->
                if (location.hostKey.isEmpty()) {
                    throw SshHostKeyApprovalRequired(
                        fingerprint(key),
                        HostKey(location.host, key).type,
                        Base64.encodeToString(key, Base64.NO_WRAP),
                    )
                }
                if (!MessageDigest.isEqual(key, Base64.decode(location.hostKey, Base64.NO_WRAP))) {
                    throw SshHostKeyChangedException(fingerprint(key))
                }
            }
            throw IOException("SSH connection or authentication failed; check the host, port and credentials", failure)
        }
    }

    private class PinnedHostKey(private val saved: String) : HostKeyRepository {
        var observed: ByteArray? = null
        override fun check(host: String?, key: ByteArray): Int {
            observed = key.clone()
            return if (saved.isEmpty()) {
                HostKeyRepository.NOT_INCLUDED
            } else if (MessageDigest.isEqual(Base64.decode(saved, Base64.NO_WRAP), key)) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "file-manager pinned host keys"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private companion object {
        fun fingerprint(key: ByteArray) = "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}

/** Identity of a connection: editing host, user or credentials retires the pool. */
private fun connectionSignature(location: RemoteLocation): String = listOf(
    location.host, location.port.toString(), location.username,
    location.password, location.privateKey, location.passphrase, location.hostKey,
).joinToString("\u0000")

/**
 * A pooled session reserved for one operation.
 *
 * Closing recycles the session, or disconnects it when the lease was transient
 * (terminals). Leases always close through `use` or `try/finally`.
 */
internal class SshLease internal constructor(
    val connection: SshConnection,
    private val recycle: () -> Unit,
) : java.io.Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        recycle()
    }
}

/**
 * One connection pool per remote location, shared by metadata, transfers and
 * terminals.
 *
 * A JSch [Session] funnels every channel through one socket reader: while a
 * channel hands data to a consumer that is not draining (a paused preview, a
 * stalled copy) that reader blocks and the whole session stalls with it. So a
 * lease marks its session busy, and a new session is dialed whenever every
 * pooled session is busy. Sessions stay pooled for reuse after release;
 * terminal sessions are destroyed when the terminal closes.
 */
internal object SshConnections {
    /** Upper bound for recycled sessions; past it leases share the least busy. */
    private const val MAX_REUSED = 6

    private class Slot(val connection: SshConnection, var transient: Boolean) {
        var busy = 0
    }

    private class Entry(val signature: String) {
        val slots = ArrayList<Slot>()
    }

    private val entries = HashMap<String, Entry>()

    /** Lease for a file operation; the session stays pooled after [SshLease.close]. */
    fun open(location: RemoteLocation): SshLease {
        val entry = entry(location)
        val slot = synchronized(entry) {
            val reusable = entry.slots.filter { !it.transient }
            val chosen = reusable.firstOrNull { it.busy == 0 } ?: run {
                if (reusable.size < MAX_REUSED) {
                    Slot(SshConnection(location), transient = false).also { entry.slots.add(it) }
                } else {
                    // Every pooled session is busy: share the least loaded one
                    // instead of blocking; only isolation degrades.
                    reusable.minByOrNull { it.busy }
                        ?: Slot(SshConnection(location), transient = false).also { entry.slots.add(it) }
                }
            }
            chosen.busy++
            chosen
        }
        return SshLease(slot.connection) { release(entry, slot) }
    }

    /**
     * Lease whose session is destroyed on close; terminals own it until they
     * exit. An already-warm idle session is reused so a terminal opens without
     * a fresh handshake, and it is dropped instead of recycled afterwards.
     */
    fun terminal(location: RemoteLocation): SshLease {
        val entry = entry(location)
        val slot = synchronized(entry) {
            (entry.slots.firstOrNull { it.busy == 0 }
                ?: Slot(SshConnection(location), transient = true).also { entry.slots.add(it) })
                .also {
                    it.transient = true
                    it.busy++
                }
        }
        return SshLease(slot.connection) { release(entry, slot) }
    }

    @Synchronized fun disconnect(id: String) {
        entries.remove(id)?.let { closeEntry(it) }
    }

    @Synchronized fun disconnectAll() {
        entries.values.forEach { closeEntry(it) }
        entries.clear()
    }

    @Synchronized private fun entry(location: RemoteLocation): Entry {
        val signature = connectionSignature(location)
        entries[location.id]?.takeIf { it.signature == signature }?.let { return it }
        entries.remove(location.id)?.let { closeEntry(it) }
        return Entry(signature).also { entries[location.id] = it }
    }

    private fun release(entry: Entry, slot: Slot) {
        if (slot.transient) {
            synchronized(entry) { entry.slots.remove(slot) }
            slot.connection.close()
            return
        }
        synchronized(entry) { if (slot.busy > 0) slot.busy-- }
    }

    private fun closeEntry(entry: Entry) {
        entry.slots.forEach { it.connection.close() }
        entry.slots.clear()
    }
}
