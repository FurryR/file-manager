package io.furryr.file.provider.remote

import io.furryr.file.core.FileProvider
import io.furryr.file.core.MetadataProvider
import io.furryr.file.core.ProviderFeature
import io.furryr.file.core.SystemAccounts
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import io.furryr.file.provider.RemoteLocationSource
import io.furryr.file.provider.terminal.ExecProvider
import io.furryr.file.provider.terminal.SshTerminalProcess
import io.furryr.file.provider.terminal.TerminalProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** The SSH provider; files stream over live SFTP/exec channels. */
internal class SshProvider(
    private val cacheDir: File,
    private val locations: RemoteLocationSource,
) : FileProvider, ExecProvider, MetadataProvider {
    override val schemes: Set<String> get() = setOf(RemoteSchemes.SSH)
    override val label: String get() = "SSH commands"
    override val features = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.OPEN,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE, ProviderFeature.RENAME, ProviderFeature.STREAM_COPY,
    )

    override fun isAvailable(): Boolean = true
    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {
        SshControls.closeAll()
        SshWorkers.closeAll()
        SshConnections.disconnectAll()
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = io {
        if (path == "/") {
            candidates().map {
                FileEntry(it.rootEntryName(), it.entryUri, true, 0, mode = 0x41ED)
            }
        } else {
            val (location, remote) = resolve(path)
            SshExecBackend(location).list(remote).map { entry ->
                validName(entry.name)
                FileEntry(
                    name = entry.name,
                    path = "${RemoteSchemes.SSH}://${location.pathAuthority}${remote.trimEnd('/')}/${entry.name}",
                    isDirectory = entry.isDirectory,
                    modifiedAt = entry.modifiedAt,
                    isSymlink = entry.isSymlink,
                    mode = entry.mode,
                    size = entry.size,
                    uid = entry.uid,
                    gid = entry.gid,
                    owner = entry.owner,
                    group = entry.group,
                )
            }
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = io {
        if (path == "/") ProviderStat(exists = true, isDirectory = true) else {
            val (location, remote) = resolve(path)
            val client = SshExecBackend(location)
            val info = client.stat(remote)
            val space = if (info?.isDirectory == true) runCatching { client.space(remote) }.getOrNull() else null
            ProviderStat(
                exists = info != null,
                isDirectory = info?.isDirectory == true,
                totalBytes = space?.first ?: info?.size ?: 0,
                usableBytes = space?.second ?: 0,
                size = info?.size ?: 0,
                modifiedAt = info?.modifiedAt ?: 0,
            )
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).createFile(mutablePath(remote))
    }

    override suspend fun createDirectory(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).createDirectory(mutablePath(remote))
    }

    override suspend fun delete(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).delete(mutablePath(remote))
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).rename(mutablePath(remote), validName(newName))
    }

    override suspend fun prepareReplacement(original: String, replacement: String): Result<Unit> = io {
        val (location, source) = resolve(original)
        val (other, target) = resolve(replacement)
        require(location.id == other.id) { "Replacement must use the same remote connection" }
        SshExecBackend(location).prepareReplacement(mutablePath(source), mutablePath(target))
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = io {
        val (location, remote) = resolve(path)
        val client = SshExecBackend(location)
        val display = "${RemoteSchemes.SSH}://${location.pathAuthority}$remote"
        if (mode == OpenMode.WRITE) {
            val file = newCache()
            return@io try {
                mutablePath(remote)
                SeekableCacheFileSource(file, mode, display, commit = { staged -> client.upload(remote, staged) })
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
        val info = client.stat(remote) ?: throw FileNotFoundException("Remote file does not exist")
        require(!info.isDirectory) { "Cannot open a directory as a file" }
        client.openRead(remote, info.size)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        require(from != to) { "Source and destination are the same file" }
        val source = open(from, OpenMode.READ).getOrThrow()
        source.use { copy(it, to, isCopy).collect { progress -> emit(progress) } }
    }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (location, remote) = resolve(destPath)
        mutablePath(remote)
        val client = SshExecBackend(location)
        val total = src.size
        if (src.readable && total > 0) {
            val counter = AtomicLong(0)
            val input = CountingInputStream(SourceInputStream(src), counter)
            coroutineScope {
                val upload = async(Dispatchers.IO) {
                    runInterruptible { client.uploadStream(remote, input, total) }
                }
                try {
                    while (!upload.isCompleted) {
                        emit(CopyProgress(total, minOf(counter.get(), total), remote, false, isCopy))
                        delay(200)
                    }
                    upload.await()
                } catch (failure: Throwable) {
                    upload.cancel()
                    throw failure
                }
            }
            emit(CopyProgress(total, total, remote, true, isCopy))
            return@flow
        }
        val staged = newCache()
        try {
            var offset = 0L
            staged.outputStream().use { output ->
                while (true) {
                    interrupted()
                    val bytes = src.readChunk(offset, 256 * 1024)
                    if (bytes.isEmpty()) break
                    output.write(bytes)
                    offset += bytes.size
                    emit(CopyProgress(src.size, offset, remote, false, isCopy))
                }
            }
            interrupted()
            client.upload(remote, staged)
            emit(CopyProgress(offset, offset, remote, true, isCopy))
        } finally {
            staged.delete()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun setMode(
        path: String,
        mode: Int,
        applyFiles: Boolean,
        applyDirs: Boolean,
    ): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).setMode(mutablePath(remote), mode, applyFiles, applyDirs)
    }

    override suspend fun setOwner(
        path: String,
        uid: Int,
        gid: Int,
        applyFiles: Boolean,
        applyDirs: Boolean,
    ): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).setOwner(mutablePath(remote), uid, gid, applyFiles, applyDirs)
    }

    override suspend fun setModifiedTime(
        path: String,
        modifiedAt: Long,
        applyFiles: Boolean,
        applyDirs: Boolean,
    ): Result<Unit> = io {
        val (location, remote) = resolve(path)
        SshExecBackend(location).setModifiedTime(mutablePath(remote), modifiedAt, applyFiles, applyDirs)
    }

    override suspend fun accounts(path: String): Result<SystemAccounts> = io {
        val (location, _) = resolve(path)
        SshExecBackend(location).accounts()
    }

    override suspend fun terminal(
        cwd: VFolder,
        rows: Int,
        cols: Int,
        terminalId: String,
    ): Result<TerminalProcess> =
        runInterruptible(Dispatchers.IO) {
            runCatching {
                val (location, remote) = resolve(innerPath(cwd.path))
                SshTerminalProcess(SshTerminals.open(location, remote, rows, cols))
            }
        }

    /** Only this SSH connection's own entries exist in its shell. */
    override fun terminalPath(cwd: VFolder, node: VNode): String? {
        if (node.provider !== this) return null
        return runCatching {
            val target = resolve(innerPath(cwd.path)).first
            val (location, remote) = resolve(innerPath(node.path))
            if (location.id == target.id) remote else null
        }.getOrNull()
    }

    private fun candidates(): List<SshLocation> =
        locations.remoteLocations().filterIsInstance<SshLocation>()

    private fun resolve(path: String): Pair<SshLocation, String> {
        require(path != "/") { "Select a remote connection first" }
        val inner = path.trimStart('/')
        val authority = inner.substringBefore('/')
        val location = candidates().firstOrNull { it.id == authority }
            ?: candidates().firstOrNull { it.pathAuthority == authority }
            ?: error("Remote connection $authority no longer exists; edit the location")
        return location to normalizeRemotePath(if ('/' in inner) "/" + inner.substringAfter('/') else "/")
    }

    private fun newCache(): File = File.createTempFile("remote-", ".stage", cacheDir)

    private suspend fun <T> io(block: () -> T): Result<T> =
        runInterruptible(Dispatchers.IO) { runCatching(block) }
}

/** Sequential [InputStream] adapter over a positional [FileSource]. */
private class SourceInputStream(private val source: FileSource) : InputStream() {
    private var offset = 0L
    private var buffer = ByteArray(0)
    private var position = 0
    private var eof = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= buffer.size) {
            if (eof) return -1
            buffer = source.readChunk(this.offset, CHUNK)
            position = 0
            if (buffer.isEmpty()) {
                eof = true
                return -1
            }
        }
        val count = minOf(length, buffer.size - position)
        System.arraycopy(buffer, position, bytes, offset, count)
        position += count
        this.offset += count
        return count
    }

    private companion object {
        const val CHUNK = 256 * 1024
    }
}

/** Reports how many bytes the remote side has pulled while an upload runs. */
private class CountingInputStream(
    private val input: InputStream,
    private val counter: AtomicLong,
) : InputStream() {
    override fun read(): Int = input.read().also { if (it >= 0) counter.incrementAndGet() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        input.read(bytes, offset, length).also { if (it > 0) counter.addAndGet(it.toLong()) }
    override fun close() = input.close()
}
