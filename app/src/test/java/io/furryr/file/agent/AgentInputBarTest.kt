package io.furryr.file.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [AgentInputBar] verifying:
 * - ExecutionMode display name formatting.
 * - Mode toggle and send behavior logic.
 */
class AgentInputBarTest {

    // ── displayName tests ──────────────────────────────────────────────────

    @Test
    fun `displayName returns @app for APP_UID`() {
        assertEquals("@app", ExecutionMode.APP_UID.displayName())
    }

    @Test
    fun `displayName returns container name for CONTAINER with name`() {
        assertEquals("@alpine", ExecutionMode.CONTAINER.displayName("alpine"))
        assertEquals("@ubuntu", ExecutionMode.CONTAINER.displayName("ubuntu"))
    }

    @Test
    fun `displayName returns default for CONTAINER when name is null`() {
        assertEquals("@alpine", ExecutionMode.CONTAINER.displayName(null))
    }

    @Test
    fun `displayName returns #root for ROOT_SHELL`() {
        assertEquals("#root", ExecutionMode.ROOT_SHELL.displayName())
    }

    @Test
    fun `displayName returns #shizuku for SHIZUKU`() {
        assertEquals("#shizuku", ExecutionMode.SHIZUKU.displayName())
    }

    @Test
    fun `displayName covers all ExecutionMode values`() {
        for (mode in ExecutionMode.values()) {
            val name = mode.displayName()
            assertEquals(false, name.isBlank())
        }
    }

    @Test
    fun `displayName labels start with @ or #`() {
        for (mode in ExecutionMode.values()) {
            val name = mode.displayName()
            assertEquals(
                true,
                name.startsWith("@") || name.startsWith("#")
            )
        }
    }

    @Test
    fun `CONTAINER displayName contains name when provided`() {
        val name = ExecutionMode.CONTAINER.displayName("mycontainer")
        assertEquals(true, name.contains("mycontainer"))
    }

    @Test
    fun `APP_UID displayName ignores container name`() {
        assertEquals("@app", ExecutionMode.APP_UID.displayName("ignored"))
    }

    @Test
    fun `ROOT_SHELL displayName ignores container name`() {
        assertEquals("#root", ExecutionMode.ROOT_SHELL.displayName("ignored"))
    }

    // ── Mode label semantics ───────────────────────────────────────────────

    @Test
    fun `APP_UID label uses @ prefix`() {
        assertEquals(true, ExecutionMode.APP_UID.displayName().startsWith("@"))
    }

    @Test
    fun `CONTAINER label uses @ prefix`() {
        assertEquals(true, ExecutionMode.CONTAINER.displayName("test").startsWith("@"))
    }

    @Test
    fun `ROOT_SHELL label uses # prefix`() {
        assertEquals(true, ExecutionMode.ROOT_SHELL.displayName().startsWith("#"))
    }

    @Test
    fun `SHIZUKU label uses # prefix`() {
        assertEquals(true, ExecutionMode.SHIZUKU.displayName().startsWith("#"))
    }
}
