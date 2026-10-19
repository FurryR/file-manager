package io.furryr.file.desktop

import android.content.Context
import io.furryr.file.core.DaemonManager
import io.furryr.file.core.DaemonTarget
import io.furryr.file.core.process.CommandSpec
import io.furryr.file.core.process.PipeProcessChannel
import io.furryr.file.graphics.GraphicsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only the compositor/server process group created for this viewer. */
internal class ManagedDesktop private constructor(
    private val process: PipeProcessChannel,
    val serverName: String,
    private val onLog: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onExit: (String) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    init {
        scope.launch { drain(process.input, true) }
        scope.launch { drain(process.error, false) }
        scope.launch {
            val result = process.waitFor()
            if (!closed.get()) onExit(result.exceptionOrNull()?.message ?: "Desktop process exited (${result.getOrNull()})")
        }
    }
    private fun drain(input: InputStream, control: Boolean) {
        try {
            val bytes = ByteArray(4096)
            var tail = ""
            while (!closed.get()) {
                val count = input.read(bytes)
                if (count < 0) break
                val text = bytes.copyOf(count).toString(Charsets.UTF_8)
                onLog(text)
                if (control) {
                    tail = (tail + text).takeLast(8192)
                    if ("FM-DESKTOP-READY\n" in tail) { tail = ""; onReady() }
                }
            }
        } catch (failure: Exception) { if (!closed.get()) onLog("\n${failure.message}\n") }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // closePipe terminates only this owned process group. Its TERM
            // grace period lets the shell clean up its private runtime folder.
            scope.launch {
                try { process.close() }
                catch (failure: Exception) { android.util.Log.w("Desktop", "Desktop cleanup failed", failure) }
                finally { scope.cancel() }
            }
        }
    }
    companion object {
        suspend fun start(
            context: Context, container: String, command: String, port: Int,
            onLog: (String) -> Unit, onReady: () -> Unit, onExit: (String) -> Unit,
        ): ManagedDesktop = withContext(Dispatchers.IO) {
            require(command.isNotBlank() && '\u0000' !in command) { "A compositor command is required" }
            require(port in 1024..65535) { "Use a desktop port between 1024 and 65535" }
            // Fail before starting processes if the requested port is in use.
            ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
            val token = UUID.randomUUID().toString()
            val name = "FileManager-$token"
            val runtime = "/tmp/file-manager-desktop-$token"
            val script = """
                command -v wayvnc >/dev/null 2>&1 || { printf '%s\n' 'Missing dependency: wayvnc' >&2; exit 127; }
                runtime=${quote(runtime)}
                umask 077
                mkdir -m 700 -- "${'$'}runtime" || exit 1
                compositor=; server=
                cleanup() {
                    trap - 0 HUP INT TERM
                    [ -z "${'$'}server" ] || kill -TERM "${'$'}server" 2>/dev/null
                    [ -z "${'$'}compositor" ] || kill -TERM "${'$'}compositor" 2>/dev/null
                    [ -z "${'$'}server" ] || wait "${'$'}server" 2>/dev/null
                    [ -z "${'$'}compositor" ] || wait "${'$'}compositor" 2>/dev/null
                    rm -rf -- "${'$'}runtime"
                }
                trap cleanup 0
                trap 'exit 0' HUP INT TERM
                export XDG_RUNTIME_DIR="${'$'}runtime" WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1
                export WLR_HEADLESS_OUTPUTS=1 WLR_RENDERER=pixman XDG_SESSION_TYPE=wayland
                unset WAYLAND_DISPLAY DISPLAY
                sh -c ${quote("exec $command")} &
                compositor=${'$'}!
                count=0
                while [ "${'$'}count" -lt 30 ]; do
                    kill -0 "${'$'}compositor" 2>/dev/null || { printf '%s\n' 'Compositor failed; see its output above (labwc or another wlroots compositor is required).' >&2; exit 1; }
                    for candidate in "${'$'}runtime"/wayland-*; do
                        if [ -S "${'$'}candidate" ]; then WAYLAND_DISPLAY=${'$'}{candidate##*/}; export WAYLAND_DISPLAY; break; fi
                    done
                    [ -z "${'$'}{WAYLAND_DISPLAY-}" ] || break
                    count=${'$'}((count + 1)); sleep 1
                done
                [ -n "${'$'}{WAYLAND_DISPLAY-}" ] || { printf '%s\n' 'Compositor did not create a Wayland socket within 30 seconds.' >&2; exit 1; }
                printf '%s\n' 'address=127.0.0.1' 'port=$port' 'enable_auth=false' > "${'$'}runtime/wayvnc.conf"
                wayvnc --config "${'$'}runtime/wayvnc.conf" --name ${quote(name)} --render-cursor --max-fps 30 &
                server=${'$'}!
                printf 'FM-DESKTOP-READY\n'
                while kill -0 "${'$'}compositor" 2>/dev/null && kill -0 "${'$'}server" 2>/dev/null; do sleep 1; done
                printf '%s\n' 'Compositor or wayvnc stopped; see startup output for missing protocols or dependencies.' >&2
                exit 1
            """.trimIndent()
            val process = DaemonManager.execService(DaemonTarget.Named(container)).spawnPipe(CommandSpec(
                command = "/bin/sh", args = listOf("-c", script),
                env = GraphicsManager.containerEnvironment(context, container),
            )).getOrThrow()
            ManagedDesktop(process, name, onLog, onReady, onExit)
        }
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
