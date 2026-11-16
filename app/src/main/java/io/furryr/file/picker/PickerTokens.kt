package io.furryr.file.picker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One document address handed to another app. [access] is `r` or `rw`. */
data class PickerDoc(
    val worldId: String,
    val access: String,
    val path: String,
) {
    val writable: Boolean get() = access == "rw"
}

/**
 * Persistent, tamper-evident document ids for [PickerDocumentsProvider].
 *
 * The key lives in app-private storage (not the in-memory share key), so a
 * granted document URI keeps working after the picker activity, and after the
 * process restarts. There is deliberately no expiry: the system's URI grant is
 * the revocable capability, and the id only names the world, address and
 * access mode.
 */
object PickerTokens {
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val SEPARATOR = '.'

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    @Volatile private var key: ByteArray? = null

    @Synchronized
    fun install(file: File) {
        if (key != null) return
        key = load(file) ?: create(file)
    }

    fun issue(doc: PickerDoc): String {
        require(doc.access == "r" || doc.access == "rw") { "invalid access: ${doc.access}" }
        require(doc.path.isNotBlank()) { "empty document path" }
        val payload = encodePayload(doc)
        val signature = sign(payload)
        return encoder.encodeToString(payload) + SEPARATOR + encoder.encodeToString(signature)
    }

    fun verify(token: String): PickerDoc? = runCatching {
        val dot = token.lastIndexOf(SEPARATOR)
        if (dot <= 0 || dot == token.lastIndex) return null
        val payload = decoder.decode(token.substring(0, dot))
        val signature = decoder.decode(token.substring(dot + 1))
        if (!MessageDigest.isEqual(sign(payload), signature)) return null
        decodePayload(payload)
    }.getOrNull()

    private fun sign(payload: ByteArray): ByteArray {
        val secret = key ?: error("PickerTokens.install() was not called")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload)
    }

    private fun encodePayload(doc: PickerDoc): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            writeString(out, doc.worldId)
            writeString(out, doc.access)
            writeString(out, doc.path)
        }
        return bytes.toByteArray()
    }

    private fun decodePayload(payload: ByteArray): PickerDoc? {
        val input = DataInputStream(ByteArrayInputStream(payload))
        if (input.readInt() != VERSION) return null
        val worldId = readString(input)
        val access = readString(input)
        val path = readString(input)
        if (worldId.isBlank() || path.isBlank()) return null
        if (access != "r" && access != "rw") return null
        return PickerDoc(worldId, access, path)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..1_048_576) { "invalid string size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

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
