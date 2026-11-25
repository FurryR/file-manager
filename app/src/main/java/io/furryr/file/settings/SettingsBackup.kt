package io.furryr.file.settings

import android.content.Context
import io.furryr.file.provider.model.FtpServer
import io.furryr.file.provider.model.SafLocation
import io.furryr.file.provider.model.SftpServer
import io.furryr.file.provider.remote.AlistLocation
import io.furryr.file.provider.remote.RemoteLocation
import io.furryr.file.provider.remote.RemoteSchemes
import io.furryr.file.provider.remote.SshLocation
import io.furryr.file.store.IdentityRepository
import io.furryr.file.store.LocationStores
import io.furryr.file.store.RemoteLocationStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App configuration backup. It flattens the preference files the settings
 * screens own into one JSON document; importing merges the stored keys back.
 *
 * Locations whose records live in SQLite or an encrypted file travel under
 * `locations`, and the agent's chat records (files, not preferences) under
 * `sessions` (one entry per `agent/sessions/<id>.json`).
 */
object SettingsBackup {
    private const val VERSION = 1
    private const val SESSIONS = "agent/sessions"
    private val PREF_FILES = listOf(
        "settings",
        "agent_settings",
        "lsp_settings",
        "search_settings",
        "adblock_settings",
        "local_locations",
        "web_locations",
    )

    fun export(context: Context): String {
        val prefsJson = JSONObject()
        for (name in PREF_FILES) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                values.put(key, encode(value))
            }
            prefsJson.put(name, values)
        }
        val sessionsJson = JSONObject()
        for (file in File(context.filesDir, SESSIONS).listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            sessionsJson.put(file.name, body)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("prefs", prefsJson)
            .put("locations", exportLocations())
            .put("sessions", sessionsJson)
            .toString(2)
    }

    fun import(context: Context, text: String): Result<Unit> = runCatching {
        val root = JSONObject(text)
        require(root.getInt("version") == VERSION) { "Unsupported configuration version" }
        val prefsJson = root.getJSONObject("prefs")
        for (name in PREF_FILES) {
            val values = prefsJson.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (key in values.keys()) {
                val entry = values.getJSONObject(key)
                when (entry.getString("type")) {
                    "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "int" -> editor.putInt(key, entry.getInt("value"))
                    "long" -> editor.putLong(key, entry.getLong("value"))
                    "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                    "string" -> editor.putString(key, entry.getString("value"))
                    "string_set" -> {
                        val array = entry.getJSONArray("value")
                        val set = buildSet {
                            for (index in 0 until array.length()) add(array.getString(index))
                        }
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.commit()
        }
        root.optJSONObject("locations")?.let(::importLocations)
        val sessionsJson = root.optJSONObject("sessions") ?: return@runCatching
        val directory = File(context.filesDir, SESSIONS)
        directory.mkdirs()
        for (name in sessionsJson.keys()) {
            val safe = File(name).name
            if (!safe.endsWith(".json") || safe != name) continue
            val body = sessionsJson.optString(name)
            if (body.isBlank()) continue
            File(directory, safe).writeText(body)
        }
    }

    // ── Locations stored outside preferences ─────────────────────────

    private fun exportLocations(): JSONObject = JSONObject()
        .put("saf", JSONArray().apply {
            for (item in LocationStores.saf.getAll()) {
                put(
                    JSONObject()
                        .put("hex_id", item.hexId)
                        .put("name", item.name)
                        .put("tree_uri", item.treeUri)
                        .put("sort_order", item.sortOrder),
                )
            }
        })
        .put("ftp", JSONArray().apply {
            for (item in LocationStores.ftp.getAll()) put(serverJson(item))
        })
        .put("sftp", JSONArray().apply {
            for (item in LocationStores.sftp.getAll()) put(serverJson(item))
        })
        .put("remote", JSONArray().apply {
            for (item in RemoteLocationStore.all()) put(remoteJson(item))
        })
        .put("identities", JSONArray().apply {
            for (identity in IdentityRepository.identities()) put(JSONObject()
                .put("id", identity.id).put("name", identity.name)
                .put("password", identity.password)
                .put("private_key", identity.privateKey).put("passphrase", identity.passphrase))
        })
        .put("ssh_hosts", JSONArray().apply {
            for (host in RemoteLocationStore.trustedHosts()) put(JSONObject()
                .put("host", host.host).put("port", host.port).put("key", host.key))
        })

    private fun importLocations(locations: JSONObject) {
        // An older backup calls identities "ssh_keys"; accept both names.
        val identities = locations.optJSONArray("identities") ?: locations.optJSONArray("ssh_keys")
        identities?.let { rows ->
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                RemoteLocationStore.putIdentity(RemoteLocationStore.Identity(
                    id = row.getString("id"), name = row.getString("name"),
                    password = row.optString("password"),
                    privateKey = row.optString("private_key"), passphrase = row.optString("passphrase"),
                ))
            }
        }
        locations.optJSONArray("ssh_hosts")?.let { rows ->
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val host = RemoteLocationStore.TrustedHost(row.getString("host"), row.getInt("port"), row.getString("key"))
                val saved = RemoteLocationStore.trustedHosts().firstOrNull {
                    it.host.equals(host.host, true) && it.port == host.port
                }
                require(saved == null || saved.key == host.key) { "Conflicting SSH host keys for ${host.host}:${host.port}" }
                RemoteLocationStore.trustHost(host)
            }
        }
        locations.optJSONArray("saf")?.let { rows ->
            val restored = mutableListOf<SafLocation>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val treeUri = row.optString("tree_uri")
                val hexId = row.optString("hex_id")
                if (treeUri.isBlank() || hexId.isBlank()) continue
                restored.add(
                    SafLocation(
                        hexId = hexId,
                        name = row.optString("name").ifBlank { hexId },
                        treeUri = treeUri,
                        sortOrder = row.optInt("sort_order", index),
                    ),
                )
            }
            if (restored.isNotEmpty()) LocationStores.saf.restore(restored)
        }
        locations.optJSONArray("ftp")?.let { rows ->
            val restored = mutableListOf<FtpServer>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val host = row.optString("host")
                if (host.isBlank()) continue
                restored.add(
                    FtpServer(
                        host = host,
                        name = row.optString("name").ifBlank { host },
                        port = row.optInt("port", 21),
                        username = row.optString("username"),
                        password = row.optString("password"),
                        sortOrder = row.optInt("sort_order", index),
                        identityId = row.optString("identity_id"),
                    ),
                )
            }
            if (restored.isNotEmpty()) LocationStores.ftp.restore(restored)
        }
        locations.optJSONArray("sftp")?.let { rows ->
            val restored = mutableListOf<SftpServer>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val host = row.optString("host")
                if (host.isBlank()) continue
                restored.add(
                    SftpServer(
                        host = host,
                        name = row.optString("name").ifBlank { host },
                        port = row.optInt("port", 22),
                        username = row.optString("username"),
                        password = row.optString("password"),
                        sortOrder = row.optInt("sort_order", index),
                        identityId = row.optString("identity_id"),
                    ),
                )
            }
            if (restored.isNotEmpty()) LocationStores.sftp.restore(restored)
        }
        // An old backup's FTP/SFTP rows carry an inline password; fold it into
        // an identity like the on-device migration does.
        LocationStores.migrateServerIdentities()
        locations.optJSONArray("remote")?.let { rows ->
            val ids = mutableListOf<String>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val location = runCatching { remote(row, index) }.getOrNull() ?: continue
                runCatching { RemoteLocationStore.put(location) }.onSuccess { ids.add(location.id) }
            }
            if (ids.isNotEmpty()) RemoteLocationStore.reorder(ids)
        }
    }

    private fun serverJson(item: FtpServer): JSONObject = JSONObject()
        .put("host", item.host)
        .put("name", item.name)
        .put("port", item.port)
        .put("username", item.username)
        .put("password", item.password)
        .put("identity_id", item.identityId)
        .put("sort_order", item.sortOrder)

    private fun serverJson(item: SftpServer): JSONObject = JSONObject()
        .put("host", item.host)
        .put("name", item.name)
        .put("port", item.port)
        .put("username", item.username)
        .put("password", item.password)
        .put("identity_id", item.identityId)
        .put("sort_order", item.sortOrder)

    private fun remoteJson(item: RemoteLocation): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("name", item.name)
        put("scheme", item.scheme)
        put("base_path", item.basePath)
        put("sort_order", item.sortOrder)
        when (item) {
            is AlistLocation -> {
                put("endpoint", item.endpoint)
                put("username", item.username)
                put("token", item.token)
                put("directory_password", item.directoryPassword)
                put("identity_id", item.identityId)
            }
            is SshLocation -> {
                put("host", item.host)
                put("port", item.port)
                put("username", item.username)
                put("identity_id", item.identityId)
            }
        }
    }

    private fun remote(row: JSONObject, index: Int): RemoteLocation {
        val id = row.getString("id")
        val name = row.getString("name")
        val basePath = row.optString("base_path", "/")
        val sortOrder = row.optInt("sort_order", index)
        return when (row.getString("scheme")) {
            RemoteSchemes.ALIST -> AlistLocation(
                id = id, name = name, basePath = basePath, sortOrder = sortOrder,
                endpoint = row.optString("endpoint"), username = row.optString("username"),
                password = row.optString("password"), token = row.optString("token"),
                directoryPassword = row.optString("directory_password"),
                identityId = row.optString("identity_id"),
            )
            RemoteSchemes.SSH -> SshLocation(
                id = id, name = name, basePath = basePath, sortOrder = sortOrder,
                host = row.optString("host"), port = row.optInt("port", 22),
                username = row.optString("username"), password = row.optString("password"),
                privateKey = row.optString("private_key"), passphrase = row.optString("passphrase"),
                hostKey = row.optString("host_key"),
                identityId = row.optString("identity_id")
                    .ifBlank { row.optString("private_key_id") },
            )
            else -> error("Unknown remote scheme")
        }
    }

    private fun encode(value: Any?): JSONObject = when (value) {
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is String -> JSONObject().put("type", "string").put("value", value)
        is Set<*> -> JSONObject()
            .put("type", "string_set")
            .put("value", JSONArray(value.map { it.toString() }))
        else -> JSONObject().put("type", "string").put("value", value.toString())
    }
}
