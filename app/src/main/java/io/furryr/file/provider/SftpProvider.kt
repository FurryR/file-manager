package io.furryr.file.provider

import io.furryr.file.model.SftpServer
import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.StreamFileSource
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.FilterInputStream
import java.io.FileNotFoundException
import java.util.Vector

/**
 * FileProvider for remote SFTP servers (SSH file transfer protocol).
 *
 * Maps `sftp://<host>/<path>` URIs to remote paths on a registered server,
 * mirroring [FtpProvider]. Connection parameters (port, credentials) are
 * stored in [SftpDatabase] keyed by host. `sftp:///` lists all registered
 * servers.
 *
 * Transport is JSch (com.github.mwiede:jsch). A [Session] is cached per host;
 * each operation opens a fresh [ChannelSftp] on that session, so concurrent
 * transfers do not share a single channel's I/O thread.
 */
object SftpProvider : FileProvider {
    override val scheme: String get() = "sftp"
    override val authority: String? get() = null
    override val label: String get() = "SFTP Location"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE, ProviderFeature.RENAME,
        ProviderFeature.OPEN, ProviderFeature.STREAM_COPY,
    )

    private const val TIMEOUT_MS = 30_000
    private const val COPY_BUFFER_SIZE = 256 * 1024

    private lateinit var db: SftpDatabase
    private lateinit var appContext: Context

    private val jsch = JSch()
    private val connLock = Any()
    private val sessions = mutableMapOf<String, Session>()

    fun init(context: Context) {
        appContext = context.applicationContext
        db = SftpDatabase(appContext)
    }

    fun getDatabase(): SftpDatabase = db

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        if (path.trim('/').isEmpty()) return DirectoryCapabilities()
        return super<FileProvider>.directoryCapabilities(path)
    }

    override suspend fun disconnect() {
        synchronized(connLock) {
            sessions.values.forEach { runCatching { it.disconnect() } }
            sessions.clear()
        }
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        if (path == "/") {
            return@runCatching db.getAll().map { server ->
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

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            val attrs = ch.lstat(remotePath)
            ProviderStat(
                exists = true,
                isDirectory = attrs.isDir,
                totalBytes = if (attrs.isDir) 0L else attrs.size,
            )
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) ProviderStat(exists = false) else throw e
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            ch.put(remotePath).close()
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        try {
            ch.mkdir(remotePath)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
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

    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
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

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        if (mode != OpenMode.READ) throw UnsupportedOperationException("SftpProvider supports READ only")
        val (server, remotePath) = resolveServerPath(path)
        val ch = channel(server)
        val attrs = ch.lstat(remotePath)
        if (attrs.isDir) throw FileNotFoundException("Is a directory: $path")
        val raw = ch.get(remotePath)
        val wrapped = object : FilterInputStream(raw) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { ch.disconnect() }
                }
            }
        }
        StreamFileSource(inputStream = wrapped, size = attrs.size, absolutePath = null)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (srcServer, srcPath) = resolveServerPath(from)
        val (dstServer, dstPath) = resolveServerPath(to)
        require(srcServer.host == dstServer.host) { "copy endpoints must use the same server" }
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

    private fun resolveServerPath(innerPath: String): Pair<SftpServer, String> {
        if (innerPath == "/" || innerPath.isEmpty()) {
            throw IllegalArgumentException("sftp:/// root has no server")
        }
        val trimmed = innerPath.trimStart('/')
        val slashIdx = trimmed.indexOf('/')
        val host = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
        val remotePath = if (slashIdx >= 0) trimmed.substring(slashIdx) else "/"
        val server = db.findByHost(host)
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
        s.setPassword(server.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setTimeout(TIMEOUT_MS)
        s.setServerAliveInterval(30_000)
        s.connect(TIMEOUT_MS)
        return s
    }
}
