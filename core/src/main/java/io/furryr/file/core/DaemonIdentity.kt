package io.furryr.file.core

import android.content.Context
import android.util.Log
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The app's Ed25519 identity, shared with every daemon it launches.
 *
 * The private key never leaves app-private storage; only the 32-byte public key
 * is handed to a daemon (`--key`). Both sides derive the daemon's abstract
 * socket name from the public key and a target id, which makes the address
 * stable and reattachable — but since the public key is not a secret, every
 * connection must still pass the [DaemonAuth] handshake.
 */
internal object DaemonIdentity {
    private const val TAG = "DaemonIdentity"
    private const val SEED_FILE = "daemon-identity.seed"
    private const val SEED_LENGTH = 32

    /** Domain separator, byte-identical to the native `auth::AUTH_CONTEXT`. */
    private const val AUTH_CONTEXT = "file-daemon-auth-v1\u0000"

    private val spec: EdDSAParameterSpec =
        EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    @Volatile private var privateKey: EdDSAPrivateKey? = null
    @Volatile private var publicKey: ByteArray? = null

    fun init(context: Context) {
        if (privateKey != null) return
        synchronized(this) {
            if (privateKey != null) return
            val seed = loadOrCreateSeed(File(context.filesDir, SEED_FILE))
            val privateKeySpec = EdDSAPrivateKeySpec(seed, spec)
            val key = EdDSAPrivateKey(privateKeySpec)
            val public = EdDSAPublicKey(EdDSAPublicKeySpec(privateKeySpec.a, spec)).abyte
            privateKey = key
            publicKey = public
            Log.i(TAG, "Daemon identity ready (pubkey ${toHex(public).take(16)}…)")
        }
    }

    /** Hex-encoded public key, as passed to a daemon via `--key`. */
    fun publicKeyHex(): String = toHex(requirePublic())

    /**
     * Stable abstract socket name for [id]. Mirrors `auth::derive_socket_name`
     * in the native daemon exactly.
     */
    fun socketName(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(requirePublic())
        digest.update(0)
        digest.update(id.toByteArray(Charsets.UTF_8))
        return "file-daemon-${toHex(digest.digest())}"
    }

    /** The exact message a daemon expects a client to sign for authentication. */
    fun authMessage(socketName: String, nonce: ByteArray): ByteArray {
        val context = AUTH_CONTEXT.toByteArray(Charsets.UTF_8)
        val name = socketName.toByteArray(Charsets.UTF_8)
        val message = ByteArray(context.size + name.size + 1 + nonce.size)
        var offset = 0
        context.copyInto(message, offset)
        offset += context.size
        name.copyInto(message, offset)
        offset += name.size
        message[offset] = 0
        nonce.copyInto(message, offset + 1)
        return message
    }

    /** Sign [message] with the app private key. */
    fun sign(message: ByteArray): ByteArray {
        val key = privateKey ?: throw IllegalStateException("DaemonIdentity.init() not called")
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initSign(key)
        engine.update(message)
        return engine.sign()
    }

    private fun requirePublic(): ByteArray =
        publicKey ?: throw IllegalStateException("DaemonIdentity.init() not called")

    private fun loadOrCreateSeed(file: File): ByteArray {
        if (file.exists()) {
            val existing = runCatching { file.readBytes() }.getOrNull()
            if (existing != null && existing.size == SEED_LENGTH) return existing
            Log.w(TAG, "Ignoring malformed identity seed at ${file.absolutePath}")
        }
        val seed = ByteArray(SEED_LENGTH)
        SecureRandom().nextBytes(seed)
        file.writeBytes(seed)
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
        return seed
    }

    private fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }
}
