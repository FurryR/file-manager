package io.furryr.file.provider

import io.furryr.file.core.*

import io.furryr.file.provider.model.SftpServer
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Vector

/**
 * FileProvider for remote SFTP servers (SSH file transfer protocol).
 *
 * Maps `sftp://<host>/<path>` URIs to remote paths on a registered server,
 * mirroring [FtpProvider]. Connection parameters (port, credentials) are
 * supplied by the app through [SftpServerSource]. `sftp:///` lists all
 * registered servers.
 *
 * Transport is JSch (com.github.mwiede:jsch). A [Session] is cached per host;
 * each operation opens a fresh [ChannelSftp] on that session, so concurrent
 * transfers do not share a single channel's I/O thread.
 */
object SftpProvider : FileProvider {
    override val schemes: Set<String> get() = setOf(ProviderSchemes.SFTP)
    override val label: String get() = "SFTP Location"
    override val writable: Boolean get() = true
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE, ProviderFeature.RENAME,
        ProviderFeature.OPEN, ProviderFeature.STREAM_COPY,
    )

    private const val TIMEOUT_MS = 30_000
    private const val COPY_BUFFER_SIZE = 256 * 1024

    private lateinit var servers: SftpServerSource
    private lateinit var cacheDir: File

    private val jsch = JSch()
    private val connLock = Any()
    private val sessions = mutableMapOf<String, Session>()

    fun init(servers: SftpServerSource, cacheDir: File) {
        this.servers = servers
        this.cacheDir = cacheDir
    }

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        if (path.trim('/').isEmpty()) return DirectoryCapabilities()
        return super<FileProvider>.directoryCapabilities(path)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        synchronized(connLock) {
            sessions.values.forEach { runCatching { it.disconnect() } }
            sessions.clear()
        }
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = io {
        if (path == "/") {
            return@io servers.sftpServers().map { server ->
                FileEntry(
                    name = server.name,
                    path = "sftp://${server.host}/",
                    isDirectory = true,
                    modifiedAt = 0,
                    size = 0,
                    mode = 0x41ED.toInt(), // drwxrwxr-x
                )
            }
        }
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            @Suppress("UNCHECKED_CAST")
            val entries: List<ChannelSftp.LsEntry> =
                (ch.ls(remotePath) as Vector<ChannelSftp.LsEntry>).toList()
            val baseUri = "sftp://${server.host}${if (remotePath == "/") "" else remotePath}"
            entries.filter { it.filename != "." && it.filename != ".." }.map { e ->
                val attrs = e.attrs
                FileEntry(
                    name = e.filename,
                    path = "$baseUri/${e.filename}",
                    isDirectory = attrs.isDir,
                    modifiedAt = attrs.mTime.toLong() * 1000,
                    mode = if (attrs.isDir) 0x41ED.toInt() else 0x81A4.toInt(),
                    size = if (attrs.isDir) 0L else attrs.size,
                )
            }
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = io {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            val attrs = ch.lstat(remotePath)
            // Directories report the volume's free space; a file keeps its size.
            var total = if (attrs.isDir) 0L else attrs.size
            var usable = 0L
            if (attrs.isDir) {
                val vfs = runCatching { ch.statVFS(remotePath) }.getOrNull()
                if (vfs != null) {
                    val unit = vfs.fragmentSize.takeIf { it > 0 } ?: 1L
                    val available = vfs.availForNonRoot.takeIf { it > 0 } ?: vfs.avail
                    total = unit * vfs.blocks
                    usable = unit * available
                }
            }
            ProviderStat(
                exists = true,
                isDirectory = attrs.isDir,
                totalBytes = total,
                usableBytes = usable,
            )
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) ProviderStat(exists = false) else throw e
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = io {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            ch.put(remotePath).close()
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun createDirectory(path: String): Result<Unit> = io {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            ch.mkdir(remotePath)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun delete(path: String): Result<Unit> = io {
        if (path == "/") throw IllegalArgumentException("Cannot delete sftp:/// root")
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            val attrs = ch.lstat(remotePath)
            if (attrs.isDir) ch.rmdir(remotePath) else ch.rm(remotePath)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = io {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            val parent = remotePath.substringBeforeLast('/', "/")
            val to = if (parent.endsWith('/')) "$parent$newName" else "$parent/$newName"
            ch.rename(remotePath, to)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = io {
        val (server, remotePath) = resolveServerPath(path)
        if (mode == OpenMode.READ) {
            val ch = channel(server)
            try {
                val attrs = ch.lstat(remotePath)
                if (attrs.isDir) throw FileNotFoundException("Is a directory: $path")
                SftpReadSource(ch, remotePath, attrs.size)
            } catch (failure: Throwable) {
                runCatching { ch.disconnect() }
                throw failure
            }
        } else {
            val staged = File.createTempFile("sftp-", ".stage", cacheDir)
            if (mode == OpenMode.READ_WRITE) {
                val ch = channel(server)
                try {
                    ch.get(remotePath, FileOutputStream(staged))
                } catch (failure: Throwable) {
                    staged.delete()
                    throw failure
                } finally {
                    runCatching { ch.disconnect() }
                }
            }
            try {
                SeekableCacheFileSource(
                    staged,
                    mode,
                    "sftp://${server.host}$remotePath",
                    commit = { file ->
                        val ch = channel(server)
                        try {
                            ch.put(file.absolutePath, remotePath)
                        } finally {
                            runCatching { ch.disconnect() }
                        }
                    },
                )
            } catch (failure: Throwable) {
                staged.delete()
                throw failure
            }
        }
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> {
        val (srcServer, _) = resolveServerPath(from)
        val (dstServer, _) = resolveServerPath(to)
        if (srcServer.host != dstServer.host) {
            // Different servers cannot share an SFTP transfer; stream the bytes.
            return flow {
                val source = open(from, OpenMode.READ).getOrThrow()
                try {
                    copy(source, to, isCopy).collect { emit(it) }
                } finally {
                    source.close()
                }
            }.flowOn(Dispatchers.IO)
        }
        return flow {
            val (_, srcPath) = resolveServerPath(from)
            val (_, dstPath) = resolveServerPath(to)
            val chIn = channel(srcServer)
            val chOut = channel(dstServer)
            try {
                val total = chIn.lstat(srcPath).size
                val input = chIn.get(srcPath)
                val output = chOut.put(dstPath)
                input.use { ins ->
                    output.use { outs ->
                        var copied = 0L
                        val buf = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                            copied += n
                            emit(CopyProgress(total, copied, from, false, isCopy))
                        }
                        emit(CopyProgress(total, copied, from, true, isCopy))
                    }
                }
            } finally {
                runCatching { chIn.disconnect() }
                runCatching { chOut.disconnect() }
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (server, remotePath) = resolveServerPath(destPath)
        val ch = channel(server)
        try {
            val output = ch.put(remotePath)
            output.use { outs ->
                var offset = 0L
                var copied = 0L
                while (true) {
                    val data = src.readChunk(offset, COPY_BUFFER_SIZE)
                    if (data.isEmpty()) break
                    outs.write(data)
                    offset += data.size
                    copied += data.size
                    emit(CopyProgress(src.size, copied, destPath, false, isCopy))
                }
                emit(CopyProgress(src.size, copied, destPath, true, isCopy))
            }
        } finally {
            runCatching { ch.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    // ── Internal ───────────────────────────────────────────────────

    /** Suspend entry point for the blocking JSch calls below. */
    private suspend fun <T> io(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching(block) }

    private fun resolveServerPath(innerPath: String): Pair<SftpServer, String> {
        if (innerPath == "/" || innerPath.isEmpty()) {
            throw IllegalArgumentException("sftp:/// root has no server")
        }
        val trimmed = innerPath.trimStart('/')
        val slashIdx = trimmed.indexOf('/')
        val host = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
        val remotePath = if (slashIdx >= 0) trimmed.substring(slashIdx) else "/"
        val server = servers.sftpServer(host)
            ?: throw IllegalStateException("SFTP server '$host' not registered")
        return server to remotePath
    }

    /** Open a fresh channel on the (possibly recreated) session for [server]. */
    private fun channel(server: SftpServer): ChannelSftp = synchronized(connLock) {
        var session = sessions[server.host]?.takeIf { it.isConnected }
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val s = session ?: createSession(server).also {
                    sessions[server.host] = it
                }
                val ch = s.openChannel("sftp") as ChannelSftp
                ch.connect()
                return@synchronized ch
            } catch (e: Exception) {
                lastError = e
                sessions.remove(server.host)?.let { runCatching { it.disconnect() } }
                session = null
                if (attempt == 0) continue
            }
        }
        throw lastError ?: IllegalStateException("sftp channel failed")
    }

    private fun createSession(server: SftpServer): Session {
        val s = jsch.getSession(server.username, server.host, server.port)
        s.setPassword(server.password.toByteArray())
        s.setConfig("StrictHostKeyChecking", "no")
        s.setTimeout(TIMEOUT_MS)
        s.setServerAliveInterval(30_000)
        s.connect(TIMEOUT_MS)
        return s
    }
}

/**
 * Random-access [FileSource] over one SFTP channel. JSch starts a download at
 * the requested offset, so a seek only reopens the remote handle; sequential
 * reads keep the current stream.
 */
private class SftpReadSource(
    private val channel: ChannelSftp,
    private val path: String,
    override val size: Long,
) : FileSource() {
    override val readable get() = true
    override val writable get() = false
    override val seekable get() = true
    override val absolutePath: String? = null

    private var stream: InputStream? = null
    private var streamOffset = -1L
    private var closed = false

    @Synchronized
    override fun readAt(offset: Long, len: Int): ByteArray {
        check(!closed) { "SFTP file source is closed" }
        require(offset >= 0 && len >= 0)
        if (len == 0 || offset >= size) {
            if (offset >= size) releaseStream()
            return ByteArray(0)
        }
        val wanted = minOf(len.toLong(), size - offset).toInt()
        val input = ensureStream(offset)
        val buffer = ByteArray(wanted)
        var read = 0
        while (read < wanted) {
            val count = input.read(buffer, read, wanted - read)
            if (count < 0) throw IOException("Incomplete SFTP file response")
            read += count
        }
        streamOffset = offset + read
        return buffer
    }

    private fun ensureStream(offset: Long): InputStream {
        stream?.let { if (streamOffset == offset) return it }
        releaseStream()
        return channel.get(path, null as SftpProgressMonitor?, offset).also {
            stream = it
            streamOffset = offset
        }
    }

    private fun releaseStream() {
        runCatching { stream?.close() }
        stream = null
        streamOffset = -1L
    }

    @Synchronized
    override fun seek(position: Long) {
        check(!closed) { "SFTP file source is closed" }
        require(position >= 0)
        releaseStream()
        streamOffset = position
    }

    override fun writeAt(offset: Long, data: ByteArray) =
        throw UnsupportedOperationException("SFTP file source is read-only")

    override fun truncate(newSize: Long) =
        throw UnsupportedOperationException("SFTP file source is read-only")

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        releaseStream()
        runCatching { channel.disconnect() }
    }
}
