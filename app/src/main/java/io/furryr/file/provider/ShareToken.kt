package io.furryr.file.provider

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Per-process signing key for forwarded `content://` URIs.
 *
 * The key pair is generated on first use and never persisted, so URIs issued
 * before an app restart stop working. Each call signs the exact path and an
 * expiry, which prevents a leaked URI from being reused for another file.
 */
internal object ShareToken {
    private const val TTL_MS = 10 * 60 * 1000L

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()
    private val keyPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    fun sign(path: String, now: Long = System.currentTimeMillis()): Pair<Long, String> {
        val expires = now + TTL_MS
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload(path, expires))
            sign()
        }
        return expires to encoder.encodeToString(signature)
    }

    fun verify(path: String, expires: Long, token: String, now: Long = System.currentTimeMillis()): Boolean {
        if (expires < now) return false
        return runCatching {
            val signature = decoder.decode(token)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(payload(path, expires))
                verify(signature)
            }
        }.getOrDefault(false)
    }

    private fun payload(path: String, expires: Long): ByteArray =
        "$expires\u0000$path".toByteArray(Charsets.UTF_8)
}
