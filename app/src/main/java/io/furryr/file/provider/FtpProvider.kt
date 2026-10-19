package io.furryr.file.provider

import io.furryr.file.model.FtpServer
import android.content.Context
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
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream

/**
 * FileProvider for remote FTP servers.
 *
 * Maps `ftp://<host>/<path>` URIs to remote paths on a registered server.
 * The host segment is used as the routing key — the server connection
 * (port, credentials) is stored in [FtpDatabase] keyed by host.
 * `ftp:///` lists all registered servers.
 *
 * ## Usage
 * ```kotlin
 * val db = FtpProvider.getDatabase()
 * db.insert(host = "192.168.1.1", name = "NAS", port = 21,
 *           username = "user", password = "pass")
 * // Then access files via: ftp://192.168.1.1/srv/ftp/file.txt
 * ```
 */
object FtpProvider : FileProvider {
    override val scheme: String get() = "ftp"
    override val authority: String? get() = null
    override val label: String get() = "FTP Location"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE, ProviderFeature.RENAME,
        ProviderFeature.OPEN, ProviderFeature.STREAM_COPY,
    )

    private const val TIMEOUT_MS = 30_000
    private const val COPY_BUFFER_SIZE = 256 * 1024

    private lateinit var db: FtpDatabase
    private lateinit var appContext: Context

    private val connLock = Any()
    private val clients = mutableMapOf<String, FTPClient>()

    fun init(context: Context) {
        appContext = context.applicationContext
        db = FtpDatabase(appContext)
    }

    fun getDatabase(): FtpDatabase = db

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        if (path.trim('/').isEmpty()) return DirectoryCapabilities()
        return super<FileProvider>.directoryCapabilities(path)
    }

    override suspend fun disconnect() {
        synchronized(connLock) {
            clients.values.forEach { runCatching { it.disconnect() } }
            clients.clear()
        }
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        if (path == "/") {
            return@runCatching db.getAll().map { server ->
                FileEntry(
                    name = server.name,
                    path = "ftp://${server.host}/",
                    isDirectory = true,
                    modifiedAt = 0,
                    size = 0,
                    mode = 0x41ED.toInt(), // drwxrwxr-x
                )
            }
        }
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        val files = ftp.listFiles(remotePath) ?: emptyArray()
        val baseUri = "ftp://${server.host}${if (remotePath == "/") "" else remotePath}"
        files.filter { it.name.isNotEmpty() }.map { f ->
            FileEntry(
                name = f.name,
                path = "$baseUri/${f.name}",
                isDirectory = f.isDirectory,
                modifiedAt = f.timestamp?.timeInMillis ?: 0,
                mode = if (f.isDirectory) 0x41ED.toInt() else 0x81A4.toInt(),
                size = if (f.isDirectory) 0L else f.size,
            )
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        val entry = mlistFile(ftp, remotePath)
        if (entry == null) {
            ProviderStat(exists = false)
        } else {
            ProviderStat(
                exists = true,
                isDirectory = entry.isDirectory,
                totalBytes = if (entry.isDirectory) 0L else entry.size,
            )
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        if (!ftp.storeFile(remotePath, object : InputStream() {
            override fun read(): Int = -1
        })) {
            throw IllegalStateException("Failed to create file: $path")
        }
    }

    override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        if (!ftp.makeDirectory(remotePath)) {
            throw IllegalStateException("Failed to create directory: $path")
        }
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        if (path == "/") throw IllegalArgumentException("Cannot delete ftp:/// root")
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        val entry = mlistFile(ftp, remotePath)
        val ok = when {
            entry == null -> false
            entry.isDirectory -> ftp.removeDirectory(remotePath)
            else -> ftp.deleteFile(remotePath)
        }
        if (!ok) throw IllegalStateException("Failed to delete: $path")
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        val parent = remotePath.substringBeforeLast('/', "/")
        val to = if (parent.endsWith('/')) "$parent$newName" else "$parent/$newName"
        if (!ftp.rename(remotePath, to)) {
            throw IllegalStateException("Failed to rename: $path")
        }
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        if (mode != OpenMode.READ) throw UnsupportedOperationException("FtpProvider supports READ only")
        val (server, remotePath) = resolveServerPath(path)
        val ftp = client(server)
        val size = mlistFile(ftp, remotePath)?.takeUnless { it.isDirectory }?.size ?: 0L
        val raw = ftp.retrieveFileStream(remotePath)
            ?: throw FileNotFoundException("Not found: $path")
        val wrapped = object : FilterInputStream(raw) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { ftp.completePendingCommand() }
                }
            }
        }
        StreamFileSource(inputStream = wrapped, size = size, absolutePath = null)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (srcServer, srcPath) = resolveServerPath(from)
        val (dstServer, dstPath) = resolveServerPath(to)
        require(srcServer.host == dstServer.host) { "copy endpoints must use the same server" }
        val ftp = client(srcServer)
        val total = mlistFile(ftp, srcPath)?.takeUnless { it.isDirectory }?.size ?: 0L
        val input = ftp.retrieveFileStream(srcPath)
            ?: throw FileNotFoundException("Not found: $from")
        val output = ftp.storeFileStream(dstPath)
            ?: throw IllegalStateException("Cannot open destination: $to")
        try {
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
            runCatching { ftp.completePendingCommand() }
        }
    }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (server, remotePath) = resolveServerPath(destPath)
        val ftp = client(server)
        val output = ftp.storeFileStream(remotePath)
            ?: throw IllegalStateException("Cannot open destination: $destPath")
        var copied = 0L
        try {
            output.use { outs ->
                var offset = 0L
                while (true) {
                    val data = src.readChunk(offset, COPY_BUFFER_SIZE)
                    if (data.isEmpty()) break
                    outs.write(data)
                    offset += data.size
                    copied += data.size
                    emit(CopyProgress(src.size, copied, destPath, false, isCopy))
                }
            }
            check(ftp.completePendingCommand()) { "FTP server did not confirm the upload" }
            emit(CopyProgress(src.size, copied, destPath, true, isCopy))
        } catch (failure: Exception) {
            runCatching { ftp.disconnect() }.onFailure { android.util.Log.w("FtpProvider", "FTP cleanup failed", it) }
            throw failure
        }
    }.flowOn(Dispatchers.IO)

    // ── Internal ───────────────────────────────────────────────────

    private fun resolveServerPath(innerPath: String): Pair<FtpServer, String> {
        if (innerPath == "/" || innerPath.isEmpty()) {
            throw IllegalArgumentException("ftp:/// root has no server")
        }
        val trimmed = innerPath.trimStart('/')
        val slashIdx = trimmed.indexOf('/')
        val host = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
        val remotePath = if (slashIdx >= 0) trimmed.substring(slashIdx) else "/"
        val server = db.findByHost(host)
            ?: throw IllegalStateException("FTP server '$host' not registered")
        return server to remotePath
    }

    private fun client(server: FtpServer): FTPClient = synchronized(connLock) {
        clients[server.host]?.let { c ->
            if (c.isConnected && c.isAvailable) return@synchronized c
            runCatching { c.disconnect() }
            clients.remove(server.host)
        }
        val c = FTPClient()
        c.connectTimeout = TIMEOUT_MS
        c.connect(server.host, server.port)
        c.login(server.username, server.password)
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.enterLocalPassiveMode()
        clients[server.host] = c
        c
    }

    /** MLST probe with a graceful fallback to a single-entry LIST query. */
    private fun mlistFile(ftp: FTPClient, remotePath: String): FTPFile? {
        runCatching { ftp.mlistFile(remotePath) }.getOrNull()?.let { return it }
        val parent = remotePath.substringBeforeLast('/', "/")
        val name = remotePath.substringAfterLast('/')
        val dir = ftp.listFiles(if (parent.isEmpty()) "/" else parent)
        return dir?.firstOrNull { it.name == name }
    }
}
