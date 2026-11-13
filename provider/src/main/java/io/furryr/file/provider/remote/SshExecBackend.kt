package io.furryr.file.provider.remote

import com.jcraft.jsch.ChannelExec
import io.furryr.file.core.source.FileSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SshHostKeyApprovalRequired(val fingerprint: String, val algorithm: String, val keyBase64: String) :
    IOException("SSH host key must be verified before connecting")
class SshHostKeyChangedException(val fingerprint: String) :
    IOException("SSH host key changed. Connection blocked; verify the server with its administrator. New fingerprint: $fingerprint")

/** File operations and AI exec use pooled sessions; terminals own theirs. */
internal class SshExecBackend(private val location: RemoteLocation) : RemoteBackend {
    override val streams: Boolean get() = true

    override fun list(path: String): List<RemoteEntry> {
        // The listing and its directory probe share one control-channel command:
        // a folder tap is one round trip, and stat/canWrite/space then read the
        // probe from the cache.
        val bytes = capture(listScript(path))
        return parseListing(path, bytes)
    }
    // One `stat` probe answers existence, write access and volume capacity in a
    // single round trip. Directory browsing asks for the same path through
    // stat/canWrite/space, so caching the probe keeps a folder tap to one exec
    // instead of a full parent listing plus two extra commands.
    override fun stat(path: String): RemoteEntry? = probe(path).entry
    override fun canWrite(path: String): Boolean = probe(path).canWrite
    override fun space(path: String): RemoteSpace? = probe(path).space
    override fun download(path: String, destination: File) {
        openRead(path, stat(path)?.size ?: 0).use { source ->
            destination.outputStream().use { output ->
                var offset = 0L
                while (true) {
                    val bytes = source.readChunk(offset, 256 * 1024)
                    if (bytes.isEmpty()) break
                    output.write(bytes)
                    offset += bytes.size
                }
            }
        }
    }
    /**
     * Live reader straight from `tail` on the server. Nothing is staged
     * locally, so callers see bytes (and progress) while they transfer.
     * Repositioning reopens the pipe at the requested offset.
     */
    override fun openRead(path: String, size: Long): FileSource {
        val marker = "FMRAW-${UUID.randomUUID()}\n"
        // A live read may stall for as long as its consumer does, so it holds a
        // leased session for its whole lifetime; the pool dials another one
        // when every session is busy.
        val lease = SshConnections.open(location)
        return try {
            SshSeekableStream(lease, path, size, marker)
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
    }
    override fun upload(path: String, source: File) {
        mutablePath(path)
        source.inputStream().use { uploadStream(path, it, source.length()) }
    }
    override fun uploadStream(path: String, source: InputStream, length: Long) {
        mutablePath(path)
        // The sender may stall on a slow source or a paused copy, so it must
        // not share a session with directory browsing.
        SshConnections.open(location).use { lease ->
            execute(uploadScript(path, length), input = source, connection = lease.connection)
        }
        invalidate(location.id)
    }

    private fun uploadScript(path: String, length: Long): String {
        val temp = parentPath(path).trimEnd('/') + "/.file-manager-${UUID.randomUUID()}.tmp"
        // Receive into a unique sibling, verify the complete binary stream, and
        // rename only after success. An interrupted upload never truncates dst.
        return """
            p=${quote(path)}
            t=${quote(temp)}
            [ ! -L "${'$'}p" ] || { printf '%s\n' 'Refusing to replace a symbolic link' >&2; exit 76; }
            umask 077
            (set -C; : > "${'$'}t") || exit 71
            trap 'rm -f -- "${'$'}t"' 0 HUP INT TERM
            cat > "${'$'}t" || exit 72
            n=${'$'}(wc -c < "${'$'}t") || exit 72
            [ "${'$'}n" -eq $length ] || exit 73
            if [ -f "${'$'}p" ]; then
                (${metadataScript(path, temp)}) || exit 74
            fi
            [ ! -d "${'$'}p" ] || exit 45
            mv -f -- "${'$'}t" "${'$'}p" || exit 75
            trap - 0 HUP INT TERM
        """.trimIndent()
    }
    override fun createFile(path: String) {
        mutablePath(path)
        control("[ ! -e ${quote(path)} ] && [ ! -L ${quote(path)} ] || exit 46\n(set -C; : > ${quote(path)})")
        invalidate(location.id)
    }
    override fun createDirectory(path: String) {
        control("mkdir -- ${quote(mutablePath(path))}")
        invalidate(location.id)
    }
    override fun delete(path: String) {
        control("rm -rf -- ${quote(mutablePath(path))}")
        invalidate(location.id)
    }
    override fun rename(path: String, newName: String) {
        val destination = parentPath(mutablePath(path)).trimEnd('/') + "/" + validName(newName)
        control("[ ! -e ${quote(destination)} ] && [ ! -L ${quote(destination)} ] || exit 46\nmv -- ${quote(path)} ${quote(destination)}")
        invalidate(location.id)
    }

    override fun prepareReplacement(original: String, replacement: String) {
        control(metadataScript(mutablePath(original), mutablePath(replacement)))
        invalidate(location.id)
    }

    private fun metadataScript(original: String, replacement: String): String {
        val acl = parentPath(replacement).trimEnd('/') + "/.file-manager-acl-${UUID.randomUUID()}.tmp"
        return """
            p=${quote(original)}
            t=${quote(replacement)}
            [ ! -L "${'$'}p" ] && [ ! -L "${'$'}t" ] && [ -f "${'$'}p" ] && [ -f "${'$'}t" ] || { printf '%s\n' 'Replacement requires regular files, not symbolic links' >&2; exit 76; }
            values=${'$'}(stat -c '%a %u %g' -- "${'$'}p" 2>/dev/null) || values=${'$'}(stat -f '%Lp %u %g' "${'$'}p") || exit 74
            set -- ${'$'}values
            [ "${'$'}#" -eq 3 ] || exit 74
            mode=${'$'}1; owner=${'$'}2; group=${'$'}3
            case "${'$'}mode" in ''|*[!0-7]*) exit 74;; esac
            case "${'$'}owner:${'$'}group" in *[!0-9:]*) exit 74;; esac
            current=${'$'}(stat -c '%u %g' -- "${'$'}t" 2>/dev/null) || current=${'$'}(stat -f '%u %g' "${'$'}t") || exit 74
            set -- ${'$'}current
            [ "${'$'}#" -eq 2 ] || exit 74
            if [ "${'$'}owner" != "${'$'}1" ] || [ "${'$'}group" != "${'$'}2" ]; then chown "${'$'}owner:${'$'}group" "${'$'}t" || exit 74; fi
            chmod "${'$'}mode" "${'$'}t" || exit 74
            if cp --help 2>/dev/null | grep -q -- '--attributes-only'; then
                # GNU cp preserves ACLs with mode and xattrs where supported;
                # attributes-only never replaces the staged file contents.
                cp --attributes-only --preserve=mode,xattr -- "${'$'}p" "${'$'}t" || exit 74
            elif command -v getfacl >/dev/null 2>&1 && command -v setfacl >/dev/null 2>&1; then
                acl=${quote(acl)}
                (umask 077; set -C; : > "${'$'}acl") || exit 74
                trap 'rm -f -- "${'$'}acl"' 0 HUP INT TERM
                getfacl -cp -- "${'$'}p" > "${'$'}acl" && setfacl --set-file="${'$'}acl" -- "${'$'}t" || exit 74
            fi
        """.trimIndent()
    }

    // Short metadata and mutation commands share the location's control shell;
    // only streaming transfers pay for their own channel.
    private fun capture(script: String): ByteArray = SshControls.get(location).run(script)

    private fun control(script: String) {
        SshControls.get(location).run(script)
    }

    private fun listScript(path: String): String = """
        p=${quote(path)}
        [ -d "${'$'}p" ] || exit 45
        find "${'$'}p" -mindepth 1 -maxdepth 1 -exec sh -c ${quote(METADATA)} sh {} +
        w=n
        if [ -w "${'$'}p" ] && [ -x "${'$'}p" ]; then w=y; fi
        d=${'$'}(LC_ALL=C df -Pk "${'$'}p" 2>/dev/null | tail -n 1)
        set -- ${'$'}d
        printf 'P/\000d\000%s\000%s\000%s\000' "${'$'}{2:-0}" "${'$'}{4:-0}" "${'$'}w"
    """.trimIndent()

    private fun probe(path: String): Probe {
        val key = location.id + '\u0000' + path
        cachedProbe(key)?.let { return it }
        val fields = capture(probeScript(path)).toString(Charsets.UTF_8).split('\u0000').dropLast(1)
        val result = parseProbe(path, fields)
        storeProbe(key, result)
        return result
    }

    private fun probeScript(path: String): String = """
        p=${quote(path)}
        v=${'$'}(stat -c '%s %Y' -- "${'$'}p" 2>/dev/null) || v=${'$'}(stat -f '%z %m' "${'$'}p" 2>/dev/null) || { printf 'M\000'; exit 0; }
        set -- ${'$'}v
        [ "${'$'}#" -eq 2 ] || { printf 'M\000'; exit 0; }
        k=f
        if [ -d "${'$'}p" ] && [ ! -L "${'$'}p" ]; then k=d; fi
        l=n
        if [ -L "${'$'}p" ]; then l=y; fi
        w=n
        if [ "${'$'}k" = d ] && [ -w "${'$'}p" ] && [ -x "${'$'}p" ]; then w=y; fi
        printf 'E\000%s\000%s\000%s\000%s\000' "${'$'}k" "${'$'}1" "${'$'}2" "${'$'}l"
        printf 'W\000%s\000' "${'$'}w"
        if [ "${'$'}k" = d ]; then
            d=${'$'}(LC_ALL=C df -Pk "${'$'}p" 2>/dev/null | tail -n 1)
            set -- ${'$'}d
            if [ "${'$'}#" -ge 4 ]; then printf 'S\000%s\000%s\000' "${'$'}2" "${'$'}4"; fi
        fi
    """.trimIndent()
    private fun execute(
        script: String,
        input: InputStream? = null,
        output: OutputStream = Discard,
        connection: SshConnection? = null,
    ) {
        if (connection != null) {
            run(script, input, output, connection)
            return
        }
        SshConnections.open(location).use { lease -> run(script, input, output, lease.connection) }
    }

    private fun run(script: String, input: InputStream?, output: OutputStream, connection: SshConnection) {
        val stderr = BoundedOutput(16 * 1024)
        val activity = AtomicLong(System.nanoTime())
        val channel = connection.open("exec") { exec: ChannelExec ->
            exec.setPty(false)
            exec.setCommand("sh -c " + quote(script))
            exec.setInputStream(input?.let { stream -> object : java.io.FilterInputStream(stream) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { activity.set(System.nanoTime()) }
                override fun read(): Int = super.read().also { activity.set(System.nanoTime()) }
            } }, true)
            exec.setOutputStream(object : OutputStream() {
                override fun write(value: Int) { output.write(value); activity.set(System.nanoTime()) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length); activity.set(System.nanoTime()) }
                override fun flush() { output.flush() }
            }, true)
            exec.setErrStream(stderr, true)
            exec.connect(30_000)
        }
        try {
            while (!channel.isClosed) {
                interrupted()
                if (System.nanoTime() - activity.get() > 120_000_000_000L) throw IOException("SSH operation timed out")
                Thread.sleep(5)
            }
            val exit = channel.exitStatus
            if (exit != 0) {
                val details = stderr.bytes().toString(Charsets.UTF_8).filter { !it.isISOControl() || it == '\n' }.take(1024)
                var safe = details
                listOf(location.password, location.passphrase, location.privateKey).filter { it.isNotEmpty() }
                    .forEach { safe = safe.replace(it, "[redacted]") }
                throw IOException("SSH command failed ($exit)${if (safe.isBlank()) "" else ": $safe"}")
            }
            output.flush()
        } finally { channel.disconnect() }
    }
    private class BoundedOutput(private val maximum: Int) : OutputStream() {
        private val output = ByteArrayOutputStream()
        @Volatile var overflowed = false; private set
        @Synchronized override fun write(value: Int) { if (output.size() < maximum) output.write(value) else overflowed = true }
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val keep = minOf(length, maximum - output.size())
            output.write(bytes, offset, keep)
            if (keep < length) overflowed = true
        }
        @Synchronized fun bytes(): ByteArray = output.toByteArray()
    }
    /**
     * Random-access [FileSource] over a live `tail -c +N` exec pipe. Sequential
     * reads stay on the current pipe; any other offset reopens it. The header
     * carries the server-reported length, so a file that changed after `stat`
     * is rejected unless the whole declared byte count arrived.
     */
    private class SshSeekableStream(
        private val lease: SshLease,
        private val path: String,
        override val size: Long,
        private val markerText: String,
    ) : FileSource() {
        override val readable: Boolean get() = true
        override val writable: Boolean get() = false
        override val seekable: Boolean get() = true
        override val absolutePath: String? = null

        private val marker = markerText.toByteArray(Charsets.US_ASCII)
        private var channel: ChannelExec? = null
        private var stdout: InputStream? = null
        private var position = 0L
        private var headerRead = false
        private var finished = false
        @Volatile private var closed = false

        init {
            openAt(0)
        }

        @Synchronized
        override fun readAt(offset: Long, len: Int): ByteArray {
            check(!closed) { "SSH file stream is closed" }
            require(offset >= 0 && len >= 0)
            if (len == 0 || offset >= size) return ByteArray(0)
            if (offset != position || stdout == null) openAt(offset)
            val input = stdout ?: throw IOException("SSH download stream is unavailable")
            if (!headerRead) readHeader(input)
            if (position >= size) {
                finish()
                return ByteArray(0)
            }
            val wanted = minOf(len.toLong(), size - position).toInt()
            val buffer = ByteArray(wanted)
            var read = 0
            while (read < wanted) {
                interrupted()
                val count = input.read(buffer, read, wanted - read)
                if (count < 0) throw IOException("Incomplete SSH file response")
                read += count
            }
            position += read
            if (position >= size) finish()
            return buffer
        }

        private fun openAt(offset: Long) {
            releaseChannel()
            headerRead = false
            finished = false
            position = offset
            var input: InputStream? = null
            val exec = try {
                lease.connection.open("exec") { exec: ChannelExec ->
                    exec.setPty(false)
                    exec.setCommand("sh -c " + quote(
                        "p=${quote(path)}\nn=\$(wc -c < \"\$p\") || exit 72\n" +
                            "printf '%s%s\\n' ${quote(markerText)} \"\$n\"\n" +
                            "tail -c +${offset + 1} \"\$p\"",
                    ))
                    exec.setErrStream(BoundedOutput(16 * 1024), true)
                    // JSch requires the pipe to exist before connect().
                    input = exec.inputStream
                    exec.connect(30_000)
                }
            } catch (failure: Throwable) {
                runCatching { input?.close() }
                throw failure
            }
            channel = exec
            stdout = input ?: run {
                runCatching { exec.disconnect() }
                throw IOException("SSH download stream is unavailable")
            }
        }

        private fun releaseChannel() {
            runCatching { stdout?.close() }
            runCatching { channel?.disconnect() }
            stdout = null
            channel = null
        }

        private fun readHeader(input: InputStream) {
            headerRead = true
            val head = ByteArray(marker.size)
            var read = 0
            while (read < head.size) {
                val count = input.read(head, read, head.size - read)
                if (count < 0) throw IOException("Incomplete SSH file response")
                read += count
            }
            if (!head.contentEquals(marker)) {
                throw IOException("Unexpected SSH stdout before file data; remove output from non-interactive shell startup")
            }
            val line = StringBuilder()
            while (true) {
                val next = input.read()
                if (next < 0) throw IOException("Incomplete SSH file response")
                if (next == '\n'.code) break
                if (next !in '0'.code..'9'.code || line.length >= 32) throw IOException("Invalid SSH file length")
                line.append(next.toChar())
            }
            val declared = line.toString().toLongOrNull() ?: throw IOException("Invalid SSH file length")
            if (declared != size) throw IOException("SSH file changed during transfer")
        }

        private fun finish() {
            if (finished) return
            finished = true
            val exec = channel ?: return
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (true) {
                    if ((stdout?.available() ?: 0) > 0) throw IOException("SSH file changed during transfer")
                    if (exec.isClosed) break
                    interrupted()
                    if (System.nanoTime() > deadline) break
                    Thread.sleep(5)
                }
                if (exec.exitStatus != 0) throw IOException("SSH file transfer failed (${exec.exitStatus})")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Operation cancelled", interrupted)
            }
        }

        override fun writeAt(offset: Long, data: ByteArray) =
            throw UnsupportedOperationException("SSH file stream is read-only")
        @Synchronized
        override fun seek(position: Long) {
            check(!closed) { "SSH file stream is closed" }
            require(position >= 0)
            if (position == this.position && stdout != null) return
            if (position >= size) {
                releaseChannel()
                this.position = position
                headerRead = false
                finished = true
                return
            }
            openAt(position)
        }
        override fun truncate(newSize: Long) =
            throw UnsupportedOperationException("SSH file stream is read-only")

        override fun close() {
            if (closed) return
            closed = true
            releaseChannel()
            lease.close()
        }
    }
    private object Discard : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }

    private fun parseListing(path: String, bytes: ByteArray): List<RemoteEntry> {
        if (bytes.isEmpty()) return emptyList()
        require(bytes.last() == 0.toByte()) { "Incomplete SSH metadata response" }
        val fields = bytes.toString(Charsets.UTF_8).split('\u0000').dropLast(1)
        require(fields.size % 5 == 0) { "Invalid SSH metadata response; a login script may be writing to stdout" }
        val entries = ArrayList<RemoteEntry>(fields.size / 5)
        var dirProbe: Probe? = null
        fields.chunked(5).forEach { values ->
            if (values[0] == "P/") {
                // Trailer record for the listed directory itself: its write
                // access and volume capacity were already gathered for free.
                val blocks = values[2].toLongOrNull() ?: 0
                val available = values[3].toLongOrNull() ?: 0
                dirProbe = Probe(
                    entry = RemoteEntry(
                        name = path.substringAfterLast('/').ifEmpty { "/" },
                        isDirectory = true,
                        mode = 0x41ED,
                    ),
                    canWrite = values[4] == "y",
                    space = if (blocks > 0) RemoteSpace(blocks * 1024, available * 1024) else null,
                    at = System.currentTimeMillis(),
                )
                return@forEach
            }
            require(values[1] in setOf("d", "f") && values[4] in setOf("y", "n")) { "Invalid SSH metadata fields" }
            entries += RemoteEntry(values[0], values[1] == "d", values[2].toLong(), values[3].toLong() * 1000,
                values[4] == "y", if (values[1] == "d") 0x41ED else 0x81A4)
        }
        dirProbe?.let { storeProbe(location.id + '\u0000' + path, it) }
        return entries
    }
    companion object {
        private const val PROBE_TTL_MS = 3_000L
        private val probes = HashMap<String, Probe>()

        private data class Probe(
            val entry: RemoteEntry?,
            val canWrite: Boolean,
            val space: RemoteSpace?,
            val at: Long,
        )

        private fun cachedProbe(key: String): Probe? = synchronized(probes) {
            val hit = probes[key] ?: return@synchronized null
            if (System.currentTimeMillis() - hit.at > PROBE_TTL_MS) {
                probes.remove(key)
                null
            } else hit
        }

        private fun storeProbe(key: String, probe: Probe) {
            synchronized(probes) {
                if (probes.size >= 128) probes.clear()
                probes[key] = probe
            }
        }

        private fun invalidate(id: String) {
            synchronized(probes) { probes.keys.removeAll { it.startsWith("$id\u0000") } }
        }

        private fun parseProbe(path: String, fields: List<String>): Probe {
            val now = System.currentTimeMillis()
            if (fields.size == 1 && fields[0] == "M") return Probe(null, false, null, now)
            require(fields.size >= 7 && fields[0] == "E" && fields[5] == "W") {
                "Invalid SSH metadata response; a login script may be writing to stdout"
            }
            val isDirectory = fields[1] == "d"
            val size = fields[2].toLongOrNull()
            val modified = fields[3].toLongOrNull()
            require(size != null && modified != null) {
                "Invalid SSH metadata response; a login script may be writing to stdout"
            }
            val entry = RemoteEntry(
                name = path.substringAfterLast('/').ifEmpty { "/" },
                isDirectory = isDirectory,
                size = size,
                modifiedAt = modified * 1000,
                isSymlink = fields[4] == "y",
                mode = if (isDirectory) 0x41ED else 0x81A4,
            )
            val space = if (fields.size >= 10 && fields[7] == "S") {
                RemoteSpace(
                    totalBytes = fields[8].toLong() * 1024,
                    usableBytes = fields[9].toLong() * 1024,
                )
            } else null
            return Probe(entry, fields[6] == "y", space, now)
        }

        private fun quote(value: String): String { require('\u0000' !in value); return "'" + value.replace("'", "'\\''") + "'" }
        private val METADATA = """
            for p do
                v=${'$'}(stat -c '%s %Y' -- "${'$'}p" 2>/dev/null) || v=${'$'}(stat -f '%z %m' "${'$'}p") || exit 70
                set -- ${'$'}v
                [ "${'$'}#" -eq 2 ] || exit 70
                k=f; if [ -d "${'$'}p" ] && [ ! -L "${'$'}p" ]; then k=d; fi
                l=n; [ ! -L "${'$'}p" ] || l=y
                n=${'$'}{p##*/}; [ -n "${'$'}n" ] || n=/
                printf '%s\000%s\000%s\000%s\000%s\000' "${'$'}n" "${'$'}k" "${'$'}1" "${'$'}2" "${'$'}l"
            done
        """.trimIndent()
    }
}
