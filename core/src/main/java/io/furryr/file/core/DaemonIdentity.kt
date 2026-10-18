package io.furryr.file.core

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * An in-memory Ed25519 identity belonging to one daemon launch.
 *
 * Generated for each launch, never read from or written to disk. Only the
 * 32-byte public key is handed to a daemon (`--key`). Socket names derive from
 * that key and the target id; every connection still needs [DaemonAuth].
 */
internal class DaemonIdentity(val id: String) {
    private val spec: EdDSAParameterSpec =
        EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    private val privateKey: EdDSAPrivateKey
    private val publicKey: ByteArray
    val socketName: String

    init {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val privateKeySpec = EdDSAPrivateKeySpec(seed, spec)
        privateKey = EdDSAPrivateKey(privateKeySpec)
        publicKey = EdDSAPublicKey(EdDSAPublicKeySpec(privateKeySpec.a, spec)).abyte
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(publicKey)
        digest.update(0)
        digest.update(id.toByteArray(Charsets.UTF_8))
        socketName = "file-daemon-${toHex(digest.digest())}"
    }

    /** Hex-encoded public key, as passed to a daemon via `--key`. */
    fun publicKeyHex(): String = toHex(publicKey)

    /** The exact message a daemon expects a client to sign for authentication. */
    fun authMessage(nonce: ByteArray): ByteArray {
        // Domain separator, byte-identical to the native auth::AUTH_CONTEXT.
        val context = "file-daemon-auth-v1\u0000".toByteArray(Charsets.UTF_8)
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

    /** Sign [message] with this launch's private key. */
    fun sign(message: ByteArray): ByteArray {
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initSign(privateKey)
        engine.update(message)
        return engine.sign()
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
