package io.furryr.file.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import io.furryr.file.provider.RemoteLocationSource
import io.furryr.file.provider.remote.AlistLocation
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.RemoteProviders
import io.furryr.file.provider.remote.RemoteSchemes
import io.furryr.file.provider.remote.SshLocation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted connection records live outside Android backup; keys stay in
 * Android Keystore.
 *
 * An [Identity] is the one credential a remote connection may reference: a
 * password (FTP, SFTP, AList, SSH password auth) or a private key with an
 * optional passphrase (SSH only). Connection records never store the secret
 * itself; [remoteLocations] resolves the referenced identity into the fields
 * the provider consumes.
 */
object RemoteLocationStore : RemoteLocationSource {
    data class Identity(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val password: String = "",
        val privateKey: String = "",
        val passphrase: String = "",
    ) {
        val usesPrivateKey: Boolean get() = privateKey.isNotBlank()
    }

    data class TrustedHost(val host: String, val port: Int, val key: String) {
        val fingerprint: String get() = "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(Base64.decode(key, Base64.NO_WRAP)),
            Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private lateinit var storage: AtomicFile
    private const val ALIAS = "io.furryr.file.remote.locations"

    // Every provider call resolves a connection, and every browse makes several
    // calls. Keep the decrypted records in memory and drop them only when this
    // process writes the store.
    private var cached: List<RemoteLocation>? = null
    private var savedIdentities = emptyList<Identity>()
    private var savedHosts = emptyList<TrustedHost>()

    @Synchronized fun init(context: Context) {
        storage = AtomicFile(File(context.noBackupFilesDir, "remote-locations.enc"))
        cached = null
        savedIdentities = emptyList()
        savedHosts = emptyList()
    }

    @Synchronized override fun remoteLocations(): List<RemoteLocation> {
        load()
        return cached.orEmpty().map { location ->
            when (location) {
                is SshLocation -> {
                    val identity = savedIdentities.firstOrNull { it.id == location.identityId }
                    location.copy(
                        // A migrated identity may hold both; JSch then tries the
                        // key first and falls back to the password.
                        password = identity?.password.orEmpty(),
                        privateKey = identity?.privateKey.orEmpty(),
                        passphrase = identity?.passphrase.orEmpty(),
                        hostKey = savedHosts.firstOrNull {
                            it.host.equals(location.host, ignoreCase = true) && it.port == location.port
                        }?.key.orEmpty(),
                    )
                }
                is AlistLocation -> {
                    val identity = savedIdentities.firstOrNull { it.id == location.identityId }
                    location.copy(password = identity?.password.orEmpty())
                }
                else -> location
            }
        }
    }

    private fun load() {
        if (cached != null) return
        if (!storage.baseFile.exists()) {
            cached = emptyList()
            return
        }
        val envelope = storage.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(envelope.getString("iv"))))
        val text = cipher.doFinal(decode(envelope.getString("data"))).toString(Charsets.UTF_8)
        val legacy = text.trimStart().startsWith('[')
        val root = if (legacy) JSONObject().put("locations", JSONArray(text)) else JSONObject(text)
        savedIdentities = root.optJSONArray("keys")?.let { rows ->
            (0 until rows.length()).map { i -> rows.getJSONObject(i).let { row ->
                Identity(
                    row.getString("id"),
                    row.getString("name"),
                    password = row.optString("password"),
                    privateKey = row.optString("privateKey"),
                    passphrase = row.optString("passphrase"),
                )
            } }
        }.orEmpty()
        savedHosts = root.optJSONArray("hosts")?.let { rows ->
            (0 until rows.length()).map { i -> rows.getJSONObject(i).let { row ->
                TrustedHost(row.getString("host"), row.getInt("port"), row.getString("key"))
            } }
        }.orEmpty()
        val records = root.getJSONArray("locations")
        val stored = (0 until records.length()).mapNotNull { index -> records.getJSONObject(index).let { row ->
            val id = row.getString("id")
            val name = row.getString("name")
            val basePath = row.optString("basePath", "/")
            val sortOrder = row.optInt("sortOrder", 0)
            when (row.optString("scheme")) {
                RemoteSchemes.ALIST -> AlistLocation(
                    id = id, name = name, basePath = basePath, sortOrder = sortOrder,
                    endpoint = row.optString("endpoint"), username = row.optString("username"),
                    password = row.optString("password"), token = row.optString("token"),
                    directoryPassword = row.optString("directoryPassword"),
                    identityId = row.optString("identityId"),
                )
                RemoteSchemes.SSH -> SshLocation(
                    id = id, name = name, basePath = basePath, sortOrder = sortOrder,
                    host = row.optString("host"), port = row.optInt("port", 22),
                    username = row.optString("username"), password = row.optString("password"),
                    privateKey = row.optString("privateKey"), passphrase = row.optString("passphrase"),
                    hostKey = row.optString("hostKey"),
                    identityId = row.optString("identityId")
                        .ifBlank { row.optString("privateKeyId") },
                )
                else -> null
            }
        } }
        val migrated = stored.map(::migrate).sortedBy { it.sortOrder }
        cached = migrated
        // Old records carried secrets inline; rewrite once they have been
        // folded into identities so the file stops duplicating them.
        if (legacy || migrated != stored.sortedBy { it.sortOrder }) write(migrated)
    }

    private fun migrate(location: RemoteLocation): RemoteLocation = when (location) {
        is SshLocation -> {
            var identityId = location.identityId
            if (identityId.isBlank()) {
                identityId = if (location.privateKey.isNotBlank() || location.password.isNotBlank()) {
                    addIdentity(
                        name = location.name,
                        password = location.password,
                        privateKey = location.privateKey,
                        passphrase = location.passphrase,
                    ).id
                } else {
                    ""
                }
            }
            if (location.hostKey.isNotBlank()) {
                val previous = savedHosts.firstOrNull {
                    it.host.equals(location.host, true) && it.port == location.port
                }
                // Never silently replace a trusted key during migration or import.
                require(previous == null || previous.key == location.hostKey) {
                    "Conflicting SSH host keys for ${location.host}:${location.port}"
                }
                if (previous == null) {
                    savedHosts = savedHosts + TrustedHost(
                        location.host.lowercase(Locale.ROOT),
                        location.port,
                        location.hostKey,
                    )
                }
            }
            location.copy(
                identityId = identityId,
                password = "",
                privateKey = "",
                passphrase = "",
                hostKey = "",
            )
        }
        is AlistLocation -> {
            val identityId = if (location.identityId.isBlank() && location.password.isNotBlank()) {
                addIdentity(name = location.name, password = location.password).id
            } else {
                location.identityId
            }
            location.copy(identityId = identityId, password = "")
        }
        else -> location
    }

    @Synchronized fun find(id: String): RemoteLocation = all().firstOrNull { it.id == id }
        ?: error("Remote connection no longer exists; edit the location")

    @Synchronized fun put(location: RemoteLocation) {
        location.validate()
        load()
        val existing = cached.orEmpty()
        val previousIdentities = savedIdentities
        val previousHosts = savedHosts
        try {
            val migrated = migrate(location)
            val identityId = when (migrated) {
                is SshLocation -> migrated.identityId
                is AlistLocation -> migrated.identityId
                else -> ""
            }
            if (identityId.isNotBlank()) {
                require(savedIdentities.any { it.id == identityId }) {
                    "This identity no longer exists; select another one"
                }
                if (migrated is AlistLocation) {
                    require(savedIdentities.first { it.id == identityId }.usesPrivateKey.not()) {
                        "AList connections require a password identity"
                    }
                }
            }
            val ordered = migrated.withSortOrder(
                existing.firstOrNull { it.id == location.id }?.sortOrder
                    ?: ((existing.maxOfOrNull { it.sortOrder } ?: -1) + 1),
            )
            val next = (existing.filterNot { it.id == location.id } + ordered).sortedBy { it.sortOrder }
            write(next)
            cached = next
        } catch (failure: Throwable) {
            savedIdentities = previousIdentities
            savedHosts = previousHosts
            throw failure
        }
    }

    @Synchronized fun remove(id: String) {
        load()
        val next = cached.orEmpty().filterNot { it.id == id }.mapIndexed { index, entry -> entry.withSortOrder(index) }
        write(next)
        cached = next
    }

    @Synchronized fun reorder(ids: List<String>) {
        load()
        val existing = cached.orEmpty()
        val byId = existing.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + existing.filter { it.id !in ids }
        write(ordered.mapIndexed { index, entry -> entry.withSortOrder(index) })
        cached = ordered.mapIndexed { index, entry -> entry.withSortOrder(index) }
    }

    @Synchronized fun all(): List<RemoteLocation> {
        load()
        return cached.orEmpty().toList()
    }

    @Synchronized fun identities(): List<Identity> {
        load()
        return savedIdentities.toList()
    }

    @Synchronized fun identityPassword(id: String): String {
        if (id.isBlank()) return ""
        load()
        return savedIdentities.firstOrNull { it.id == id }?.password.orEmpty()
    }

    fun verifyIdentity(identity: Identity) {
        require(identity.name.isNotBlank()) { "An identity name is required" }
        require(identity.password.isNotBlank() || identity.privateKey.isNotBlank()) {
            "A password or private key is required"
        }
    }

    @Synchronized fun putIdentity(identity: Identity) {
        load()
        verifyIdentity(identity)
        if (identity.usesPrivateKey) {
            require(cached.orEmpty().none { it is AlistLocation && it.identityId == identity.id }) {
                "An AList connection uses this identity as a password"
            }
        }
        val previous = savedIdentities
        savedIdentities = savedIdentities.filterNot { it.id == identity.id } +
            identity.copy(name = identity.name.trim())
        try { write(cached.orEmpty()) } catch (failure: Throwable) {
            savedIdentities = previous
            throw failure
        }
        cached.orEmpty().filter { it.referencesIdentity(identity.id) }
            .forEach { RemoteProviders.forget(it.id) }
    }

    @Synchronized fun removeIdentity(id: String) {
        load()
        require(cached.orEmpty().none { it.referencesIdentity(id) }) {
            "This identity is used by a connection; edit the connection first"
        }
        val previous = savedIdentities
        savedIdentities = savedIdentities.filterNot { it.id == id }
        try { write(cached.orEmpty()) } catch (failure: Throwable) {
            savedIdentities = previous
            throw failure
        }
    }

    /** Table migration/import helper: reuse an equal secret, else append one. */
    @Synchronized fun ensureIdentity(
        name: String,
        password: String = "",
        privateKey: String = "",
        passphrase: String = "",
    ): Identity {
        load()
        val before = savedIdentities
        val created = addIdentity(name, password, privateKey, passphrase)
        if (savedIdentities.size != before.size) {
            try {
                write(cached.orEmpty())
            } catch (failure: Throwable) {
                savedIdentities = before
                throw failure
            }
        }
        return created
    }

    private fun addIdentity(
        name: String,
        password: String = "",
        privateKey: String = "",
        passphrase: String = "",
    ): Identity {
        savedIdentities.firstOrNull {
            it.password == password && it.privateKey == privateKey && it.passphrase == passphrase
        }?.let { return it }
        val created = Identity(
            name = uniqueIdentityName(name.ifBlank { "Identity" }),
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
        )
        savedIdentities = savedIdentities + created
        return created
    }

    private fun uniqueIdentityName(base: String): String {
        val trimmed = base.trim().ifBlank { "Identity" }
        val names = savedIdentities.map { it.name }.toSet()
        if (trimmed !in names) return trimmed
        var index = 1
        while ("$trimmed ($index)" in names) index++
        return "$trimmed ($index)"
    }

    @Synchronized fun trustedHosts(): List<TrustedHost> {
        load()
        return savedHosts.toList()
    }

    @Synchronized fun trustHost(host: TrustedHost) {
        load()
        require(host.host.isNotBlank() && host.port in 1..65535 && decode(host.key).isNotEmpty()) { "Invalid SSH host key" }
        val previous = savedHosts
        savedHosts = savedHosts.filterNot { it.host.equals(host.host, true) && it.port == host.port } +
            host.copy(host = host.host.lowercase(Locale.ROOT))
        try { write(cached.orEmpty()) } catch (failure: Throwable) { savedHosts = previous; throw failure }
        cached.orEmpty().filterIsInstance<SshLocation>().filter {
            it.host.equals(host.host, true) && it.port == host.port
        }.forEach { RemoteProviders.forget(it.id) }
    }

    @Synchronized fun removeHost(host: String, port: Int) {
        load()
        val previous = savedHosts
        savedHosts = savedHosts.filterNot { it.host.equals(host, true) && it.port == port }
        try { write(cached.orEmpty()) } catch (failure: Throwable) { savedHosts = previous; throw failure }
        cached.orEmpty().filterIsInstance<SshLocation>().filter {
            it.host.equals(host, true) && it.port == port
        }.forEach { RemoteProviders.forget(it.id) }
    }

    private fun RemoteLocation.referencesIdentity(id: String): Boolean = when (this) {
        is SshLocation -> identityId == id
        is AlistLocation -> identityId == id
        else -> false
    }

    private fun write(locations: List<RemoteLocation>) {
        val records = JSONArray()
        locations.forEach { entry -> records.put(JSONObject().apply {
            put("id", entry.id); put("name", entry.name); put("scheme", entry.scheme)
            put("basePath", entry.basePath); put("sortOrder", entry.sortOrder)
            when (entry) {
                is AlistLocation -> {
                    put("endpoint", entry.endpoint); put("username", entry.username)
                    put("token", entry.token)
                    put("directoryPassword", entry.directoryPassword)
                    put("identityId", entry.identityId)
                }
                is SshLocation -> {
                    put("host", entry.host); put("port", entry.port); put("username", entry.username)
                    put("identityId", entry.identityId)
                }
            }
        }) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = JSONObject().put("locations", records)
            .put("keys", JSONArray().apply { savedIdentities.forEach { identity ->
                put(JSONObject().put("id", identity.id).put("name", identity.name)
                    .put("password", identity.password)
                    .put("privateKey", identity.privateKey).put("passphrase", identity.passphrase))
            } })
            .put("hosts", JSONArray().apply { savedHosts.forEach { host ->
                put(JSONObject().put("host", host.host).put("port", host.port).put("key", host.key))
            } })
        val envelope = JSONObject().put("iv", encode(cipher.iv))
            .put("data", encode(cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))))
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
