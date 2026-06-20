package io.furryr.file.agent

import org.junit.Assert.*
import org.junit.Test

class ContainerUriResolverTest {

    // ── resolveContainerUri — normal cases ─────────────────────────────

    @Test
    fun `resolveContainerUri - alpine home test`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///alpine/home/test")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("alpine", resolved.containerName)
        assertEquals("/home/test", resolved.containerPath)
        assertEquals("container:///alpine/home/test", resolved.originalUri)
    }

    @Test
    fun `resolveContainerUri - my-container etc hosts`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///my-container/etc/hosts")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("my-container", resolved.containerName)
        assertEquals("/etc/hosts", resolved.containerPath)
        assertEquals("container:///my-container/etc/hosts", resolved.originalUri)
    }

    @Test
    fun `resolveContainerUri - ubuntu root path`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///ubuntu/")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("ubuntu", resolved.containerName)
        assertEquals("/", resolved.containerPath)
    }

    // ── resolveContainerUri — container name variants ──────────────────

    @Test
    fun `resolveContainerUri - uppercase and digits`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///ALPINE-123/usr/bin")
        assertTrue(result.isSuccess)
        assertEquals("ALPINE-123", result.getOrThrow().containerName)
        assertEquals("/usr/bin", result.getOrThrow().containerPath)
    }

    @Test
    fun `resolveContainerUri - single char name`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///a/bin/sh")
        assertTrue(result.isSuccess)
        assertEquals("a", result.getOrThrow().containerName)
        assertEquals("/bin/sh", result.getOrThrow().containerPath)
    }

    // ── resolveContainerUri — root URI ────────────────────────────────────

    @Test
    fun `resolveContainerUri - root URI`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertNull(resolved.containerName)
        assertEquals("/", resolved.containerPath)
        assertEquals("container:///", resolved.originalUri)
    }

    @Test
    fun `resolveContainerUri - root URI bare scheme`() {
        val result = ContainerUriResolver.resolveContainerUri("container://")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertNull(resolved.containerName)
        assertEquals("/", resolved.containerPath)
    }

    @Test
    fun `resolveContainerUri - container name only no extra slash`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///ubuntu")
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("ubuntu", resolved.containerName)
        assertEquals("/", resolved.containerPath)
    }

    // ── resolveContainerUri — edge cases (failure) ────────────────────

    @Test
    fun `resolveContainerUri - invalid scheme`() {
        val result = ContainerUriResolver.resolveContainerUri("file:///etc/hosts")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveContainerUri - no scheme separator`() {
        val result = ContainerUriResolver.resolveContainerUri("alpine/home/test")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveContainerUri - empty string`() {
        val result = ContainerUriResolver.resolveContainerUri("")
        assertTrue(result.isFailure)
    }

    // ── resolveContainerUri — container name validation failures ───────

    @Test
    fun `resolveContainerUri - name with spaces fails`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///my container/path")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveContainerUri - name with underscore fails`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///my_container/path")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveContainerUri - name with special chars fails`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///alpine!test/path")
        assertTrue(result.isFailure)
    }

    @Test
    fun `resolveContainerUri - name starting with hyphen fails`() {
        val result = ContainerUriResolver.resolveContainerUri("container:///-test/path")
        assertTrue(result.isFailure)
    }

    // ── isValidContainerName ──────────────────────────────────────────

    @Test
    fun `isValidContainerName - simple name`() {
        assertTrue(ContainerUriResolver.isValidContainerName("alpine"))
    }

    @Test
    fun `isValidContainerName - name with hyphens`() {
        assertTrue(ContainerUriResolver.isValidContainerName("my-container"))
    }

    @Test
    fun `isValidContainerName - name with digits and hyphens`() {
        assertTrue(ContainerUriResolver.isValidContainerName("ubuntu-22-04"))
    }

    @Test
    fun `isValidContainerName - uppercase`() {
        assertTrue(ContainerUriResolver.isValidContainerName("ALPINE"))
    }

    @Test
    fun `isValidContainerName - empty string`() {
        assertFalse(ContainerUriResolver.isValidContainerName(""))
    }

    @Test
    fun `isValidContainerName - whitespace`() {
        assertFalse(ContainerUriResolver.isValidContainerName("  "))
    }

    @Test
    fun `isValidContainerName - special characters`() {
        assertFalse(ContainerUriResolver.isValidContainerName("alpine!"))
        assertFalse(ContainerUriResolver.isValidContainerName("my_container"))
        assertFalse(ContainerUriResolver.isValidContainerName("test@name"))
        assertFalse(ContainerUriResolver.isValidContainerName("test.name"))
    }

    // ── ContainerUriResult data class ─────────────────────────────────

    @Test
    fun `ContainerUriResult - stores all fields correctly`() {
        val result = ContainerUriResult(
            containerName = "alpine",
            containerPath = "/home/test",
            originalUri = "container:///alpine/home/test"
        )
        assertEquals("alpine", result.containerName)
        assertEquals("/home/test", result.containerPath)
        assertEquals("container:///alpine/home/test", result.originalUri)
    }
}
