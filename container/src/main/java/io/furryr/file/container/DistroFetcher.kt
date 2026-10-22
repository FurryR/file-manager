package io.furryr.file.container

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
            DockerRegistryClient.downloadRootfs(imageRef, destDir, progress)
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

    private fun downloadFromUrl(
        urlString: String,
        checksum: String,
        destDir: File,
        progress: ((Long, Long) -> Unit)?,
    ) {
        val url = URL(urlString)
        val conn = DistroHttp.openConnection(url, readTimeoutMs = 300_000)

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

            DistroTarExtractor.extractTarGzInto(tempFile, destDir)
            DistroTarExtractor.maybeFlattenRootfs(destDir)
        } finally {
            tempFile.delete()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  tar.gz extraction
    // ══════════════════════════════════════════════════════════════════════

}
