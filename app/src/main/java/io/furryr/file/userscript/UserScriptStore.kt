package io.furryr.file.userscript

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * File-backed user-script store. Script bodies, `@require`/`@resource` data and
 * GM values are ordinary files under `filesDir/userscripts/`, so the built-in
 * editor can open and change a script in place. Metadata stays in
 * `SharedPreferences("userscript_settings")`.
 */
object UserScriptStore {
    private const val PREFS = "userscript_settings"
    private const val KEY_SCRIPTS = "scripts"

    private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024
    private const val MAX_REQUIRE_BYTES = 4 * 1024 * 1024
    private const val MAX_RESOURCE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "File/1.0 (Android) Userscripts"

    /** Number of installed scripts, for the settings row. */
    fun count(context: Context): Int =
        runCatching { JSONArray(prefs(context).getString(KEY_SCRIPTS, "[]")).length() }
            .getOrDefault(0)

    /** Public so the settings row can open the source in the editor. */
    fun scriptPath(context: Context, id: String): File = scriptFile(context, id)

    fun scripts(context: Context): List<UserScript> = readScripts(context)

    fun script(context: Context, id: String): UserScript? =
        readScripts(context).firstOrNull { it.id == id }

    /** Download and parse a script without touching installed rows. */
    fun fetch(sourceUrl: String): UserScriptDraft {
        val url = sourceUrl.trim()
        require(UserScriptPattern.isHttpUrl(url)) { "not an http(s) URL" }
        val bytes = download(url, MAX_SCRIPT_BYTES)
        return UserScriptParser.parse(bytes.toString(Charsets.UTF_8), url)
    }

    /** Read a script from a picker document; it has no update URL. */
    fun read(context: Context, uri: Uri): UserScriptDraft {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            readLimited(it, MAX_SCRIPT_BYTES)
        } ?: error("cannot read the selected file")
        return UserScriptParser.parse(bytes.toString(Charsets.UTF_8), "")
    }

    /** Write a blank, editable template and register its metadata. */
    @Synchronized
    fun createBlank(context: Context, name: String, description: String): UserScript {
        val now = System.currentTimeMillis()
        val id = "us-${UUID.randomUUID()}"
        val body = buildString {
            appendLine("// ==UserScript==")
            appendLine("// @name         $name")
            appendLine("// @namespace    local")
            appendLine("// @version      1.0.0")
            appendLine("// @description  $description")
            appendLine("// @match        https://example.com/*")
            appendLine("// @run-at       document-idle")
            appendLine("// @grant        none")
            appendLine("// ==/UserScript==")
            appendLine()
            appendLine("(function () {")
            appendLine("    'use strict';")
            appendLine()
            appendLine("    // Write your script here.")
            appendLine("})();")
        }
        val script = UserScript(
            id = id,
            name = name,
            namespace = "local",
            version = "1.0.0",
            description = description,
            sourceUrl = "",
            enabled = true,
            runAt = UserScriptRunAt.DOCUMENT_IDLE,
            matches = listOf("https://example.com/*"),
            includes = emptyList(),
            excludes = emptyList(),
            grants = listOf("none"),
            connects = emptyList(),
            body = body,
            installedAt = now,
            updatedAt = now,
        )
        writeFile(scriptFile(context, id), body.toByteArray(Charsets.UTF_8))
        saveMetadata(context, readScripts(context) + script)
        return script
    }

    /** The installed script this draft would replace, if any. */
    fun conflict(context: Context, draft: UserScriptDraft): UserScript? =
        readScripts(context).firstOrNull { existing ->
            (draft.sourceUrl.isNotBlank() &&
                existing.sourceUrl.equals(draft.sourceUrl, ignoreCase = true)) ||
                (
                    draft.metadata.namespace.isNotBlank() &&
                        existing.namespace.equals(draft.metadata.namespace, ignoreCase = true) &&
                        existing.name.equals(draft.metadata.name, ignoreCase = true)
                    )
        }

    /**
     * Fetch every `@require`/`@resource`, write the body files, then update the
     * metadata list. [replacingId] keeps the id, enable flag and install time.
     */
    @Synchronized
    fun install(context: Context, draft: UserScriptDraft, replacingId: String? = null): UserScript {
        val installed = readScripts(context)
        val existing = replacingId?.let { id -> installed.firstOrNull { it.id == id } }
        val sourceUrl = draft.sourceUrl
        val requires = draft.metadata.requires.map { raw ->
            val url = resolve(sourceUrl, raw)
            val bytes = download(url, MAX_REQUIRE_BYTES)
            UserScriptRequire(url, sha256(bytes), bytes.toString(Charsets.UTF_8))
        }
        val resources = draft.metadata.resources.map { (name, raw) ->
            val url = resolve(sourceUrl, raw)
            val bytes = download(url, MAX_RESOURCE_BYTES)
            UserScriptResource(name, url, sha256(bytes), bytes)
        }
        val incoming = draft.body.toByteArray(Charsets.UTF_8).size +
            requires.sumOf { it.body.toByteArray(Charsets.UTF_8).size } +
            resources.sumOf { it.body.size }
        val used = installed.filter { it.id != existing?.id }.sumOf { totalBytes(context, it.id) }
        require(used + incoming <= MAX_TOTAL_BYTES) { "user script storage is full" }

        val now = System.currentTimeMillis()
        val script = UserScript(
            id = existing?.id ?: "us-${UUID.randomUUID()}",
            name = draft.metadata.name,
            namespace = draft.metadata.namespace,
            version = draft.metadata.version,
            description = draft.metadata.description,
            sourceUrl = sourceUrl,
            enabled = existing?.enabled ?: true,
            runAt = draft.metadata.runAt,
            matches = draft.metadata.matches,
            includes = draft.metadata.includes,
            excludes = draft.metadata.excludes,
            grants = draft.metadata.grants,
            connects = draft.metadata.connects,
            body = draft.body,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now,
        )
        writeFile(scriptFile(context, script.id), draft.body.toByteArray(Charsets.UTF_8))
        writeParts(context, script.id, requires, resources)
        saveMetadata(context, installed.filterNot { it.id == script.id } + script)
        return script
    }

    fun update(context: Context, id: String): UserScript {
        val current = script(context, id) ?: error("script not found")
        require(current.sourceUrl.isNotBlank()) { "script has no source URL" }
        return install(context, fetch(current.sourceUrl), replacingId = id)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val scripts = readScripts(context)
        if (scripts.none { it.id == id }) return
        saveMetadata(context, scripts.filterNot { it.id == id })
        scriptFile(context, id).delete()
        requireDirectory(context, id).deleteRecursively()
        resourceDirectory(context, id).deleteRecursively()
        valuesFile(context, id).delete()
    }

    fun setScriptEnabled(context: Context, id: String, enabled: Boolean) {
        synchronized(this) {
            val scripts = readScripts(context)
            if (scripts.none { it.id == id }) return
            saveMetadata(context, scripts.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    fun value(context: Context, scriptId: String, key: String): String? =
        synchronized(this) { readValues(context, scriptId)[key] }

    fun setValue(context: Context, scriptId: String, key: String, value: String) {
        synchronized(this) {
            val values = readValues(context, scriptId).toMutableMap()
            values[key] = value
            writeValues(context, scriptId, values)
        }
    }

    fun deleteValue(context: Context, scriptId: String, key: String) {
        synchronized(this) {
            val values = readValues(context, scriptId).toMutableMap()
            if (values.remove(key) != null) writeValues(context, scriptId, values)
        }
    }

    fun values(context: Context, scriptId: String): Map<String, String> =
        synchronized(this) { readValues(context, scriptId) }

    fun requires(context: Context, scriptId: String): List<UserScriptRequire> {
        val root = requireDirectory(context, scriptId)
        val manifest = readManifest(File(root, MANIFEST))
        return manifest.mapNotNull { item ->
            val name = item.optString("file")
            val body = File(root, name)
            if (!body.isFile) return@mapNotNull null
            UserScriptRequire(
                url = item.optString("url"),
                sha256 = item.optString("sha256"),
                body = runCatching { body.readText() }.getOrNull() ?: return@mapNotNull null,
            )
        }
    }

    fun resources(context: Context, scriptId: String): List<UserScriptResource> {
        val root = resourceDirectory(context, scriptId)
        val manifest = readManifest(File(root, MANIFEST))
        return manifest.mapNotNull { item ->
            val name = item.optString("name")
            val body = File(root, item.optString("file"))
            if (!body.isFile) return@mapNotNull null
            UserScriptResource(
                name = name,
                url = item.optString("url"),
                sha256 = item.optString("sha256"),
                body = runCatching { body.readBytes() }.getOrNull() ?: return@mapNotNull null,
            )
        }
    }

    fun resource(context: Context, scriptId: String, name: String): UserScriptResource? =
        resources(context, scriptId).firstOrNull { it.name == name }

    /** Newest mtime under scripts/requires/resources, used to notice edits. */
    fun latestModified(context: Context): Long {
        val root = userscriptRoot(context)
        return maxOf(
            newest(File(root, "scripts")),
            newest(File(root, "requires")),
            newest(File(root, "resources")),
        )
    }

    private fun newest(root: File): Long {
        if (!root.exists()) return 0L
        var newest = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (!file.exists()) continue
            if (file.lastModified() > newest) newest = file.lastModified()
            if (file.isDirectory) file.listFiles()?.forEach(stack::add)
        }
        return newest
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readScripts(context: Context): List<UserScript> {
        val raw = prefs(context).getString(KEY_SCRIPTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val body = runCatching { scriptFile(context, id).readText() }.getOrNull() ?: continue
                add(
                    UserScript(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        namespace = item.optString("namespace"),
                        version = item.optString("version"),
                        description = item.optString("description"),
                        sourceUrl = item.optString("source_url"),
                        enabled = item.optBoolean("enabled", true),
                        runAt = UserScriptRunAt.parse(item.optString("run_at")),
                        matches = readList(item.optJSONArray("matches")),
                        includes = readList(item.optJSONArray("includes")),
                        excludes = readList(item.optJSONArray("excludes")),
                        grants = readList(item.optJSONArray("grants")),
                        connects = readList(item.optJSONArray("connects")),
                        body = body,
                        installedAt = item.optLong("installed_at", 0L),
                        updatedAt = item.optLong("updated_at", 0L),
                    ),
                )
            }
        }
    }

    private fun saveMetadata(context: Context, scripts: List<UserScript>) {
        val array = JSONArray()
        for (script in scripts) {
            array.put(
                JSONObject()
                    .put("id", script.id)
                    .put("name", script.name)
                    .put("namespace", script.namespace)
                    .put("version", script.version)
                    .put("description", script.description)
                    .put("source_url", script.sourceUrl)
                    .put("enabled", script.enabled)
                    .put("run_at", script.runAt.id)
                    .put("matches", JSONArray(script.matches))
                    .put("includes", JSONArray(script.includes))
                    .put("excludes", JSONArray(script.excludes))
                    .put("grants", JSONArray(script.grants))
                    .put("connects", JSONArray(script.connects))
                    .put("installed_at", script.installedAt)
                    .put("updated_at", script.updatedAt),
            )
        }
        prefs(context).edit().putString(KEY_SCRIPTS, array.toString()).apply()
    }

    private fun writeParts(
        context: Context,
        id: String,
        requires: List<UserScriptRequire>,
        resources: List<UserScriptResource>,
    ) {
        val requiresDir = requireDirectory(context, id).apply { deleteRecursively(); mkdirs() }
        val requireManifest = JSONArray()
        requires.forEachIndexed { index, item ->
            val name = "${index.toString().padStart(3, '0')}-${item.sha256.take(16)}.js"
            writeFile(File(requiresDir, name), item.body.toByteArray(Charsets.UTF_8))
            requireManifest.put(
                JSONObject()
                    .put("url", item.url)
                    .put("sha256", item.sha256)
                    .put("file", name),
            )
        }
        writeFile(File(requiresDir, MANIFEST), requireManifest.toString().toByteArray(Charsets.UTF_8))

        val resourcesDir = resourceDirectory(context, id).apply { deleteRecursively(); mkdirs() }
        val resourceManifest = JSONArray()
        resources.forEachIndexed { index, item ->
            val safe = item.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "resource" }
            val name = "${index.toString().padStart(3, '0')}-$safe"
            writeFile(File(resourcesDir, name), item.body)
            resourceManifest.put(
                JSONObject()
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("sha256", item.sha256)
                    .put("file", name),
            )
        }
        writeFile(File(resourcesDir, MANIFEST), resourceManifest.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readValues(context: Context, scriptId: String): Map<String, String> {
        val text = runCatching { valuesFile(context, scriptId).readText() }.getOrNull()
            ?: return emptyMap()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (key in json.keys()) put(key, json.optString(key))
        }
    }

    private fun writeValues(context: Context, scriptId: String, values: Map<String, String>) {
        val json = JSONObject()
        for ((key, value) in values) json.put(key, value)
        writeFile(valuesFile(context, scriptId), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun totalBytes(context: Context, id: String): Long {
        var size = scriptFile(context, id).length()
        size += requireDirectory(context, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        size += resourceDirectory(context, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return size
    }

    private fun userscriptRoot(context: Context) = File(context.filesDir, "userscripts")

    private fun scriptFile(context: Context, id: String): File {
        val safe = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(userscriptRoot(context), "scripts/$safe.user.js")
    }

    private fun requireDirectory(context: Context, id: String): File {
        val safe = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(userscriptRoot(context), "requires/$safe")
    }

    private fun resourceDirectory(context: Context, id: String): File {
        val safe = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(userscriptRoot(context), "resources/$safe")
    }

    private fun valuesFile(context: Context, id: String): File {
        val safe = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(userscriptRoot(context), "values/$safe.json")
    }

    private fun readManifest(file: File): List<JSONObject> {
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun readList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index)
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun readList(raw: String): List<String> =
        runCatching { readList(JSONArray(raw)) }.getOrDefault(emptyList())

    private fun writeFile(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    private fun resolve(base: String, raw: String): String {
        if (UserScriptPattern.isHttpUrl(raw)) return raw
        val resolved = runCatching { URL(URL(base), raw.trim()).toString() }.getOrNull()
        require(resolved != null && UserScriptPattern.isHttpUrl(resolved)) {
            "invalid URL: $raw"
        }
        return resolved
    }

    private fun download(rawUrl: String, limit: Int): ByteArray {
        var current = rawUrl
        var redirects = 0
        while (true) {
            require(UserScriptPattern.isHttpUrl(current)) { "redirected to a non-http URL" }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "*/*")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("redirect without Location")
                    current = URL(URL(current), location).toString()
                    redirects++
                    require(redirects <= MAX_REDIRECTS) { "too many redirects" }
                    continue
                }
                require(code in 200..299) { "HTTP $code" }
                val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
                return stream.use { readLimited(it, limit) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "response is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private const val MANIFEST = "manifest.json"
}
