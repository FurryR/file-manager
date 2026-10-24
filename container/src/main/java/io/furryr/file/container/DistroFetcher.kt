package io.furryr.file.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads Linux rootfs filesystems from Docker images via the Docker
 * Registry HTTP API V2.
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
}
