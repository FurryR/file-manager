package io.furryr.file.provider.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted connection records live outside Android backup; keys stay in Android Keystore. */
object RemoteLocationStore {
    private lateinit var storage: AtomicFile
    private const val ALIAS = "io.furryr.file.remote.locations"
    @Synchronized fun init(context: Context) { storage = AtomicFile(File(context.noBackupFilesDir, "remote-locations.enc")) }

    @Synchronized fun all(): List<RemoteLocation> {
        if (!storage.baseFile.exists()) return emptyList()
        val envelope = storage.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(envelope.getString("iv"))))
        val records = JSONArray(cipher.doFinal(decode(envelope.getString("data"))).toString(Charsets.UTF_8))
        return (0 until records.length()).map { index -> records.getJSONObject(index).let { row ->
            RemoteLocation(
                id = row.getString("id"), name = row.getString("name"), kind = RemoteKind.valueOf(row.getString("kind")),
                basePath = row.optString("basePath", "/"), endpoint = row.optString("endpoint"), host = row.optString("host"),
                port = row.optInt("port", 22), username = row.optString("username"), password = row.optString("password"),
                privateKey = row.optString("privateKey"), passphrase = row.optString("passphrase"), token = row.optString("token"),
                directoryPassword = row.optString("directoryPassword"), hostKey = row.optString("hostKey"),
                sortOrder = row.optInt("sortOrder", 0),
            )
        } }.sortedBy { it.sortOrder }
    }
    @Synchronized fun find(id: String): RemoteLocation = all().firstOrNull { it.id == id }
        ?: error("Remote connection no longer exists; edit the location")
    @Synchronized fun put(location: RemoteLocation) {
        location.validate()
        val existing = all()
        val ordered = location.copy(
            sortOrder = existing.firstOrNull { it.id == location.id }?.sortOrder
                ?: ((existing.maxOfOrNull { it.sortOrder } ?: -1) + 1),
        )
        write(existing.filterNot { it.id == location.id } + ordered)
    }
    @Synchronized fun remove(id: String) {
        write(all().filterNot { it.id == id }.mapIndexed { index, entry -> entry.copy(sortOrder = index) })
    }
    @Synchronized fun reorder(ids: List<String>) {
        val existing = all()
        val byId = existing.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + existing.filter { it.id !in ids }
        write(ordered.mapIndexed { index, entry -> entry.copy(sortOrder = index) })
    }

    private fun write(locations: List<RemoteLocation>) {
        val records = JSONArray()
        locations.forEach { entry -> records.put(JSONObject().apply {
            put("id", entry.id); put("name", entry.name); put("kind", entry.kind.name); put("basePath", entry.basePath)
            put("endpoint", entry.endpoint); put("host", entry.host); put("port", entry.port); put("username", entry.username)
            put("password", entry.password); put("privateKey", entry.privateKey); put("passphrase", entry.passphrase)
            put("token", entry.token); put("directoryPassword", entry.directoryPassword); put("hostKey", entry.hostKey)
            put("sortOrder", entry.sortOrder)
        }) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val envelope = JSONObject().put("iv", encode(cipher.iv))
            .put("data", encode(cipher.doFinal(records.toString().toByteArray(Charsets.UTF_8))))
        val output = storage.startWrite()
        try { output.write(envelope.toString().toByteArray(Charsets.UTF_8)); storage.finishWrite(output) }
        catch (failure: Throwable) { storage.failWrite(output); throw failure }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
}
