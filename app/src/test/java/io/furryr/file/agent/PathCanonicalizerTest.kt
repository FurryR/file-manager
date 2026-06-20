package io.furryr.file.agent

import org.junit.Assert.*
import org.junit.Test

class PathCanonicalizerTest {

    // ── resolveAndroidUri ─────────────────────────────────────────────

    @Test
    fun `resolveAndroidUri - normal sdcard path`() {
        val result = PathCanonicalizer.resolveAndroidUri("android:///sdcard/Download/test.txt")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("android", resolved.scheme)
        assertEquals("android:///sdcard/Download/test.txt", resolved.originalUri)
        assertEquals("/storage/emulated/0/Download/test.txt", resolved.physicalPath)
        assertFalse(resolved.needsRoot)
    }

    @Test
    fun `resolveAndroidUri - system path needs root`() {
        val result = PathCanonicalizer.resolveAndroidUri("android:///system/build.prop")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/system/build.prop", resolved.physicalPath)
        assertTrue(resolved.needsRoot)
    }

    @Test
    fun `resolveAndroidUri - own app data does not need root`() {
        val result = PathCanonicalizer.resolveAndroidUri("android:///data/data/io.furryr.file/cache")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/data/data/io.furryr.file/cache", resolved.physicalPath)
        assertFalse(resolved.needsRoot)
    }

    @Test
    fun `resolveAndroidUri - other app data needs root`() {
        val result = PathCanonicalizer.resolveAndroidUri("android:///data/data/com.other.app/shared_prefs")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/data/data/com.other.app/shared_prefs", resolved.physicalPath)
        assertTrue(resolved.needsRoot)
    }

    @Test
    fun `resolveAndroidUri - rejects non-android scheme`() {
        val result = PathCanonicalizer.resolveAndroidUri("file:///sdcard/test.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveAndroidUri - rejects URI without scheme separator`() {
        val result = PathCanonicalizer.resolveAndroidUri("/sdcard/test.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveAndroidUri - rejects empty path`() {
        val result = PathCanonicalizer.resolveAndroidUri("android:///")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveAndroidUri - sdcard root resolves to storage`() {
        val result = PathCanonicalizer.resolveAndroidUri("android:///sdcard")
        assertTrue(result.isSuccess)
        assertEquals("/storage/emulated/0", result.getOrThrow().physicalPath)
    }

    // ── canonicalize ──────────────────────────────────────────────────

    @Test
    fun `canonicalize - resolves dot-dot parent traversal`() {
        assertEquals("/sdcard/bar", PathCanonicalizer.canonicalize("/sdcard/foo/../bar"))
        assertEquals("/sdcard", PathCanonicalizer.canonicalize("/sdcard/foo/.."))
    }

    @Test
    fun `canonicalize - resolves dot current directory`() {
        assertEquals("/sdcard/bar", PathCanonicalizer.canonicalize("/sdcard/./bar"))
        assertEquals("/sdcard/bar", PathCanonicalizer.canonicalize("/sdcard/././bar"))
    }

    @Test
    fun `canonicalize - collapses duplicate slashes`() {
        assertEquals("/sdcard/foo", PathCanonicalizer.canonicalize("//sdcard///foo/"))
        assertEquals("/", PathCanonicalizer.canonicalize("////"))
    }

    @Test
    fun `canonicalize - dot-dot above root is ignored`() {
        assertEquals("/etc/passwd", PathCanonicalizer.canonicalize("/../../etc/passwd"))
    }

    @Test
    fun `canonicalize - blank path returns root`() {
        assertEquals("/", PathCanonicalizer.canonicalize(""))
        assertEquals("/", PathCanonicalizer.canonicalize("   "))
    }

    // ── isPathAllowed ─────────────────────────────────────────────────

    @Test
    fun `isPathAllowed - sdcard and storage paths allowed`() {
        assertTrue(PathCanonicalizer.isPathAllowed("android:///sdcard/Download"))
        assertTrue(PathCanonicalizer.isPathAllowed("android:///storage/emulated/0/Documents"))
        assertTrue(PathCanonicalizer.isPathAllowed("android:///sdcard"))
    }

    @Test
    fun `isPathAllowed - system path allowed`() {
        assertTrue(PathCanonicalizer.isPathAllowed("android:///system/build.prop"))
    }

    @Test
    fun `isPathAllowed - own app data allowed`() {
        assertTrue(PathCanonicalizer.isPathAllowed("android:///data/data/io.furryr.file/cache"))
        assertTrue(PathCanonicalizer.isPathAllowed("android:///data/data/io.furryr.file"))
    }

    @Test
    fun `isPathAllowed - other app data rejected`() {
        assertFalse(PathCanonicalizer.isPathAllowed("android:///data/data/com.other.app/secret"))
    }

    @Test
    fun `isPathAllowed - traversal escaping sdcard rejected`() {
        // /sdcard/../../system canonicalizes to /system — traversal detected on raw path
        assertFalse(PathCanonicalizer.isPathAllowed("android:///sdcard/../../system"))
    }

    @Test
    fun `isPathAllowed - traversal escaping to etc rejected`() {
        assertFalse(PathCanonicalizer.isPathAllowed("android:///sdcard/../../../etc/passwd"))
    }

    // ── detectPathTraversal ───────────────────────────────────────────

    @Test
    fun `detectPathTraversal - escapes sdcard root`() {
        assertTrue(PathCanonicalizer.detectPathTraversal("sdcard/../../system"))
    }

    @Test
    fun `detectPathTraversal - escapes to etc passwd`() {
        assertTrue(PathCanonicalizer.detectPathTraversal("../../etc/passwd"))
    }

    @Test
    fun `detectPathTraversal - stays within sdcard`() {
        assertFalse(PathCanonicalizer.detectPathTraversal("sdcard/foo/../bar"))
    }

    @Test
    fun `detectPathTraversal - normal path no traversal`() {
        assertFalse(PathCanonicalizer.detectPathTraversal("/sdcard/Download/test.txt"))
        assertFalse(PathCanonicalizer.detectPathTraversal("/system/build.prop"))
    }

    @Test
    fun `detectPathTraversal - detects dip below root with leading dots`() {
        assertTrue(PathCanonicalizer.detectPathTraversal("/../etc/passwd"))
    }

    // ── needsRoot ─────────────────────────────────────────────────────

    @Test
    fun `needsRoot - system paths require root`() {
        assertTrue(PathCanonicalizer.needsRoot("/system/build.prop"))
        assertTrue(PathCanonicalizer.needsRoot("/system"))
    }

    @Test
    fun `needsRoot - other app data requires root`() {
        assertTrue(PathCanonicalizer.needsRoot("/data/data/com.other.app/files"))
    }

    @Test
    fun `needsRoot - own app data does not require root`() {
        assertFalse(PathCanonicalizer.needsRoot("/data/data/io.furryr.file/cache"))
        assertFalse(PathCanonicalizer.needsRoot("/data/data/io.furryr.file"))
    }

    @Test
    fun `needsRoot - sdcard paths do not require root`() {
        assertFalse(PathCanonicalizer.needsRoot("/sdcard/Download"))
        assertFalse(PathCanonicalizer.needsRoot("/storage/emulated/0"))
    }

    // ── Integration: resolve + allow-list ─────────────────────────────

    @Test
    fun `integration - resolved sdcard path passes allow-list`() {
        val resolved = PathCanonicalizer.resolveAndroidUri("android:///sdcard/Download/test.txt")
            .getOrThrow()
        assertTrue(PathCanonicalizer.isPathAllowed(resolved))
    }

    @Test
    fun `integration - resolved other app data fails allow-list despite root`() {
        val resolved = PathCanonicalizer.resolveAndroidUri("android:///data/data/com.other.app/secret")
            .getOrThrow()
        assertTrue(resolved.needsRoot)
        assertFalse(PathCanonicalizer.isPathAllowed(resolved))
    }

    @Test
    fun `integration - resolved own app data passes allow-list`() {
        val resolved = PathCanonicalizer.resolveAndroidUri("android:///data/data/io.furryr.file/cache")
            .getOrThrow()
        assertFalse(resolved.needsRoot)
        assertTrue(PathCanonicalizer.isPathAllowed(resolved))
    }
}
