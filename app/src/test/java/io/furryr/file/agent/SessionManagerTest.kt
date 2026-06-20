package io.furryr.file.agent

import com.termux.terminal.TerminalSession
import io.mockk.clearAllMocks
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionManagerTest {

    // ── Session model tests ───────────────────────────────────────────

    @Test
    fun `SessionType AppShell has default command`() {
        val t = SessionManager.SessionType.AppShell()
        assertEquals("/system/bin/sh", t.command)
    }

    @Test
    fun `SessionType AppShell accepts custom command`() {
        val t = SessionManager.SessionType.AppShell("/bin/bash")
        assertEquals("/bin/bash", t.command)
    }

    @Test
    fun `SessionType Container holds containerName`() {
        val t = SessionManager.SessionType.Container("alpine-dev")
        assertEquals("alpine-dev", t.containerName)
    }

    @Test
    fun `SessionType RootPty defaults useShizuku to false`() {
        val t = SessionManager.SessionType.RootPty()
        assertTrue(!t.useShizuku)
    }

    @Test
    fun `SessionType RootPty accepts useShizuku flag`() {
        val t = SessionManager.SessionType.RootPty(useShizuku = true)
        assertTrue(t.useShizuku)
    }

    @Test
    fun `SessionInfo holds all fields`() {
        val session = mockk<TerminalSession>(relaxed = true)
        val bridge = mockk<PtyBridge>(relaxed = true)
        val now = System.currentTimeMillis()

        val info = SessionManager.SessionInfo(
            id = "test-id",
            session = session,
            type = SessionManager.SessionType.AppShell(),
            ptyBridge = bridge,
            createdAt = now
        )

        assertEquals("test-id", info.id)
        assertEquals(session, info.session)
        assertTrue(info.type is SessionManager.SessionType.AppShell)
        assertEquals(bridge, info.ptyBridge)
        assertEquals(now, info.createdAt)
    }

    @Test
    fun `SessionInfo Container type has null ptyBridge`() {
        val session = mockk<TerminalSession>(relaxed = true)

        val info = SessionManager.SessionInfo(
            id = "container-session",
            session = session,
            type = SessionManager.SessionType.Container("ubuntu"),
            ptyBridge = null,
            createdAt = 1000L
        )

        assertEquals("container-session", info.id)
        assertTrue(info.type is SessionManager.SessionType.Container)
        assertNull(info.ptyBridge)
    }

    // ── SessionManager map query tests ────────────────────────────────

    @Before
    fun setUp() {
        clearAllMocks()
        SessionManager.destroyAll()
    }

    @After
    fun tearDown() {
        SessionManager.destroyAll()
        clearAllMocks()
    }

    @Test
    fun `getSession returns null for unknown id`() {
        val result = SessionManager.getSession("nonexistent")
        assertNull(result)
    }

    @Test
    fun `getAllSessions returns empty list initially`() {
        val all = SessionManager.getAllSessions()
        assertTrue(all.isEmpty())
    }

    @Test
    fun `getAllSessionInfos returns empty list initially`() {
        val infos = SessionManager.getAllSessionInfos()
        assertTrue(infos.isEmpty())
    }

    @Test
    fun `destroySession fails for unknown id`() {
        val result = SessionManager.destroySession("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun `destroyAll on empty map does not throw`() {
        SessionManager.destroyAll()
        assertTrue(SessionManager.getAllSessions().isEmpty())
    }
}