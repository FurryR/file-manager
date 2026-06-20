package io.furryr.file.agent

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Bridges bidirectional I/O between a daemon-created PTY master
 * ([PtyConnection]) and a Termux [TerminalSession] for display and
 * input handling.
 *
 * ## Data flow
 *
 * ```
 * PTY master stdout --read--> emulator.append() --> terminal display
 * Terminal user input --> TerminalOutput (pipe) --> PTY master stdin
 * ```
 *
 * The daemon spawns a shell inside a PTY pair and forwards the master
 * file descriptor to the app via SCM_RIGHTS.  The app wraps it as
 * [PtyConnection] (InputStream / OutputStream).  This bridge connects
 * those streams to a Termux terminal widget so the user can interact
 * with the remote shell.
 *
 * [TerminalSession] v0.118.0+ has an empty constructor (no process, no
 * emulator).  [start] creates its own [TerminalEmulator] and injects
 * it into the session -- the session's internal process management is
 * never touched.  This avoids the trap where a default-0 mShellPid
 * causes [Os.kill(0, SIGKILL)][android.system.Os.kill] to kill the
 * entire app process group.
 *
 * ## Lifecycle
 *
 * 1. Construct with a live [PtyConnection] and a [TerminalSession].
 * 2. Call [start] -- creates an emulator if missing, intercepts
 *    terminal output via reflection, and pumps I/O in two coroutines.
 * 3. Call [stop] when the session should be torn down -- closes pipes
 *    and PTY streams, restores the original [TerminalOutput].
 *
 * @param ptyConnection Wraps the master file descriptor as I/O streams.
 * @param terminalSession A Termux session whose emulator will render
 *        PTY output and capture user input.
 */
class PtyBridge(
    private val ptyConnection: PtyConnection,
    private val terminalSession: TerminalSession
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pipeOut: PipedOutputStream? = null
    private var pipeIn: PipedInputStream? = null
    private var originalEmulatorOutput: TerminalOutput? = null

    /** Whether [start] has been called and [stop] has not yet been called. */
    @Volatile
    private var running = false

    // -- start --------------------------------------------------------

    /**
     * Begin I/O pumping between the daemon PTY and the terminal session.
     *
     * Idempotent: calling [start] on an already-running bridge is a no-op.
     */
    fun start() {
        if (running) return
        running = true

        // TerminalSession v0.118.0+ has an empty constructor -- no
        // emulator, no process.  Create our own emulator if missing.
        val emulator: TerminalEmulator = terminalSession.emulator as? TerminalEmulator
            ?: createAndInjectEmulator(terminalSession, columns = 80, rows = 24)

        // Pipe: terminal writes here -> coroutine reads -> daemon PTY stdin.
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 65536) // 64 KiB buffer
        pipeOut = out
        pipeIn = input

        // Intercept TerminalEmulator output so user keystrokes are routed
        // to our PTY instead of the (now-dead) internal one.
        originalEmulatorOutput = interceptTerminalOutput(emulator, out)

        // Coroutine 1 -- Terminal -> PTY: user keystrokes -> daemon STDIN.
        scope.launch {
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n == -1) break
                    ptyConnection.outputStream.write(buf, 0, n)
                    ptyConnection.outputStream.flush()
                }
            } catch (_: IOException) {
                // stream closed or pipe broken -- graceful exit
            }
        }

        // Coroutine 2 -- PTY -> Terminal: daemon STDOUT -> terminal display.
        scope.launch {
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val n = ptyConnection.inputStream.read(buf)
                    if (n == -1) break
                    emulator.append(buf, n)
                    notifyScreenUpdate()
                }
            } catch (_: IOException) {
                // stream closed -- graceful exit
            }
        }
        }

    // -- stop ---------------------------------------------------------

    /**
     * Stop I/O pumping, restore the terminal's original output target,
     * and release all resources.
     *
     * Idempotent and safe to call from any thread.
     */
    fun stop() {
        if (!running) return
        running = false

        // Unblock pending coroutines by closing the pipe first.
        runCatching { pipeIn?.close() }
        runCatching { pipeOut?.close() }

        // Restore original TerminalOutput so the emulator is left in a
        // consistent state (even if the session will be discarded).
        originalEmulatorOutput?.let { original ->
            try {
                setEmulatorSessionField(terminalSession.emulator, original)
            } catch (_: Exception) { }
        }

        scope.cancel()

        runCatching { ptyConnection.inputStream.close() }
        runCatching { ptyConnection.outputStream.close() }
    }

    // =================================================================
    //  Emulator initialisation (no process management)
    // =================================================================

    /**
     * Create a standalone [TerminalEmulator] and inject it into the
     * [TerminalSession.mEmulator][TerminalSession.getEmulator] field.
     *
     * This is the replacement for [TerminalSession.initializeEmulator]
     * which also spawns a shell process (and whose default-0 mShellPid
     * would cause [android.system.Os.kill] with pid=0 to SIGKILL us).
     *
     * @param session  The terminal session that will render our PTY data.
     * @param columns  Initial terminal width in columns.
     * @param rows     Initial terminal height in rows.
     * @param transcriptRows  Scrollback buffer rows (default 2000).
     */
    private fun createAndInjectEmulator(
        session: TerminalSession,
        columns: Int,
        rows: Int,
        transcriptRows: Int = 2000
    ): TerminalEmulator {
        val client = getSessionClient(session)
        val emulator = TerminalEmulator(session, columns, rows, transcriptRows, client)
        setEmulatorField(session, emulator)
        return emulator
    }

    /** Read [TerminalSession.mClient] via reflection (package-private field). */
    private fun getSessionClient(session: TerminalSession): TerminalSessionClient {
        val field = TerminalSession::class.java.getDeclaredField("mClient")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(session) as TerminalSessionClient
    }

    /** Write [TerminalSession.mEmulator], removing the `final` modifier. */
    private fun setEmulatorField(session: TerminalSession, emulator: TerminalEmulator) {
        try {
            val field = TerminalSession::class.java.getDeclaredField("mEmulator")
            field.isAccessible = true
            val modifiersField = Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
            field.set(session, emulator)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject emulator into session", e)
        }
    }

    companion object {
        private const val TAG = "PtyBridge"
    }

    // =================================================================
    //  Reflection helpers
    // =================================================================

    /**
     * Replace [TerminalEmulator.mSession] (private final) with a custom
     * [TerminalOutput] that writes into [pipeOut] instead of the internal
     * PTY file descriptor.
     *
     * @return The original [TerminalOutput] so it can be restored later.
     */
    private fun interceptTerminalOutput(
        emulator: TerminalEmulator,
        pipeOut: PipedOutputStream
    ): TerminalOutput? {
        val original = getEmulatorSessionField(emulator)

        val redirect = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                try {
                    pipeOut.write(data, offset, count)
                } catch (_: IOException) {
                    // pipe closed -- terminal output is discarded
                }
            }

            override fun titleChanged(title: String, cwd: String) {
                original?.titleChanged(title, cwd)
            }

            override fun onCopyTextToClipboard(text: String) {
                original?.onCopyTextToClipboard(text)
            }

            override fun onPasteTextFromClipboard() {
                original?.onPasteTextFromClipboard()
            }

            override fun onBell() {
                original?.onBell()
            }

            override fun onColorsChanged() {
                original?.onColorsChanged()
            }
        }

        setEmulatorSessionField(emulator, redirect)
        return original
    }

    /** Read [TerminalEmulator.mSession] via reflection.  Returns `null` on failure. */
    private fun getEmulatorSessionField(emulator: TerminalEmulator): TerminalOutput? {
        return try {
            val field = TerminalEmulator::class.java.getDeclaredField("mSession")
            field.isAccessible = true
            field.get(emulator) as? TerminalOutput
        } catch (_: Exception) {
            null
        }
    }

    /** Write [TerminalEmulator.mSession], removing the `final` modifier. */
    private fun setEmulatorSessionField(
        emulator: TerminalEmulator,
        value: TerminalOutput
    ) {
        try {
            val field = TerminalEmulator::class.java.getDeclaredField("mSession")
            field.isAccessible = true

            // Strip the final modifier so we can reassign a private-final field.
            val modifiersField: Field = Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())

            field.set(emulator, value)
        } catch (_: Exception) {
            // Reflection unavailable (e.g. mock in test) -- no-op.
        }
    }

    private fun notifyScreenUpdate() {
        SessionManager.notifyTerminalUpdated(terminalSession)
    }
}
