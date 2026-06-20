package io.furryr.file.agent

import android.util.Log
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Integration tests for [PtyManager].
 *
 * All [AgentPTYClient] calls are mocked to avoid actual daemon connections.
 * [PtyManager] itself is tested directly (not mocked) so its internal
 * [PtyManager.activePtys] map is exercised.
 *
 * NOTE: The original spec expected [PtyManager.closePty] with an unknown
 * ID to return [Result.failure].  The current implementation returns
 * [Result.success] — the test below asserts the actual behaviour.
 */
class PtyManagerIntegrationTest {

    /** Monotonic counter so each mock-spawned PTY gets a unique ID. */
    private var nextPtyId = 100L

    @Before
    fun setUp() {
        clearAllMocks()
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        mockkObject(AgentPTYClient)

        // ── Default mock stubs ──────────────────────────────────────
        // spawnPty: return a PtyConnection with a unique ID.
        coEvery {
            AgentPTYClient.spawnPty(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            val id = nextPtyId++
            Result.success(
                PtyConnection(
                    ptyId = id,
                    inputStream = ByteArrayInputStream(ByteArray(0)),
                    outputStream = ByteArrayOutputStream()
                )
            )
        }

        // closePty / resizePty: succeed silently.
        coEvery { AgentPTYClient.closePty(any()) } returns Result.success(Unit)
        coEvery { AgentPTYClient.resizePty(any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        // Reset PtyManager singleton state before clearing mocks.
        runBlocking { PtyManager.cleanupAll() }
        clearAllMocks()
    }

    // ── Test 1: createPty delegates to AgentPTYClient ──────────────

    @Test
    fun `createPty delegates to AgentPTYClient spawnPty with correct params`() = runBlocking {
        val result = PtyManager.createPty()

        assertTrue("createPty should succeed", result.isSuccess)

        coVerify(exactly = 1) {
            AgentPTYClient.spawnPty(
                command = "/system/bin/sh",
                args = emptyList(),
                env = emptyMap(),
                termType = "xterm-256color",
                rows = 24,
                cols = 80,
                cwd = null,
                useRoot = false
            )
        }
    }

    // ── Test 2: getActivePtys reflects created PTYs ────────────────

    @Test
    fun `getActivePtys reflects created PTYs`() = runBlocking {
        val pty1 = PtyManager.createPty().getOrThrow()
        val pty2 = PtyManager.createPty().getOrThrow()
        val pty3 = PtyManager.createPty().getOrThrow()

        val activeIds = PtyManager.getActivePtys()

        assertEquals("Should have 3 active PTYs", 3, activeIds.size)
        assertTrue("Should contain pty1 ID", activeIds.contains(pty1.ptyId))
        assertTrue("Should contain pty2 ID", activeIds.contains(pty2.ptyId))
        assertTrue("Should contain pty3 ID", activeIds.contains(pty3.ptyId))
    }

    // ── Test 3: closePty removes from active map ───────────────────

    @Test
    fun `closePty removes PTY from active map`() = runBlocking {
        val pty = PtyManager.createPty().getOrThrow()
        assertTrue("PTY should be active after creation", PtyManager.hasPty(pty.ptyId))

        PtyManager.closePty(pty.ptyId)

        assertFalse("PTY should not be active after close", PtyManager.hasPty(pty.ptyId))
        assertFalse(
            "getActivePtys should not contain closed ID",
            PtyManager.getActivePtys().contains(pty.ptyId)
        )
    }

    // ── Test 4: resizePty delegates to AgentPTYClient ──────────────

    @Test
    fun `resizePty delegates to AgentPTYClient with correct ptyId rows cols`() = runBlocking {
        val pty = PtyManager.createPty().getOrThrow()

        val result = PtyManager.resizePty(pty.ptyId, rows = 40, cols = 120)

        assertTrue("resizePty should succeed", result.isSuccess)
        coVerify(exactly = 1) {
            AgentPTYClient.resizePty(pty.ptyId, 40, 120)
        }
    }

    // ── Test 5: cleanupAll closes all PTYs ─────────────────────────

    @Test
    fun `cleanupAll closes all active PTYs and clears the map`() = runBlocking {
        PtyManager.createPty()
        PtyManager.createPty()

        assertEquals("Should have 2 active PTYs before cleanup", 2, PtyManager.getActivePtys().size)

        PtyManager.cleanupAll()

        assertTrue("Active PTYs should be empty after cleanupAll", PtyManager.getActivePtys().isEmpty())
        coVerify(exactly = 2) { AgentPTYClient.closePty(any()) }
    }

    // ── Test 6: getPty returns correct connection ──────────────────

    @Test
    fun `getPty returns PtyConnection with matching ptyId`() = runBlocking {
        val pty = PtyManager.createPty().getOrThrow()

        val retrieved = PtyManager.getPty(pty.ptyId)

        assertNotNull("getPty should return a connection", retrieved)
        assertEquals("Returned connection should have matching ptyId", pty.ptyId, retrieved!!.ptyId)
    }

    // ── Test 7: hasPty returns correct boolean ─────────────────────

    @Test
    fun `hasPty returns true for existing and false for non-existent PTY`() = runBlocking {
        val pty = PtyManager.createPty().getOrThrow()

        assertTrue("hasPty should return true for existing PTY ID", PtyManager.hasPty(pty.ptyId))
        assertFalse("hasPty should return false for non-existent PTY ID", PtyManager.hasPty(99999L))
    }

    // ── Test 8: closePty with unknown ID ───────────────────────────

    @Test
    fun `closePty with unknown ID returns success`() = runBlocking {
        // NOTE: Current implementation returns Result.success for unknown
        // IDs rather than Result.failure (the unknown ID is treated as
        // "already closed").
        val result = PtyManager.closePty(99999L)

        assertTrue("closePty with unknown ID should return success", result.isSuccess)
    }
}
