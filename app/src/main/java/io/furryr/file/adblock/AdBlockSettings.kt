package io.furryr.file.adblock

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * One filter list. A built-in list has a download [url]; an imported list has
 * no URL and keeps its content in `filesDir/adblock/lists/<id>.txt`, so the
 * editor can open and change it.
 *
 * [hosts] marks a file in hosts syntax (`0.0.0.0 domain`). Those lines are
 * rewritten to Adblock Plus syntax before the engine sees them, so hosts files
 * can be combined with normal lists.
 */
data class AdBlockList(
    val id: String,
    val title: String,
    val url: String?,
    val enabled: Boolean,
    val builtIn: Boolean,
    val hosts: Boolean = false,
)

/** Result of one "update filter lists" run. */
data class AdBlockUpdateReport(
    val updated: Int,
    val failed: Int,
)

/**
 * Filter-list configuration and cache files for the browser's ad blocker.
 *
 * Preferences live in `SharedPreferences("adblock_settings")`; list bodies
 * live under `filesDir/adblock/lists/` (text the editor can open) and the
 * serialized engine under `cacheDir/adblock/`. The store never owns the
 * engine: [AdBlockEngine] builds and installs it.
 */
object AdBlockSettings {
    private const val PREFS = "adblock_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LISTS = "lists"
    private const val KEY_LAST_UPDATE = "last_update"

    private const val MAX_LIST_BYTES = 16 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    private val HOSTS_LINE = Regex(
        "^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1?|::)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    /** Lists shipped with the app. Users may disable them but not delete them. */
    val builtIns: List<AdBlockList> = listOf(
        AdBlockList(
            id = "easylist",
            title = "EasyList",
            url = "https://easylist.to/easylist/easylist.txt",
            enabled = true,
            builtIn = true,
        ),
        AdBlockList(
            id = "easylist-china",
            title = "EasyList China",
            url = "https://easylist-downloads.adblockplus.org/easylistchina.txt",
            enabled = true,
            builtIn = true,
        ),
        AdBlockList(
            id = "easyprivacy",
            title = "EasyPrivacy",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            enabled = false,
            builtIn = true,
        ),
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastUpdate(context: Context): Long = prefs(context).getLong(KEY_LAST_UPDATE, 0L)

    /** All configured lists, with built-ins added on first use. */
    @Synchronized
    fun lists(context: Context): List<AdBlockList> {
        val stored = prefs(context).getString(KEY_LISTS, null)
        val current = if (stored == null) {
            mutableListOf<AdBlockList>()
        } else {
            runCatching { parseLists(stored) }.getOrDefault(mutableListOf())
        }
        var changed = stored == null
        for (default in builtIns) {
            val index = current.indexOfFirst { it.id == default.id }
            if (index < 0) {
                current.add(default)
                changed = true
            } else if (!current[index].builtIn) {
                current[index] = current[index].copy(builtIn = true)
                changed = true
            }
        }
        if (changed) saveLists(context, current)
        return current
    }

    @Synchronized
    fun saveLists(context: Context, lists: List<AdBlockList>) {
        val array = JSONArray()
        for (list in lists) {
            array.put(
                JSONObject()
                    .put("id", list.id)
                    .put("title", list.title)
                    .put("url", list.url.orEmpty())
                    .put("enabled", list.enabled)
                    .put("builtIn", list.builtIn)
                    .put("hosts", list.hosts),
            )
        }
        prefs(context).edit().putString(KEY_LISTS, array.toString()).apply()
    }

    @Synchronized
    fun setListEnabled(context: Context, id: String, enabled: Boolean) {
        val lists = lists(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveLists(context, lists)
    }

    /** Insert or replace a user list. A built-in id only changes its enabled flag. */
    @Synchronized
    fun saveList(context: Context, list: AdBlockList) {
        val lists = lists(context).toMutableList()
        if (list.builtIn) {
            val index = lists.indexOfFirst { it.id == list.id }
            if (index >= 0) lists[index] = lists[index].copy(enabled = list.enabled)
        } else {
            val index = lists.indexOfFirst { it.id == list.id }
            if (index >= 0) lists[index] = list else lists.add(list)
        }
        saveLists(context, lists)
    }

    @Synchronized
    fun deleteList(context: Context, id: String) {
        val list = lists(context).firstOrNull { it.id == id } ?: return
        if (list.builtIn) return
        saveLists(context, lists(context).filterNot { it.id == id })
        listFile(context, id).delete()
    }

    fun newId(): String = "custom-${UUID.randomUUID()}"

    fun isValidUrl(value: String): Boolean {
        val text = value.trim()
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        return runCatching { URL(text) }.isSuccess
    }

    /** True when at least one enabled list already has a body to compile. */
    @Synchronized
    fun hasRules(context: Context): Boolean =
        lists(context).any { it.enabled && listFile(context, it.id).isFile }

    fun engineCache(context: Context): File = File(context.cacheDir, "adblock/engine.dat")

    /** Body file for one list; the editor can open and change it in place. */
    fun listFile(context: Context, id: String): File {
        val safe = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.filesDir, "adblock/lists/$safe.txt")
    }

    /** Combine every enabled list into the text the engine parses. */
    @Synchronized
    fun combinedRules(context: Context): String {
        val builder = StringBuilder()
        for (list in lists(context)) {
            if (!list.enabled) continue
            val file = listFile(context, list.id)
            if (!file.isFile) continue
            val text = try {
                readLimited(file.inputStream(), MAX_LIST_BYTES).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            builder.append(if (list.hosts) hostsToRules(text) else text)
            builder.append('\n')
        }
        return builder.toString()
    }

    /**
     * Download every enabled list that has a URL, then rebuild the engine.
     *
     * A failed download keeps the previous body, so one unreachable mirror does
     * not wipe a working list. The update fails only when nothing could be
     * compiled.
     */
    fun update(context: Context): AdBlockUpdateReport {
        val enabled = lists(context).filter { it.enabled }
        if (enabled.isEmpty()) error("No filter lists are enabled")
        var updated = 0
        var failed = 0
        var hasBody = false
        for (list in enabled) {
            val file = listFile(context, list.id)
            if (list.url != null) {
                try {
                    val text = download(list.url)
                    val head = text.trimStart().take(64).lowercase(Locale.ROOT)
                    require(!head.startsWith("<!doctype") && !head.startsWith("<html")) {
                        "Not a filter list"
                    }
                    require(text.lineSequence().any { line ->
                        line.isNotBlank() && !line.trimStart().startsWith("!")
                    }) {
                        "Empty filter list"
                    }
                    writeAtomically(file, text.toByteArray(Charsets.UTF_8))
                    updated++
                    hasBody = true
                } catch (error: Exception) {
                    failed++
                    if (file.isFile) hasBody = true
                }
            } else if (file.isFile) {
                hasBody = true
            } else {
                failed++
            }
        }
        if (!hasBody) error("No filter list could be loaded")
        AdBlockEngine.rebuildNow(context).getOrThrow()
        prefs(context).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        return AdBlockUpdateReport(updated = updated, failed = failed)
    }

    /**
     * Import a local filter list (EasyList syntax or a hosts file) and enable
     * it. The body is copied into the database, so the source document may be
     * deleted afterwards.
     */
    @Synchronized
    fun importFile(context: Context, uri: Uri): AdBlockList {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            decodeList(readLimited(input, MAX_LIST_BYTES))
        } ?: error("Cannot read the selected file")
        require(text.lineSequence().any { line ->
            line.isNotBlank() && !line.trimStart().startsWith("!")
        }) {
            "The selected file has no rules"
        }
        val title = displayName(context, uri)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "Imported list"
        val list = AdBlockList(
            id = "local-${UUID.randomUUID()}",
            title = title,
            url = null,
            enabled = true,
            builtIn = false,
            hosts = looksLikeHosts(text),
        )
        writeAtomically(listFile(context, list.id), text.toByteArray(Charsets.UTF_8))
        saveList(context, list)
        return list
    }

    private fun parseLists(raw: String): MutableList<AdBlockList> {
        val array = JSONArray(raw)
        val result = mutableListOf<AdBlockList>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            result.add(
                AdBlockList(
                    id = id,
                    title = item.optString("title").takeIf { it.isNotBlank() } ?: id,
                    url = item.optString("url").takeIf { it.isNotBlank() },
                    enabled = item.optBoolean("enabled", true),
                    builtIn = item.optBoolean("builtIn", false),
                    hosts = item.optBoolean("hosts", false),
                ),
            )
        }
        return result
    }

    /** Rewrite `0.0.0.0 domain` / `127.0.0.1 domain` lines into `||domain^`. */
    private fun hostsToRules(text: String): String {
        val builder = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                builder.append(line).append('\n')
                continue
            }
            val match = HOSTS_LINE.matchEntire(trimmed)
            if (match == null) {
                builder.append(line).append('\n')
                continue
            }
            val tail = match.groupValues[1]
            for (part in tail.split(Regex("\\s+"))) {
                if (part.startsWith("#")) break
                if (part.isNotEmpty()) builder.append("||").append(part).append("^\n")
            }
        }
        return builder.toString()
    }

    private fun looksLikeHosts(text: String): Boolean {
        var rules = 0
        var hosts = 0
        for (line in text.lineSequence()) {
            if (rules >= 200) break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue
            rules++
            if (HOSTS_LINE.matches(trimmed)) hosts++
        }
        return rules > 0 && hosts * 2 >= rules
    }

    private fun download(url: String): String {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FileManager-AdBlock/1.0")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect without a location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                val bytes = connection.inputStream.use { readLimited(it, MAX_LIST_BYTES) }
                return decodeList(bytes)
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    /** Servers sometimes compress a list even when identity was requested. */
    private fun decodeList(bytes: ByteArray): String {
        val gzip = bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() &&
            bytes[1] == 0x8b.toByte()
        if (!gzip) return bytes.toString(Charsets.UTF_8)
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            readLimited(input, MAX_LIST_BYTES).toString(Charsets.UTF_8)
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) error("Filter list is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment
}
