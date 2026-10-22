package io.furryr.file.provider.remote

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/** AList v3 API: https://alistgo.com/guide/api/fs.html and /guide/api/auth.html. */
internal class AlistBackend(private val location: RemoteLocation) : RemoteBackend {
    private val base = location.endpoint.trimEnd('/')
    private val authId = listOf(location.id, location.endpoint, location.username, location.password)

    override fun list(path: String): List<RemoteEntry> {
        val entries = linkedMapOf<String, RemoteEntry>()
        var page = 1
        while (true) {
            interrupted()
            val data = listPage(path, page, 500)
            val content = data.optJSONArray("content") ?: JSONArray()
            var added = 0
            for (i in 0 until content.length()) {
                val entry = entry(content.getJSONObject(i))
                validName(entry.name)
                if (entries.put(entry.name, entry) == null) added++
            }
            val more = if (data.has("has_more")) data.getBoolean("has_more") else {
                content.length() > 0 && entries.size < data.optLong("total", Long.MAX_VALUE)
            }
            if (!more || content.length() == 0) break
            if (added == 0) throw IOException("AList returned a repeated page; refresh the directory")
            page++
        }
        return entries.values.toList()
    }
    override fun canWrite(path: String): Boolean = listPage(path, 1, 1).optBoolean("write", false)
    private fun listPage(path: String, page: Int, count: Int): JSONObject = api("/api/fs/list",
        JSONObject().put("path", path).put("password", location.directoryPassword)
            .put("page", page).put("per_page", count).put("refresh", page == 1)).getJSONObject("data")

    override fun stat(path: String): RemoteEntry? {
        if (path == "/") { listPage(path, 1, 1); return RemoteEntry("/", true) }
        // v3 storage drivers often use code 500 for not-found. Listing the
        // parent distinguishes absence from permission/network/driver errors.
        return list(parentPath(path)).firstOrNull { it.name == path.substringAfterLast('/') }
    }
    override fun download(path: String, destination: File) {
        val data = api("/api/fs/get", JSONObject().put("path", path).put("password", location.directoryPassword)).getJSONObject("data")
        require(!data.getBoolean("is_dir")) { "Cannot download a directory" }
        var url = URL(data.getString("raw_url"))
        var redirects = 0
        while (true) {
            require(url.protocol in setOf("https", "http") && url.userInfo == null) { "AList returned an unsupported download URL" }
            val connection = connection(url, "GET")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    if (++redirects > 10) throw IOException("Too many download redirects")
                    val next = URL(url, connection.getHeaderField("Location") ?: throw IOException("Missing download redirect"))
                    if (url.protocol == "https" && next.protocol != "https") throw IOException("Refusing an insecure download redirect")
                    url = next
                    continue
                }
                if (code !in 200..299) throw IOException("AList download returned HTTP $code")
                val bytes = connection.inputStream.use { input -> destination.outputStream().use { copy(input, it) } }
                val expected = connection.getHeaderFieldLong("Content-Length", -1)
                if (expected >= 0 && bytes != expected) throw IOException("AList download was incomplete")
                return
            } finally { connection.disconnect() }
        }
    }
    override fun upload(path: String, source: File) { put(mutablePath(path), source, overwrite = true) }
    override fun createFile(path: String) {
        if (stat(path) != null) throw IOException("An entry with this name already exists")
        put(mutablePath(path), null, overwrite = false)
    }
    override fun createDirectory(path: String) {
        if (stat(path) != null) throw IOException("An entry with this name already exists")
        api("/api/fs/mkdir", JSONObject().put("path", mutablePath(path)))
    }
    override fun delete(path: String) {
        mutablePath(path)
        api("/api/fs/remove", JSONObject().put("dir", parentPath(path)).put("names", JSONArray().put(path.substringAfterLast('/'))))
    }
    override fun rename(path: String, newName: String) {
        mutablePath(path); validName(newName)
        if (stat(parentPath(path).trimEnd('/') + "/" + newName) != null) throw IOException("The destination already exists")
        api("/api/fs/rename", JSONObject().put("path", path).put("name", newName))
    }
    private fun put(path: String, source: File?, overwrite: Boolean) {
        val connection = authenticated("/api/fs/put", "PUT")
        try {
            connection.setRequestProperty("File-Path", Uri.encode(path, "/"))
            connection.setRequestProperty("As-Task", "false")
            connection.setRequestProperty("Overwrite", overwrite.toString())
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(source?.length() ?: 0L)
            connection.outputStream.use { output -> source?.inputStream()?.use { copy(it, output) } }
            val response = response(connection)
            // As-Task=false is synchronous in AList's FsStream/PutDirectly.
            // Never mistake an unexpected queued task for a completed save.
            if (response.optJSONObject("data")?.optJSONObject("task") != null) {
                throw IOException("AList queued the upload instead of confirming it; check the server task before retrying")
            }
        } finally { connection.disconnect() }
    }
    private fun api(path: String, body: JSONObject): JSONObject {
        val connection = authenticated(path, "POST")
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return response(connection)
        } finally { connection.disconnect() }
    }
    private fun authenticated(path: String, method: String): HttpURLConnection {
        val token = token()
        return connection(URL(base + path), method).apply {
            setRequestProperty("Client-Id", "file-manager-${location.id}")
            if (token.isNotEmpty()) setRequestProperty("Authorization", token)
        }
    }
    private fun token(): String {
        if (location.token.isNotBlank()) return location.token
        if (location.username.isBlank()) return ""
        synchronized(tokens) {
            tokens[authId]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
            val connection = connection(URL(base + "/api/auth/login"), "POST")
            try {
                val body = JSONObject().put("username", location.username).put("password", location.password).toString().toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Client-Id", "file-manager-${location.id}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val token = response(connection).getJSONObject("data").getString("token")
                tokens[authId] = token to (System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                return token
            } finally { connection.disconnect() }
        }
    }
    private fun response(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        if (status !in 200..299) {
            if (status == 401) tokens.remove(authId)
            throw IOException("AList returned HTTP $status")
        }
        val json = connection.inputStream.use { JSONObject(readJson(it)) }
        val code = json.getInt("code")
        if (code != 200) {
            if (code == 401) tokens.remove(authId)
            var message = json.optString("message").take(512).filter { !it.isISOControl() }
            listOf(location.password, location.token, location.directoryPassword).filter { it.isNotEmpty() }
                .forEach { message = message.replace(it, "[redacted]") }
            throw IOException("AList $code: $message")
        }
        return json
    }
    private fun entry(value: JSONObject) = RemoteEntry(value.getString("name"), value.getBoolean("is_dir"),
        value.optLong("size"), value.optString("modified").takeIf { it.isNotEmpty() }?.let {
            try { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            catch (error: java.time.format.DateTimeParseException) { android.util.Log.d("AList", "Unrecognized remote timestamp", error); 0L }
        } ?: 0)
    private fun connection(url: URL, method: String) = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 30_000; readTimeout = 60_000
        instanceFollowRedirects = false
        setRequestProperty("Accept-Encoding", "identity")
    }
    private fun readJson(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            interrupted(); val n = input.read(buffer); if (n < 0) break
            if (output.size() + n > 16 * 1024 * 1024) throw IOException("AList metadata response is too large")
            output.write(buffer, 0, n)
        }
        return output.toString("UTF-8")
    }
    private fun copy(input: InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(256 * 1024); var total = 0L
        while (true) { interrupted(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); total += n }
        return total
    }
    companion object { private val tokens = ConcurrentHashMap<List<String>, Pair<String, Long>>() }
}
