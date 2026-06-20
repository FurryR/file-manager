package io.furryr.file.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads Linux rootfs filesystems from Docker images via the Docker
 * Registry HTTP API V2, or from direct tarball URLs.
 *
 * Rootfs contents are extracted directly into the container directory
 * (no extra `rootfs/` subdirectory). Docker images that wrap in a single
 * top-level directory are automatically flattened.
 *
 * ```
 * 1. GET https://auth.docker.io/token?service=registry.docker.io&scope=repository:<name>:pull
 *    → bearer token
 * 2. GET https://registry-1.docker.io/v2/<name>/manifests/<tag>
 *    Accept: application/vnd.docker.distribution.manifest.v2+json
 *    → manifest with layer digests
 * 3. For each layer:
 *    GET https://registry-1.docker.io/v2/<name>/blobs/<digest>
 *    → tar.gz → extract directly into destDir
 *    → flatten if wrapped in single top-level directory
 * ```
 *
 * Usage:
 * ```
 * DistroFetcher.downloadRootfs(imageRef = "library/alpine:latest", destDir)
 * ```
 */
object DistroFetcher {
    private const val TAG = "DistroFetcher"

    // Docker Registry endpoints
    private const val DOCKER_AUTH_URL = "https://auth.docker.io/token"
    private const val DOCKER_REGISTRY = "https://registry-1.docker.io"
    private const val DOCKER_SERVICE = "registry.docker.io"

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Download and extract a rootfs from a Docker image.
     *
     * @param imageRef Docker image reference, e.g. "library/alpine:latest"
     * @param destDir  Directory where the rootfs will be extracted
     * @param progress Optional callback: progress(currentBytes, totalBytes)
     * @return [Result.success] with the rootfs directory
     */
    suspend fun downloadRootfs(
        imageRef: String,
        destDir: File,
        progress: ((Long, Long) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            downloadFromDocker(imageRef, destDir, progress)
            destDir
        }
    }

    /**
     * Download and extract a rootfs from a direct tarball URL.
     *
     * @param url      Direct URL to a rootfs tarball (.tar.gz)
     * @param destDir  Directory where the rootfs will be extracted
     * @param checksum Optional SHA-256 hex digest for integrity verification
     * @param progress Optional callback: progress(currentBytes, totalBytes)
     * @return [Result.success] with the rootfs directory
     */
    suspend fun downloadRootfsFromUrl(
        url: String,
        destDir: File,
        checksum: String = "",
        progress: ((Long, Long) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            downloadFromUrl(url, checksum, destDir, progress)
            destDir
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Docker Registry V2 API
    // ══════════════════════════════════════════════════════════════════════

    private fun downloadFromDocker(
        imageRef: String,
        destDir: File,
        progress: ((Long, Long) -> Unit)?,
    ) {
        val (name, tag) = parseDockerRef(imageRef)
        Log.i(TAG, "Pulling Docker image $name:$tag")

        val token = obtainDockerToken(name)
        val manifestJson = fetchDockerManifest(name, tag, token)
        val resolvedJson = resolveManifestForArm64(manifestJson, name, token)
        val layers = parseDockerManifestLayers(resolvedJson)

        if (layers.isEmpty()) {
            throw IOException("No layers found in manifest for $name:$tag")
        }

        val totalBytes = layers.sumOf { it.size }
        var downloadedBytes = 0L

        for (layer in layers) {
            Log.d(TAG, "Downloading layer ${layer.digest} (${layer.size} bytes)")
            val blobFile = File(destDir, ".layer-${layer.digest.replace(':', '-')}.tar.gz")
            try {
                downloadBlob(name, layer.digest, token, blobFile, progress, downloadedBytes, totalBytes)
                extractTarGzInto(blobFile, destDir)
            } finally {
                blobFile.delete()
            }
            downloadedBytes += layer.size
        }
        maybeFlattenRootfs(destDir)

        Log.i(TAG, "Docker image $name:$tag pulled (${layers.size} layers)")
    }

    internal fun parseDockerRef(ref: String): Pair<String, String> {
        val parts = ref.split(':')
        val name = parts[0].trimStart('/')
        val tag = if (parts.size > 1) parts[1] else "latest"
        return name to tag
    }

    private fun obtainDockerToken(name: String): String {
        val scope = "repository:$name:pull"
        val url = URL("$DOCKER_AUTH_URL?service=$DOCKER_SERVICE&scope=$scope")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            throw IOException("Docker auth failed (HTTP $responseCode): $body")
        }

        val json = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(json).getString("token")
    }

    private fun fetchDockerManifest(name: String, tag: String, token: String): String {
        val url = URL("$DOCKER_REGISTRY/v2/$name/manifests/$tag")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty(
            "Accept",
            "application/vnd.docker.distribution.manifest.list.v2+json," +
            "application/vnd.docker.distribution.manifest.v2+json," +
            "application/vnd.oci.image.index.v1+json," +
            "application/vnd.oci.image.manifest.v1+json"
        )
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            throw IOException("Docker manifest fetch failed (HTTP $responseCode): $body")
        }

        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    internal fun resolveManifestForArm64(
        manifestJson: String,
        name: String,
        token: String,
    ): String {
        val obj = JSONObject(manifestJson)

        if (obj.has("layers")) return manifestJson

        if (!obj.has("manifests")) {
            throw IOException("Unrecognized manifest format (no 'layers' or 'manifests')")
        }

        val manifests = obj.getJSONArray("manifests")
        for (i in 0 until manifests.length()) {
            val entry = manifests.getJSONObject(i)
            val platform = entry.optJSONObject("platform")
            if (platform != null) {
                val arch = platform.optString("architecture", "")
                if (arch == "arm64" || arch == "aarch64") {
                    val digest = entry.getString("digest")
                    Log.i(TAG, "Resolved arm64 manifest digest: $digest")
                    return fetchDockerManifestByDigest(name, digest, token)
                }
            }
        }

        val first = manifests.getJSONObject(0)
        Log.w(TAG, "No arm64 manifest found, using first entry (${first.optJSONObject("platform")?.optString("architecture")})")
        return fetchDockerManifestByDigest(name, first.getString("digest"), token)
    }

    private fun fetchDockerManifestByDigest(name: String, digest: String, token: String): String {
        val url = URL("$DOCKER_REGISTRY/v2/$name/manifests/$digest")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.docker.distribution.manifest.v2+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            throw IOException("Docker manifest fetch by digest failed (HTTP $responseCode): $body")
        }

        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    internal fun parseDockerManifestLayers(manifestJson: String): List<LayerDescriptor> {
        val obj = JSONObject(manifestJson)
        if (!obj.has("layers")) {
            throw IOException("No value for layers")
        }
        val layersArray = obj.getJSONArray("layers")
        val layers = mutableListOf<LayerDescriptor>()
        for (i in 0 until layersArray.length()) {
            val layer = layersArray.getJSONObject(i)
            layers.add(
                LayerDescriptor(
                    digest = layer.getString("digest"),
                    size = layer.getLong("size"),
                )
            )
        }
        return layers
    }

    internal data class LayerDescriptor(
        val digest: String,
        val size: Long,
    )

    private fun downloadBlob(
        name: String,
        digest: String,
        token: String,
        outputFile: File,
        progress: ((Long, Long) -> Unit)?,
        alreadyDownloaded: Long,
        totalBytes: Long,
    ) {
        val url = URL("$DOCKER_REGISTRY/v2/$name/blobs/$digest")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        conn.instanceFollowRedirects = true

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            throw IOException("Docker blob download failed (HTTP $responseCode): $body")
        }

        conn.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var offset = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    offset += bytesRead
                    if (progress != null && totalBytes > 0) {
                        progress(alreadyDownloaded + offset, totalBytes)
                    }
                }
            }
        }

        conn.disconnect()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Direct HTTP download (fallback)
    // ══════════════════════════════════════════════════════════════════════

    private fun downloadFromUrl(
        urlString: String,
        checksum: String,
        destDir: File,
        progress: ((Long, Long) -> Unit)?,
    ) {
        val url = URL(urlString)
        val conn = openHttpConnection(url, readTimeoutMs = 300_000)

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("Server returned HTTP $responseCode for $urlString")
        }

        val contentLength = conn.contentLengthLong
        val digest = if (checksum.isNotBlank()) MessageDigest.getInstance("SHA-256") else null

        val tempFile = File(destDir, ".download.tar.gz")
        try {
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest?.update(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (progress != null && contentLength > 0) {
                            progress(totalBytesRead, contentLength)
                        }
                    }
                }
            }
            conn.disconnect()

            if (digest != null) {
                val computedHash = digest.digest().joinToString(separator = "") { "%02x".format(it) }
                if (!computedHash.equals(checksum, ignoreCase = true)) {
                    tempFile.delete()
                    throw IOException("Checksum mismatch: expected $checksum, computed $computedHash")
                }
            }

            extractTarGzInto(tempFile, destDir)
            maybeFlattenRootfs(destDir)
        } finally {
            tempFile.delete()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  tar.gz extraction
    // ══════════════════════════════════════════════════════════════════════

    private fun extractTarGzInto(tarball: File, destDir: File) {
        val destPath = destDir.canonicalPath
        val dataBuffer = ByteArray(8192)

        tarball.inputStream().use { raw ->
            GZIPInputStream(raw).use { gzip ->
                var header = ByteArray(512)

                while (true) {
                    // Read 512-byte header
                    var offset = 0
                    while (offset < 512) {
                        val bytesRead = gzip.read(header, offset, 512 - offset)
                        if (bytesRead == -1) {
                            if (offset == 0) break
                            throw IOException("Truncated tar header")
                        }
                        offset += bytesRead
                    }

                    // End marker: two zero blocks
                    if (offset == 512 && isAllZeros(header)) {
                        offset = 0
                        val secondBlock = ByteArray(512)
                        while (offset < 512) {
                            val bytesRead = gzip.read(secondBlock, offset, 512 - offset)
                            if (bytesRead == -1 && offset == 0) break
                            if (bytesRead == -1) break
                            offset += bytesRead
                        }
                        break
                    }
                    if (offset < 512) break

                    val name = parseTarString(header, 0, 100)
                    val prefix = parseTarString(header, 345, 155)
                    val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

                    val sanitised = fullName.trimStart('/')
                        .split('/')
                        .filter { it != ".." && it != "." && it.isNotEmpty() }
                        .joinToString("/")
                    if (sanitised.isEmpty()) {
                        skipTarData(gzip, header)
                        continue
                    }

                    val size = parseTarOctal(header, 124, 12)
                    val typeFlag = header[156].toInt().toChar()

                    val mode = parseTarOctal(header, 100, 8)

                    val entryFile = File(destDir, sanitised)
                    val entryCanonical = entryFile.canonicalPath

                    // Path-traversal guard
                    if (!entryCanonical.startsWith(destPath + File.separator) && entryCanonical != destPath) {
                        skipTarData(gzip, header)
                        continue
                    }

                    when (typeFlag) {
                        '0', '\u0000' -> {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { out ->
                                var remaining = size
                                while (remaining > 0) {
                                    val toRead = minOf(remaining, dataBuffer.size.toLong()).toInt()
                                    val read = gzip.read(dataBuffer, 0, toRead)
                                    if (read == -1) throw IOException("Unexpected EOF in tar data")
                                    out.write(dataBuffer, 0, read)
                                    remaining -= read
                                }
                            }
                            applyMode(entryFile, mode)
                            val padding = (512 - (size % 512)) % 512
                            if (padding > 0) skipBytes(gzip, padding)
                        }

                        '5' -> { entryFile.mkdirs(); applyMode(entryFile, mode) }

                        '2' -> {
                            entryFile.parentFile?.mkdirs()
                            val linkTarget = parseTarString(header, 157, 100)
                            entryFile.delete()
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    entryFile.toPath(),
                                    java.nio.file.Paths.get(linkTarget),
                                )
                            } catch (_: Exception) {
                                entryFile.createNewFile()
                                applyMode(entryFile, mode)
                            }
                        }

                        '1' -> {
                            val linkTarget = parseTarString(header, 157, 100)
                            val targetFile = File(destDir, linkTarget.trimStart('/'))
                            val targetCanonical = targetFile.canonicalPath

                            if (!targetCanonical.startsWith(destPath + File.separator) && targetCanonical != destPath) {
                                skipTarData(gzip, header)
                                continue
                            }

                            entryFile.parentFile?.mkdirs()
                            entryFile.delete()

                            try {
                                java.nio.file.Files.createLink(
                                    entryFile.toPath(),
                                    targetFile.toPath()
                                )
                            } catch (_: Exception) {
                                targetFile.copyTo(entryFile, overwrite = true)
                                applyMode(entryFile, mode)
                            }
                        }

                        else -> skipTarData(gzip, header)
                    }
                }
            }
        }
    }

    /**
     * Docker images often wrap the rootfs in a single top-level directory
     * (e.g. `alpine-minirootfs/...`). If [destDir] contains exactly one
     * subdirectory and no regular files, move its contents up and delete it.
     */
    private fun maybeFlattenRootfs(destDir: File) {
        val children = destDir.listFiles() ?: return
        val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
        val files = children.filter { it.isFile && !it.name.startsWith(".") }
        if (dirs.size == 1 && files.isEmpty()) {
            val wrapper = dirs[0]
            Log.i(TAG, "Flattening top-level directory: ${wrapper.name}")
            wrapper.listFiles()?.forEach { child ->
                child.renameTo(File(destDir, child.name))
            }
            wrapper.delete()
        }
    }

    // ── Permission restoration ─────────────────────────────────────────

    /**
     * Apply Unix permission bits from a tar header mode field.
     * Falls back to sensible defaults when mode is 0.
     */
    private fun applyMode(file: File, mode: Long) {
        if (mode == 0L) return
        val perms = mutableSetOf<PosixFilePermission>()
        if (mode and 0x100 != 0L) perms.add(PosixFilePermission.OWNER_READ)
        if (mode and 0x080 != 0L) perms.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0x040 != 0L) perms.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0x020 != 0L) perms.add(PosixFilePermission.GROUP_READ)
        if (mode and 0x010 != 0L) perms.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0x008 != 0L) perms.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0x004 != 0L) perms.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0x002 != 0L) perms.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0x001 != 0L) perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(file.toPath(), perms)
    }

    // ── tar helpers ─────────────────────────────────────────────────

    private fun skipBytes(input: InputStream, n: Long) {
        val buffer = ByteArray(512)
        var remaining = n
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            remaining -= read
        }
    }

    private fun skipTarData(input: InputStream, header: ByteArray) {
        val size = parseTarOctal(header, 124, 12)
        val blocks = (size + 511) / 512
        val toSkip = blocks * 512
        var skipped = 0L
        while (skipped < toSkip) {
            val n = input.skip(toSkip - skipped)
            if (n <= 0) break
            skipped += n
        }
    }

    private fun isAllZeros(bytes: ByteArray): Boolean {
        for (b in bytes) {
            if (b != 0.toByte()) return false
        }
        return true
    }

    private fun parseTarString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseTarOctal(header: ByteArray, offset: Int, length: Int): Long {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        val str = String(header, offset, end - offset, Charsets.UTF_8)
        if (str.isEmpty()) return 0L
        return str.toLong(8)
    }

    // ── HTTP helpers ────────────────────────────────────────────────

    private fun openHttpConnection(url: URL, readTimeoutMs: Int): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
        }
    }
}
