package io.furryr.file.util

import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.furryr.file.container.ContainerDaemonLauncher
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.hub.Hub
import io.furryr.file.daemon.process.CommandSpec
import io.furryr.file.daemon.process.ProcessChannel
import io.furryr.file.provider.remote.SshShell
import io.furryr.file.provider.remote.SshTerminals
import io.furryr.file.pulse.PulseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Daemon-backed replacement for Termux's native process helpers.
 *
 * `com.termux.terminal.JNI` is rewritten (see `app/build.gradle.kts`) to call
 * these methods, so a [com.termux.terminal.TerminalSession] owns the PTY fd
 * returned here and reads/writes it directly.
 *
 * A `cmd` of the form `container://<name>` spawns the shell on that container's
 * dedicated daemon instead of the app daemon, so the terminal *is* the
 * container's shell (no bridging). An `ssh://<id>` command instead bridges the
 * terminal fd to an SSH shell channel on the reused remote connection.
 */
object ProcessHelper {
    private const val TAG = "ProcessHelper"
    private const val CONTAINER_PREFIX = "container://"
    private const val SSH_PREFIX = "ssh://"

    private val fdToChannel = HashMap<Int, ProcessChannel>()
    private val pidToChannel = HashMap<Int, ProcessChannel>()
    private val fdToSsh = HashMap<Int, SshTerminalHandle>()
    private val pidToSsh = HashMap<Int, SshTerminalHandle>()
    private val terminalToSsh = HashMap<String, SshTerminalHandle>()
    private val preparedSsh = HashMap<String, SshShell>()
    private val sshPid = AtomicInteger(1_000_000_000)
    private val mapLock = Any()

    @JvmStatic
    fun createSubprocess(
        cmd: String, cwd: String?, args: Array<String>?, envVars: Array<String>?,
        processId: IntArray, rows: Int, columns: Int
    ): Int = onIoThread {
        val env = envVars?.associate {
            val eq = it.indexOf('=')
            if (eq >= 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
        } ?: emptyMap()

        if (cmd.startsWith(SSH_PREFIX)) {
            return@onIoThread createSshSubprocess(
                cmd.removePrefix(SSH_PREFIX), env, rows, columns, processId,
            )
        }

        val containerName = cmd.removePrefix(CONTAINER_PREFIX)
            .takeIf { cmd.startsWith(CONTAINER_PREFIX) && it.isNotBlank() }
        val target = if (containerName != null) {
            DaemonTarget.Named(containerName)
        } else {
            DaemonTarget.App
        }
        val hubEnv = Hub.environment(target)

        // Initialize a clean target environment before an interactive container login.
        // Command execution through the hub bypasses login and its MOTD output.
        val spec = CommandSpec(
            command = if (containerName != null) "" else cmd,
            args = if (containerName != null) {
                listOf("-c", "exec /bin/login -p -f root")
            } else (args?.toList() ?: emptyList()),
            env = if (containerName != null) {
                env.filterKeys { it == "TERM" || it == "HUB_TERMINAL" || it == "CONTAINER_START_PATH" } +
                    hubEnv + PulseManager.environment() + buildMap {
                    put("PATH", ContainerDaemonLauncher.DEFAULT_PATH)
                    Hub.binDir?.let { put("HUB_PATH", it.absolutePath) }
                }
            } else env + hubEnv,
            cwd = if (containerName != null) "~" else cwd,
            user = if (containerName != null) "root" else null,
            rows = rows,
            cols = columns,
        )
        val channel = DaemonManager.execService(target).spawn(spec).getOrThrow()
        val fd = channel.fileDescriptor
            ?: throw IllegalStateException("daemon returned no PTY fd")

        val rawFd = getRawFd(fd)
        processId[0] = channel.pid

        synchronized(mapLock) {
            fdToChannel[rawFd] = channel
            pidToChannel[channel.pid] = channel
        }

        Log.d(TAG, "createSubprocess: fd=$rawFd pid=${channel.pid} container=$containerName")
        rawFd
    }

    @JvmStatic
    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int) {
        val channel: ProcessChannel?
        val ssh: SshTerminalHandle?
        synchronized(mapLock) {
            channel = fdToChannel[fd]
            ssh = fdToSsh[fd]
        }
        if (ssh != null) {
            ssh.shell.resize(rows, cols)
            return
        }
        channel ?: return
        onIoThread { channel.resize(rows, cols) }
            .onFailure { Log.w(TAG, "setPtyWindowSize: fd=$fd is no longer available", it) }
    }

    @JvmStatic
    fun waitFor(processId: Int): Int {
        val ssh = synchronized(mapLock) { pidToSsh[processId] }
        if (ssh != null) return ssh.await()
        val channel: ProcessChannel?
        synchronized(mapLock) { channel = pidToChannel[processId] }
        channel ?: return -1
        // Termux calls this on its own thread without an exception handler.
        // A daemon reset/disconnect must finish the session, not crash the app.
        val exitCode = onIoThread { channel.waitFor() }.getOrElse {
            Log.w(TAG, "waitFor: pid=$processId lost its daemon", it)
            -1
        }
        Log.d(TAG, "waitFor: pid=$processId exitCode=$exitCode")
        return exitCode
    }

    @JvmStatic
    fun close(fileDescriptor: Int) {
        val ssh: SshTerminalHandle?
        synchronized(mapLock) {
            ssh = fdToSsh.remove(fileDescriptor)
            if (ssh != null) {
                pidToSsh.remove(ssh.pid)
                terminalToSsh.remove(ssh.terminalId, ssh)
            }
        }
        if (ssh != null) {
            ssh.close()
            closeRawFd(fileDescriptor)
            return
        }
        val channel: ProcessChannel?
        synchronized(mapLock) {
            channel = fdToChannel.remove(fileDescriptor)
            if (channel != null) pidToChannel.remove(channel.pid)
        }
        if (channel != null) {
            runCatching { channel.close() }
                .onFailure { Log.w(TAG, "close: fd=$fileDescriptor failed", it) }
        } else {
            closeRawFd(fileDescriptor)
        }
    }

    /** Close the SSH shell bridged to [terminalId] without signalling a fake pid. */
    fun closeSshTerminal(terminalId: String) {
        synchronized(mapLock) { terminalToSsh[terminalId] }?.close()
        synchronized(mapLock) { preparedSsh.remove(terminalId) }?.close()
    }

    /** Register a shell opened before Termux attaches, so its errors stay in createSession. */
    fun prepareSshTerminal(terminalId: String, shell: SshShell) {
        synchronized(mapLock) { preparedSsh.put(terminalId, shell) }?.close()
    }

    fun discardSshTerminal(terminalId: String) {
        synchronized(mapLock) { preparedSsh.remove(terminalId) }?.close()
    }

    private fun createSshSubprocess(
        locationId: String,
        env: Map<String, String>,
        rows: Int,
        cols: Int,
        processId: IntArray,
    ): Int {
        val terminalId = env["SSH_TERMINAL_ID"]
            ?: throw IllegalStateException("SSH terminal has no session id")
        val prepared = synchronized(mapLock) { preparedSsh.remove(terminalId) }
        val shell = prepared ?: SshTerminals.open(
            SshTerminals.find(locationId),
            env["SSH_START_PATH"]?.takeIf { it.startsWith('/') },
            rows,
            cols,
        )
        shell.resize(rows, cols)
        val pair = arrayOf(FileDescriptor(), FileDescriptor())
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, pair[0], pair[1])
        } catch (failure: Exception) {
            shell.close()
            throw failure
        }
        val rawFd = getRawFd(pair[0])
        val pid = sshPid.incrementAndGet()
        val handle = SshTerminalHandle(
            terminalId = terminalId,
            pid = pid,
            shell = shell,
            socketInput = FileInputStream(pair[1]),
            socketOutput = FileOutputStream(pair[1]),
        )
        synchronized(mapLock) {
            fdToSsh[rawFd] = handle
            pidToSsh[pid] = handle
            terminalToSsh[terminalId] = handle
        }
        processId[0] = pid
        handle.start()
        Log.d(TAG, "createSshSubprocess: fd=$rawFd pid=$pid")
        return rawFd
    }

    /**
     * Terminal fd <-> SSH shell bridge. The terminal owns one socketpair end;
     * the relay owns the other and copies bytes in both directions.
     */
    private class SshTerminalHandle(
        val terminalId: String,
        val pid: Int,
        val shell: SshShell,
        private val socketInput: InputStream,
        private val socketOutput: OutputStream,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        fun start() {
            Thread({
                copy(socketInput, shell.output)
            }, "SshTerminalInput").apply { isDaemon = true }.start()
            Thread({
                copy(shell.input, socketOutput)
            }, "SshTerminalOutput").apply { isDaemon = true }.start()
        }

        fun await(): Int {
            try {
                done.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            Log.d(TAG, "SSH terminal exited: pid=$pid status=${shell.exitStatus}")
            return shell.exitStatus
        }

        private fun copy(from: InputStream, to: OutputStream) {
            try {
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    to.write(buffer, 0, count)
                    to.flush()
                }
            } catch (failure: Exception) {
                Log.d(TAG, "SSH terminal relay stopped: pid=$pid", failure)
            } finally {
                close()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socketInput.close() }
            runCatching { socketOutput.close() }
            runCatching { shell.close() }
            done.countDown()
        }
    }

    /**
     * The termux JNI contract calls into these methods synchronously, and the
     * terminal drives some of them straight from the main thread. The daemon
     * talks over a (local) UNIX socket, which StrictMode's `BlockGuardOs`
     * rejects with `NetworkOnMainThreadException` when the syscall runs on the
     * main thread — so hop onto an IO thread and block until it finishes.
     */
    private fun <T> onIoThread(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun getRawFd(fd: FileDescriptor): Int {
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        return field.getInt(fd)
    }

    private fun closeRawFd(rawFd: Int) {
        try {
            val fdObj = FileDescriptor()
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fdObj, rawFd)
            Os.close(fdObj)
        } catch (e: Exception) {
            Log.w(TAG, "closeRawFd($rawFd) failed", e)
        }
    }
}
