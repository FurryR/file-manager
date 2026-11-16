package io.furryr.file.forward

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Persistent signing key for forwarded `content://` URIs.
 *
 * Share URIs used to die with the process (and after ten minutes). Both rules
 * made a handed-over file unusable as soon as the receiving app was resumed
 * later. The key now lives in app-private storage next to the picker key and
 * the payload carries no expiry: a shared URI keeps working after the activity,
 * after the process restarts and after the receiver stores it. Clearing app
 * data deletes the key and invalidates every old URI.
 *
 * The HMAC covers the whole payload, so a token cannot be moved to another
 * path, world or referer. The system's URI grant remains the capability that
 * decides whether another app may read it.
 */
internal object ShareToken {
    private const val VERSION = 1
    private const val KEY_BYTES = 32

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    @Volatile private var key: ByteArray? = null

    @Synchronized
    fun install(file: File) {
        if (key != null) return
        key = load(file) ?: create(file)
    }

    fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret(), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    fun verify(payload: String, token: String): Boolean = runCatching {
        val signature = decoder.decode(token)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret(), "HmacSHA256"))
        MessageDigest.isEqual(mac.doFinal(payload.toByteArray(Charsets.UTF_8)), signature)
    }.getOrDefault(false)

    private fun secret(): ByteArray = key ?: error("ShareToken.install() was not called")

    private fun load(file: File): ByteArray? = runCatching {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 1 + KEY_BYTES || bytes[0].toInt() != VERSION) return null
        bytes.copyOfRange(1, bytes.size)
    }.getOrNull()?.takeIf { it.size >= KEY_BYTES }

    private fun create(file: File): ByteArray {
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.outputStream().use { out ->
            out.write(VERSION)
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            file.outputStream().use { out ->
                out.write(VERSION)
                out.write(bytes)
                out.fd.sync()
            }
            temp.delete()
        }
        return bytes
    }
}
