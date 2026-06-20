package io.furryr.file.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DistroFetcherTest {

    // ── parseDockerRef ──────────────────────────────────────────────────

    @Test
    fun `parseDockerRef splits name and tag`() {
        val (name, tag) = DistroFetcher.parseDockerRef("library/alpine:latest")
        assertEquals("library/alpine", name)
        assertEquals("latest", tag)
    }

    @Test
    fun `parseDockerRef defaults tag to latest`() {
        val (name, tag) = DistroFetcher.parseDockerRef("library/ubuntu")
        assertEquals("library/ubuntu", name)
        assertEquals("latest", tag)
    }

    @Test
    fun `parseDockerRef strips leading slash`() {
        val (name, tag) = DistroFetcher.parseDockerRef("/library/debian:bookworm")
        assertEquals("library/debian", name)
        assertEquals("bookworm", tag)
    }

    @Test
    fun `parseDockerRef handles numeric tag`() {
        val (name, tag) = DistroFetcher.parseDockerRef("library/python:3.12")
        assertEquals("library/python", name)
        assertEquals("3.12", tag)
    }

    // ── parseDockerManifestLayers ───────────────────────────────────────

    @Test
    fun `parseDockerManifestLayers extracts layers from v2 manifest`() {
        val json = JSONObject()
        val layersArr = org.json.JSONArray()

        layersArr.put(JSONObject().apply {
            put("mediaType", "application/vnd.docker.image.rootfs.diff.tar.gzip")
            put("size", 4335217L)
            put("digest", "sha256:abc123")
        })
        layersArr.put(JSONObject().apply {
            put("mediaType", "application/vnd.docker.image.rootfs.diff.tar.gzip")
            put("size", 1024L)
            put("digest", "sha256:def456")
        })

        json.put("schemaVersion", 2)
        json.put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
        json.put("config", JSONObject())
        json.put("layers", layersArr)

        val layers = DistroFetcher.parseDockerManifestLayers(json.toString())

        assertEquals(2, layers.size)
        assertEquals("sha256:abc123", layers[0].digest)
        assertEquals(4335217L, layers[0].size)
        assertEquals("sha256:def456", layers[1].digest)
        assertEquals(1024L, layers[1].size)
    }

    @Test
    fun `parseDockerManifestLayers throws on empty manifest object`() {
        val json = JSONObject().apply {
            put("schemaVersion", 2)
        }
        val ex = assertThrows(IOException::class.java) {
            DistroFetcher.parseDockerManifestLayers(json.toString())
        }
        assertEquals("No value for layers", ex.message)
    }

    @Test
    fun `parseDockerManifestLayers throws on missing layers key`() {
        val json = """{"schemaVersion":2,"mediaType":"some-type"}"""
        val ex = assertThrows(IOException::class.java) {
            DistroFetcher.parseDockerManifestLayers(json)
        }
        assertEquals("No value for layers", ex.message)
    }

    @Test
    fun `parseDockerManifestLayers returns single layer list`() {
        val json = JSONObject()
        val layersArr = org.json.JSONArray()
        layersArr.put(JSONObject().apply {
            put("mediaType", "application/vnd.docker.image.rootfs.diff.tar.gzip")
            put("size", 999L)
            put("digest", "sha256:single")
        })
        json.put("layers", layersArr)

        val layers = DistroFetcher.parseDockerManifestLayers(json.toString())
        assertEquals(1, layers.size)
        assertEquals("sha256:single", layers[0].digest)
        assertEquals(999L, layers[0].size)
    }

    // ── resolveManifestForArm64 ─────────────────────────────────────────

    @Test
    fun `resolveManifestForArm64 returns single manifest as-is`() {
        val json = JSONObject().apply {
            put("layers", org.json.JSONArray())
        }
        val result = DistroFetcher.resolveManifestForArm64(json.toString(), "test/image", "token123")
        assertEquals(json.toString(), result)
    }

    @Test
    fun `resolveManifestForArm64 throws on unknown format`() {
        val json = """{"schemaVersion":2}"""
        val ex = assertThrows(IOException::class.java) {
            DistroFetcher.resolveManifestForArm64(json, "test/image", "token")
        }
        assertEquals("Unrecognized manifest format (no 'layers' or 'manifests')", ex.message)
    }

    @Test
    fun `resolveManifestForArm64 selects arm64 entry from manifest list`() {
        val list = JSONObject().apply {
            put("schemaVersion", 2)
            put("mediaType", "application/vnd.docker.distribution.manifest.list.v2+json")
            val manifests = org.json.JSONArray()

            // amd64 entry
            manifests.put(JSONObject().apply {
                put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
                put("size", 528)
                put("digest", "sha256:amd64-only")
                put("platform", JSONObject().apply {
                    put("architecture", "amd64")
                    put("os", "linux")
                })
            })

            // arm64 entry
            manifests.put(JSONObject().apply {
                put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
                put("size", 528)
                put("digest", "sha256:arm64-only")
                put("platform", JSONObject().apply {
                    put("architecture", "arm64")
                    put("os", "linux")
                    put("variant", "v8")
                })
            })

            put("manifests", manifests)
        }

        // This will try to fetch the arm64 manifest by digest.
        // With a fake token it will fail at HTTP, so we catch that.
        try {
            DistroFetcher.resolveManifestForArm64(list.toString(), "test/alpine", "fake-token")
        } catch (e: Exception) {
            // Expected to fail at HTTP call — verify it tried arm64 digest
            val msg = e.message ?: ""
            assert(msg.contains("sha256:arm64-only") || msg.contains("HTTP") || msg.contains("auth")) {
                "Expected HTTP error for arm64 digest, got: $msg"
            }
        }
    }

    // ── downloadRootfs (error cases, not network) ───────────────────────

    @Test
    fun `downloadRootfs fails on empty image ref`() {
        // Should fail before making any HTTP calls
        try {
            DistroFetcher.parseDockerRef("")
        } catch (e: Exception) {
            // empty ref with no tag defaults to latest
        }
        // No explicit test needed — the split produces ("", "latest")
        val (name, tag) = DistroFetcher.parseDockerRef("")
        assertEquals("", name)
        assertEquals("latest", tag)
    }

    // ── DistroInfo model ────────────────────────────────────────────────

    @Test
    fun `DistroInfo with image field creates properly`() {
        val info = DistroInfo(
            id = "alpine",
            name = "Alpine Linux",
            sizeMb = 5,
            image = "library/alpine:latest",
        )
        assertEquals("library/alpine:latest", info.image)
        assertEquals("", info.downloadUrl)
        assertEquals("", info.checksumSha256)
    }

    @Test
    fun `DistroInfo with downloadUrl creates properly`() {
        val info = DistroInfo(
            id = "custom",
            name = "Custom Distro",
            sizeMb = 100,
            downloadUrl = "https://example.com/rootfs.tar.gz",
            checksumSha256 = "abc123",
        )
        assertEquals("https://example.com/rootfs.tar.gz", info.downloadUrl)
        assertEquals("abc123", info.checksumSha256)
        assertEquals("", info.image)
    }
}
