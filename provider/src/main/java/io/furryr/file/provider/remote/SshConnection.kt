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
 * One reusable SSH session for a remote location.
 *
 * File operations open a short-lived channel on the shared [Session]; the
 * terminal keeps its own shell channel on the same session. A dead session is
 * replaced once per open before the caller observes the failure.
 */
internal class SshConnection(private val location: RemoteLocation) {
    private var session: Session? = null
    private val signature = connectionSignature(location)

    fun matches(other: RemoteLocation): Boolean = connectionSignature(other) == signature

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
        session.setPassword(location.password)
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
        fun connectionSignature(location: RemoteLocation): String = listOf(
            location.host, location.port.toString(), location.username,
            location.password, location.privateKey, location.passphrase, location.hostKey,
        ).joinToString("\u0000")

        fun fingerprint(key: ByteArray) = "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}

/** Shared per-remote-location connections, so files and terminals reuse one SSH session. */
internal object SshConnections {
    private val connections = HashMap<String, SshConnection>()

    @Synchronized fun get(location: RemoteLocation): SshConnection {
        connections[location.id]?.takeIf { it.matches(location) }?.let { return it }
        connections.remove(location.id)?.close()
        return SshConnection(location).also { connections[location.id] = it }
    }

    @Synchronized fun disconnect(id: String) {
        connections.remove(id)?.close()
    }

    @Synchronized fun disconnectAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
