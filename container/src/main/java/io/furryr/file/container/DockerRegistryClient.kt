package io.furryr.file.container

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object DockerRegistryClient {
    private const val TAG = "DockerRegistryClient"
    private const val DOCKER_AUTH_URL = "https://auth.docker.io/token"
    private const val DOCKER_REGISTRY = "https://registry-1.docker.io"
    private const val DOCKER_SERVICE = "registry.docker.io"

    fun downloadRootfs(
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
                DistroTarExtractor.extractTarGzInto(blobFile, destDir)
            } finally {
                blobFile.delete()
            }
            downloadedBytes += layer.size
        }
        DistroTarExtractor.maybeFlattenRootfs(destDir)

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

}
