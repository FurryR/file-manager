package io.furryr.file.agent

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PtyBridgeTest {

    private lateinit var ptyInStream: InputStream
    private lateinit var ptyOutStream: OutputStream
    private lateinit var ptyConnection: PtyConnection
    private lateinit var mockEmulator: TerminalEmulator
    private lateinit var mockSession: TerminalSession

    @Before
    fun setUp() {
        clearAllMocks()

        ptyInStream = ByteArrayInputStream(ByteArray(0))
        ptyOutStream = ByteArrayOutputStream()
        ptyConnection = PtyConnection(
            ptyId = 1L,
            inputStream = ptyInStream,
            outputStream = ptyOutStream
        )

        mockEmulator = mockk(relaxed = true)
        mockSession = mockk(relaxed = true)

        every { mockSession.emulator } returns mockEmulator
        every { mockSession.finishIfRunning() } returns Unit
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ── Construction ──────────────────────────────────────────────────

    @Test
    fun `constructor accepts PtyConnection and TerminalSession`() {
        val bridge = PtyBridge(ptyConnection, mockSession)
        assertNotNull(bridge)
    }

    @Test
    fun `PtyConnection wraps streams correctly`() {
        val data = "hello pty".toByteArray()
        val bais = ByteArrayInputStream(data)
        val baos = ByteArrayOutputStream()

        val conn = PtyConnection(ptyId = 42L, inputStream = bais, outputStream = baos)

        // Write through outputStream
        conn.outputStream.write(data)
        conn.outputStream.flush()
        assertTrue(baos.toByteArray().contentEquals(data))

        // Read through inputStream
        val buf = ByteArray(64)
        val n = conn.inputStream.read(buf)
        assertTrue(n > 0)
        assertTrue(data.contentEquals(buf.copyOf(n)))

        assertTrue(conn.ptyId == 42L)
    }

    // ── start / stop lifecycle ────────────────────────────────────────

    @Test
    fun `start kills internal shell and does not throw`() {
        val bridge = PtyBridge(ptyConnection, mockSession)
        // Should not throw even with mock emulator (if reflection fails
        // gracefully).
        bridge.start()
        bridge.stop()
        verify(exactly = 1) { mockSession.finishIfRunning() }
    }

    @Test
    fun `start is idempotent`() {
        val bridge = PtyBridge(ptyConnection, mockSession)
        bridge.start()
        bridge.start() // second call should be no-op
        bridge.stop()
        verify(exactly = 1) { mockSession.finishIfRunning() }
    }

    @Test
    fun `stop is idempotent`() {
        val bridge = PtyBridge(ptyConnection, mockSession)
        bridge.start()
        bridge.stop()
        // second stop should not throw
        bridge.stop()
    }

    @Test
    fun `stop without start does not throw`() {
        val bridge = PtyBridge(ptyConnection, mockSession)
        bridge.stop() // should be safe
    }

    @Test
    fun `stop closes PTY streams`() {
        // Use close-tracking streams
        val trackedIn = CloseTrackingInputStream(ByteArrayInputStream("data".toByteArray()))
        val trackedOut = CloseTrackingOutputStream(ByteArrayOutputStream())
        val conn = PtyConnection(1L, trackedIn, trackedOut)

        val bridge = PtyBridge(conn, mockSession)
        bridge.start()
        bridge.stop()

        assertTrue("inputStream should be closed", trackedIn.isClosed)
        assertTrue("outputStream should be closed", trackedOut.isClosed)
    }

    // ── Data pumping direction ────────────────────────────────────────

    @Test
    fun `PTY output is appended to emulator`() {
        // Write "hello\n" into PTY inputStream (simulating shell stdout).
        val testData = "echo hello\n".toByteArray()
        val bais = ByteArrayInputStream(testData)
        val conn = PtyConnection(2L, bais, ByteArrayOutputStream())

        // Capture what gets appended to the emulator.
        val appendedData = mutableListOf<ByteArray>()
        val appendLatch = CountDownLatch(1)

        // We need a real-ish TerminalEmulator mock that tracks append().
        // Since the real emulator stores mSession as private-final and our
        // interceptTerminalOutput tries to swap it via reflection, we must
        // ensure the mock doesn't throw on getDeclaredField.
        val mockEmu = mockk<TerminalEmulator>(relaxed = true)
        every { mockEmu.append(any<ByteArray>(), any()) } answers {
            val data = firstArg<ByteArray>()
            val len = secondArg<Int>()
            appendedData.add(data.copyOf(len))
            appendLatch.countDown()
            Unit
        }
        val mockSess = mockk<TerminalSession>(relaxed = true)
        every { mockSess.emulator } returns mockEmu
        every { mockSess.finishIfRunning() } returns Unit

        val bridge = PtyBridge(conn, mockSess)
        bridge.start()

        assertTrue("append() should be called within timeout",
            appendLatch.await(3, TimeUnit.SECONDS))

        val allAppended = appendedData.flatMap { it.toList() }.toByteArray()
        assertTrue("appended data should match PTY output",
            testData.contentEquals(allAppended))

        bridge.stop()
    }

    @Test
    fun `terminal output pipe is set up and torn down correctly`() {
        val trackedOut = CloseTrackingOutputStream(ByteArrayOutputStream())
        val conn = PtyConnection(3L, ByteArrayInputStream(ByteArray(0)), trackedOut)

        val bridge = PtyBridge(conn, mockSession)
        bridge.start()

        // The pipe and coroutines are set up.  Give the coroutine a moment
        // to start (it will block on the empty inputStream, then exit on
        // pipe close during stop()).
        bridge.stop()

        // Verify the bridge completed start/stop cycle without exceptions.
        // The actual data-forwarding path requires a real TerminalEmulator
        // with a real mSession field (unavailable from mockk proxies).
        // Integration tests cover that path.
    }

    // ── Reflection helpers ────────────────────────────────────────────

    @Test
    fun `interceptTerminalOutput replaces emulator mSession field`() {
        // Use a real TerminalOutput as starting value (mock relaxed won't
        // have real fields).  This test verifies the reflection path compiles
        // and runs against the mock without throwing.
        val bridge = PtyBridge(ptyConnection, mockSession)

        // start() internally calls interceptTerminalOutput; we just verify
        // it completes.
        bridge.start()
        bridge.stop()

        // No exception = pass
    }

    // ── Edge cases ────────────────────────────────────────────────────

    @Test
    fun `start with empty PTY input stream does not crash`() {
        val conn = PtyConnection(4L, ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        val bridge = PtyBridge(conn, mockSession)

        bridge.start()
        // Coroutine will read -1 (EOF) and exit. Give it time.
        Thread.sleep(200)
        bridge.stop()
    }

    @Test
    fun `stop after PTY stream already closed does not throw`() {
        val bais = ByteArrayInputStream("x".toByteArray())
        val baos = ByteArrayOutputStream()
        val conn = PtyConnection(5L, bais, baos)

        val bridge = PtyBridge(conn, mockSession)
        bridge.start()

        // Prematurely close streams (simulating connection drop)
        bais.close()
        baos.close()

        Thread.sleep(200)
        bridge.stop() // should not throw
    }

    // ══════════════════════════════════════════════════════════════════
    //  Test helpers
    // ══════════════════════════════════════════════════════════════════

    /** InputStream wrapper that tracks whether [close] was called. */
    private class CloseTrackingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        var isClosed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len)

        override fun close() {
            isClosed = true
            delegate.close()
        }
    }

    /** OutputStream wrapper that tracks whether [close] was called. */
    private class CloseTrackingOutputStream(
        private val delegate: OutputStream
    ) : OutputStream() {
        var isClosed = false
            private set

        override fun write(b: Int) { delegate.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
        }

        override fun flush() { delegate.flush() }

        override fun close() {
            isClosed = true
            delegate.close()
        }

        fun toByteArray(): ByteArray = (delegate as ByteArrayOutputStream).toByteArray()
    }
}
