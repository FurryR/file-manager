package io.furryr.file.agent

import org.junit.Assert.*
import org.junit.Test

class AndroidUriResolverTest {

    // ── resolve: valid URIs ────────────────────────────────────────────

    @Test
    fun `resolve - sdcard path returns allowed not needing root`() {
        val result = AndroidUriResolver.resolve("android:///sdcard/Download/test.txt")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("android", resolved.resolvedPath.scheme)
        assertEquals("/storage/emulated/0/Download/test.txt", resolved.resolvedPath.physicalPath)
        assertTrue(resolved.allowed)
        assertFalse(resolved.needsRoot)
    }

    @Test
    fun `resolve - system path returns allowed needing root`() {
        val result = AndroidUriResolver.resolve("android:///system/build.prop")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/system/build.prop", resolved.resolvedPath.physicalPath)
        assertTrue(resolved.allowed)
        assertTrue(resolved.needsRoot)
    }

    @Test
    fun `resolve - own app data returns allowed not needing root`() {
        val result = AndroidUriResolver.resolve("android:///data/data/io.furryr.file/cache")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/data/data/io.furryr.file/cache", resolved.resolvedPath.physicalPath)
        assertTrue(resolved.allowed)
        assertFalse(resolved.needsRoot)
    }

    @Test
    fun `resolve - other app data returns not allowed needing root`() {
        val result = AndroidUriResolver.resolve("android:///data/data/com.other.app/shared_prefs")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/data/data/com.other.app/shared_prefs", resolved.resolvedPath.physicalPath)
        assertFalse(resolved.allowed)
        assertTrue(resolved.needsRoot)
    }

    @Test
    fun `resolve - sdcard root resolves to storage`() {
        val result = AndroidUriResolver.resolve("android:///sdcard")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/storage/emulated/0", resolved.resolvedPath.physicalPath)
        assertTrue(resolved.allowed)
        assertFalse(resolved.needsRoot)
    }

    @Test
    fun `resolve - storage path directly`() {
        val result = AndroidUriResolver.resolve("android:///storage/emulated/0/Documents")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/storage/emulated/0/Documents", resolved.resolvedPath.physicalPath)
        assertTrue(resolved.allowed)
        assertFalse(resolved.needsRoot)
    }

    // ── resolve: invalid URIs ──────────────────────────────────────────

    @Test
    fun `resolve - rejects non-android scheme`() {
        val result = AndroidUriResolver.resolve("file:///sdcard/test.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolve - rejects content scheme`() {
        val result = AndroidUriResolver.resolve("content://media/external/images/1")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolve - rejects URI without scheme separator`() {
        val result = AndroidUriResolver.resolve("/sdcard/test.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolve - rejects empty path`() {
        val result = AndroidUriResolver.resolve("android:///")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolve - rejects blank path`() {
        val result = AndroidUriResolver.resolve("android:///   ")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolve - rejects empty string`() {
        val result = AndroidUriResolver.resolve("")
        assertTrue(result.isFailure)
    }

    // ── resolve: traversal URIs ────────────────────────────────────────

    @Test
    fun `resolve - traversal escaping sdcard`() {
        // /sdcard/../../system canonicalizes to /system
        val result = AndroidUriResolver.resolve("android:///sdcard/../../system/build.prop")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/system/build.prop", resolved.resolvedPath.physicalPath)
        // Still allowed (/system is allowed), and needs root
        assertTrue(resolved.allowed)
        assertTrue(resolved.needsRoot)
    }

    @Test
    fun `resolve - traversal going above root`() {
        // /../../etc/passwd canonicalizes to /etc/passwd (.. above root is ignored)
        val result = AndroidUriResolver.resolve("android:///../../etc/passwd")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("/etc/passwd", resolved.resolvedPath.physicalPath)
        // /etc/passwd is not in the allow-list
        assertFalse(resolved.allowed)
        assertFalse(resolved.needsRoot)
    }

    // ── isAllowed ──────────────────────────────────────────────────────

    @Test
    fun `isAllowed - sdcard paths allowed`() {
        assertTrue(AndroidUriResolver.isAllowed("android:///sdcard/Download"))
        assertTrue(AndroidUriResolver.isAllowed("android:///sdcard"))
    }

    @Test
    fun `isAllowed - storage paths allowed`() {
        assertTrue(AndroidUriResolver.isAllowed("android:///storage/emulated/0/Documents"))
    }

    @Test
    fun `isAllowed - system paths allowed`() {
        assertTrue(AndroidUriResolver.isAllowed("android:///system/build.prop"))
    }

    @Test
    fun `isAllowed - own app data allowed`() {
        assertTrue(AndroidUriResolver.isAllowed("android:///data/data/io.furryr.file/cache"))
    }

    @Test
    fun `isAllowed - other app data rejected`() {
        assertFalse(AndroidUriResolver.isAllowed("android:///data/data/com.other.app/secret"))
    }

    @Test
    fun `isAllowed - traversal escaping sdcard rejected`() {
        assertFalse(AndroidUriResolver.isAllowed("android:///sdcard/../../system"))
    }

    @Test
    fun `isAllowed - traversal to etc rejected`() {
        assertFalse(AndroidUriResolver.isAllowed("android:///sdcard/../../../etc/passwd"))
    }

    @Test
    fun `isAllowed - invalid URI returns false`() {
        assertFalse(AndroidUriResolver.isAllowed("not-a-uri"))
        assertFalse(AndroidUriResolver.isAllowed(""))
    }

    // ── needsRoot ──────────────────────────────────────────────────────

    @Test
    fun `needsRoot - system paths need root`() {
        assertTrue(AndroidUriResolver.needsRoot("android:///system/build.prop"))
        assertTrue(AndroidUriResolver.needsRoot("android:///system"))
    }

    @Test
    fun `needsRoot - other app data needs root`() {
        assertTrue(AndroidUriResolver.needsRoot("android:///data/data/com.other.app/files"))
    }

    @Test
    fun `needsRoot - own app data does not need root`() {
        assertFalse(AndroidUriResolver.needsRoot("android:///data/data/io.furryr.file/cache"))
        assertFalse(AndroidUriResolver.needsRoot("android:///data/data/io.furryr.file"))
    }

    @Test
    fun `needsRoot - sdcard paths do not need root`() {
        assertFalse(AndroidUriResolver.needsRoot("android:///sdcard/Download"))
        assertFalse(AndroidUriResolver.needsRoot("android:///storage/emulated/0"))
    }

    @Test
    fun `needsRoot - invalid URI returns false`() {
        assertFalse(AndroidUriResolver.needsRoot("not-a-uri"))
        assertFalse(AndroidUriResolver.needsRoot(""))
    }

    // ── Integration: resolve + isAllowed + needsRoot consistency ────────

    @Test
    fun `integration - resolve isAllowed matches isAllowed string API for sdcard`() {
        val resolved = AndroidUriResolver.resolve("android:///sdcard/Download/test.txt")
            .getOrThrow()
        assertEquals(resolved.allowed, AndroidUriResolver.isAllowed("android:///sdcard/Download/test.txt"))
    }

    @Test
    fun `integration - resolve needsRoot matches needsRoot string API for system`() {
        val resolved = AndroidUriResolver.resolve("android:///system/build.prop")
            .getOrThrow()
        assertEquals(resolved.needsRoot, AndroidUriResolver.needsRoot("android:///system/build.prop"))
    }

    @Test
    fun `integration - resolve needsRoot matches needsRoot string API for other app data`() {
        val resolved = AndroidUriResolver.resolve("android:///data/data/com.other.app/secret")
            .getOrThrow()
        assertTrue(resolved.needsRoot)
        assertTrue(AndroidUriResolver.needsRoot("android:///data/data/com.other.app/secret"))
    }

    @Test
    fun `integration - traversal detected by isAllowed but resolve still succeeds`() {
        // Traversal URIs are rejected by isAllowed but the path resolution still works
        val result = AndroidUriResolver.resolve("android:///sdcard/../../system")
        assertTrue(result.isSuccess)
        assertFalse(AndroidUriResolver.isAllowed("android:///sdcard/../../system"))
    }
}
