package io.furryr.file.provider.remote

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SshHostKeyApprovalRequired(val fingerprint: String, val algorithm: String, val keyBase64: String) :
    IOException("SSH host key must be verified before connecting")
class SshHostKeyChangedException(val fingerprint: String) :
    IOException("SSH host key changed. Connection blocked; verify the server with its administrator. New fingerprint: $fingerprint")

/** Uses SSH exec channels only. No SFTP subsystem, PTY, or shell output evaluation. */
internal class SshExecBackend(private val location: RemoteLocation) : RemoteBackend {
    override fun list(path: String): List<RemoteEntry> {
        val script = "[ -d ${quote(path)} ] || exit 45\n" +
            "find ${quote(path)} -mindepth 1 -maxdepth 1 -exec sh -c ${quote(METADATA)} sh {} +"
        return parse(capture(script))
    }
    override fun stat(path: String): RemoteEntry? = if (path == "/") {
        parse(capture("sh -c ${quote(METADATA)} sh /" )).singleOrNull()
    } else list(parentPath(path)).firstOrNull { it.name == path.substringAfterLast('/') }

    override fun canWrite(path: String): Boolean {
        val result = capture("if [ -d ${quote(path)} ] && [ -w ${quote(path)} ] && [ -x ${quote(path)} ]; then printf y; else printf n; fi")
        return result.contentEquals(byteArrayOf('y'.code.toByte()))
    }
    override fun download(path: String, destination: File) {
        // A misconfigured shell may print a banner even for non-interactive
        // sessions. Verify framing before accepting any bytes as file content.
        val marker = "FMRAW-${UUID.randomUUID()}\n"
        destination.outputStream().use { output ->
            val framed = FramedOutput(marker.toByteArray(Charsets.US_ASCII), output)
            execute("p=${quote(path)}\nn=\$(wc -c < \"\$p\") || exit 72\nprintf '%s%s\\n' ${quote(marker)} \"\$n\"\ncat -- \"\$p\"", output = framed)
            framed.finish()
        }
    }
    override fun upload(path: String, source: File) {
        mutablePath(path)
        val temp = parentPath(path).trimEnd('/') + "/.file-manager-${UUID.randomUUID()}.tmp"
        // Receive into a unique sibling, verify the complete binary stream, and
        // rename only after success. An interrupted upload never truncates dst.
        val script = """
            p=${quote(path)}
            t=${quote(temp)}
            [ ! -L "${'$'}p" ] || { printf '%s\n' 'Refusing to replace a symbolic link' >&2; exit 76; }
            umask 077
            (set -C; : > "${'$'}t") || exit 71
            trap 'rm -f -- "${'$'}t"' 0 HUP INT TERM
            cat > "${'$'}t" || exit 72
            n=${'$'}(wc -c < "${'$'}t") || exit 72
            [ "${'$'}n" -eq ${source.length()} ] || exit 73
            if [ -f "${'$'}p" ]; then
                (${metadataScript(path, temp)}) || exit 74
            fi
            [ ! -d "${'$'}p" ] || exit 45
            mv -f -- "${'$'}t" "${'$'}p" || exit 75
            trap - 0 HUP INT TERM
        """.trimIndent()
        source.inputStream().use { execute(script, input = it) }
    }
    override fun createFile(path: String) {
        mutablePath(path)
        execute("[ ! -e ${quote(path)} ] && [ ! -L ${quote(path)} ] || exit 46\n(set -C; : > ${quote(path)})")
    }
    override fun createDirectory(path: String) { execute("mkdir -- ${quote(mutablePath(path))}") }
    override fun delete(path: String) { execute("rm -rf -- ${quote(mutablePath(path))}") }
    override fun rename(path: String, newName: String) {
        val destination = parentPath(mutablePath(path)).trimEnd('/') + "/" + validName(newName)
        execute("[ ! -e ${quote(destination)} ] && [ ! -L ${quote(destination)} ] || exit 46\nmv -- ${quote(path)} ${quote(destination)}")
    }

    override fun prepareReplacement(original: String, replacement: String) {
        execute(metadataScript(mutablePath(original), mutablePath(replacement)))
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

    private fun capture(script: String): ByteArray {
        val output = BoundedOutput(32 * 1024 * 1024)
        execute(script, output = output)
        if (output.overflowed) throw IOException("SSH directory metadata is too large")
        return output.bytes()
    }
    private fun execute(script: String, input: InputStream? = null, output: OutputStream = Discard) {
        val session = session()
        var channel: ChannelExec? = null
        val stderr = BoundedOutput(16 * 1024)
        val activity = AtomicLong(System.nanoTime())
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setPty(false)
            channel.setCommand("sh -c " + quote(script))
            channel.setInputStream(input?.let { stream -> object : java.io.FilterInputStream(stream) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { activity.set(System.nanoTime()) }
                override fun read(): Int = super.read().also { activity.set(System.nanoTime()) }
            } }, true)
            channel.setOutputStream(object : OutputStream() {
                override fun write(value: Int) { output.write(value); activity.set(System.nanoTime()) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length); activity.set(System.nanoTime()) }
                override fun flush() { output.flush() }
            }, true)
            channel.setErrStream(stderr, true)
            channel.connect(30_000)
            while (!channel.isClosed) {
                interrupted()
                if (System.nanoTime() - activity.get() > 120_000_000_000L) throw IOException("SSH operation timed out")
                Thread.sleep(25)
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
        } finally { channel?.disconnect(); session.disconnect() }
    }
    private fun session(): Session {
        val jsch = JSch()
        val repository = PinnedHostKey(location.hostKey)
        jsch.setHostKeyRepository(repository)
        if (location.privateKey.isNotBlank()) {
            try {
                jsch.addIdentity("remote-${location.id}", location.privateKey.toByteArray(Charsets.UTF_8), null,
                    location.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
            } catch (failure: Exception) { throw IOException("Could not unlock the SSH private key; check its format and passphrase") }
        }
        val session = jsch.getSession(location.username, location.host, location.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (location.privateKey.isNotBlank()) "publickey,password" else "password")
        session.setPassword(location.password)
        session.timeout = 30_000
        session.serverAliveInterval = 15_000
        try { session.connect(30_000); return session }
        catch (failure: Exception) {
            session.disconnect()
            repository.observed?.let { key ->
                val fingerprint = fingerprint(key)
                if (location.hostKey.isEmpty()) throw SshHostKeyApprovalRequired(fingerprint, HostKey(location.host, key).type,
                    Base64.encodeToString(key, Base64.NO_WRAP))
                if (!MessageDigest.isEqual(key, Base64.decode(location.hostKey, Base64.NO_WRAP))) throw SshHostKeyChangedException(fingerprint)
            }
            throw IOException("SSH connection or authentication failed; check the host, port and credentials", failure)
        }
    }
    private class PinnedHostKey(private val saved: String) : HostKeyRepository {
        var observed: ByteArray? = null
        override fun check(host: String?, key: ByteArray): Int {
            observed = key.clone()
            return if (saved.isEmpty()) HostKeyRepository.NOT_INCLUDED else if (
                MessageDigest.isEqual(Base64.decode(saved, Base64.NO_WRAP), key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "file-manager pinned host keys"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
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
    private class FramedOutput(private val marker: ByteArray, private val output: OutputStream) : OutputStream() {
        private var matched = 0
        private val lengthLine = StringBuilder()
        private var remaining: Long? = null
        private var failure: IOException? = null
        @Synchronized override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failure != null) return
            var cursor = offset
            val end = offset + length
            while (matched < marker.size && cursor < end) {
                if (bytes[cursor++] != marker[matched++]) {
                    failure = IOException("Unexpected SSH stdout before file data; remove output from non-interactive shell startup")
                    return
                }
            }
            while (remaining == null && cursor < end) {
                val next = bytes[cursor++].toInt()
                if (next == '\n'.code) {
                    remaining = lengthLine.toString().trim().toLongOrNull()?.takeIf { it >= 0 }
                    if (remaining == null) { failure = IOException("Invalid SSH file length"); return }
                } else if (lengthLine.length < 32 && (next in '0'.code..'9'.code || next == ' '.code || next == '\t'.code)) {
                    lengthLine.append(next.toChar())
                } else { failure = IOException("Invalid SSH file length"); return }
            }
            val count = end - cursor
            if (count.toLong() > (remaining ?: 0)) { failure = IOException("SSH file changed during transfer or stdout contains extra data"); return }
            try { if (count > 0) { output.write(bytes, cursor, count); remaining = remaining!! - count } }
            catch (error: IOException) { failure = error }
        }
        @Synchronized fun finish() {
            failure?.let { throw it }
            if (matched != marker.size || remaining != 0L) throw IOException("Incomplete SSH file response")
            output.flush()
        }
    }
    private object Discard : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }

    private fun parse(bytes: ByteArray): List<RemoteEntry> {
        if (bytes.isEmpty()) return emptyList()
        require(bytes.last() == 0.toByte()) { "Incomplete SSH metadata response" }
        val fields = bytes.toString(Charsets.UTF_8).split('\u0000').dropLast(1)
        require(fields.size % 5 == 0) { "Invalid SSH metadata response; a login script may be writing to stdout" }
        return fields.chunked(5).map { values ->
            require(values[1] in setOf("d", "f") && values[4] in setOf("y", "n")) { "Invalid SSH metadata fields" }
            RemoteEntry(values[0], values[1] == "d", values[2].toLong(), values[3].toLong() * 1000,
                values[4] == "y", if (values[1] == "d") 0x41ED else 0x81A4)
        }
    }
    companion object {
        private fun quote(value: String): String { require('\u0000' !in value); return "'" + value.replace("'", "'\\''") + "'" }
        private fun fingerprint(key: ByteArray) = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING)
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
