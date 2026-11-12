package io.furryr.file.provider.remote

import io.furryr.file.provider.RemoteLocationSource
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import io.furryr.file.core.DirectoryCapabilities
import io.furryr.file.core.FileProvider
import io.furryr.file.core.ProviderFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

object RemoteProviders {
    fun providers(cacheDir: File, locations: RemoteLocationSource): List<FileProvider> =
        RemoteKind.entries.map { kind -> RemoteProvider(cacheDir, kind, locations) }

    suspend fun verify(location: RemoteLocation): Result<Unit> = runInterruptible(Dispatchers.IO) {
        runCatching { location.validate(); backend(location).list(normalizeRemotePath(location.basePath)); Unit }
    }

    /** Drop live SSH resources for a connection the app removed or edited. */
    fun forget(id: String) {
        SshControls.close(id)
        SshConnections.disconnect(id)
    }
}

/** Both remote protocols share complete file-source and cross-server copy semantics. */
private class RemoteProvider(
    private val cacheDir: File,
    private val kind: RemoteKind,
    private val locations: RemoteLocationSource,
) : FileProvider {
    override val scheme get() = kind.scheme
    override val authority: String? = null
    override val label get() = if (kind == RemoteKind.ALIST) "AList" else "SSH commands"
    override val writable get() = false
    override val features = setOf(ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.OPEN,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR, ProviderFeature.DELETE, ProviderFeature.RENAME, ProviderFeature.STREAM_COPY)
    override fun isAvailable() = true
    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() {
        if (kind == RemoteKind.SSH) {
            SshControls.closeAll()
            SshConnections.disconnectAll()
        }
    }

    override suspend fun list(path: String): Result<List<FileEntry>> = io {
        if (path == "/") locations.remoteLocations().filter { it.kind == kind }.map {
            // The connection list shows the same address the paths use, so the
            // folder name matches what the agent and the path bar display.
            val label = if (kind == RemoteKind.SSH) it.pathAuthority else it.name
            FileEntry(label, it.entryUri, true, 0, mode = 0x41ED)
        } else {
            val (location, remote) = resolve(path)
            backend(location).list(remote).map { entry ->
                validName(entry.name)
                FileEntry(entry.name, "${kind.scheme}://${location.pathAuthority}${remote.trimEnd('/')}/${entry.name}",
                    entry.isDirectory, entry.modifiedAt, entry.isSymlink, mode = entry.mode, size = entry.size)
            }
        }
    }
    override suspend fun stat(path: String): Result<ProviderStat> = io {
        if (path == "/") ProviderStat(exists = true, isDirectory = true) else {
            val (location, remote) = resolve(path)
            val client = backend(location)
            val info = client.stat(remote)
            // A directory reports its volume's capacity, not the entry size, so
            // the footer shows what can still be written there.
            val space = if (info?.isDirectory == true) runCatching { client.space(remote) }.getOrNull() else null
            ProviderStat(exists = info != null, isDirectory = info?.isDirectory == true,
                totalBytes = space?.totalBytes ?: info?.size ?: 0,
                usableBytes = space?.usableBytes ?: 0,
                canEnter = info?.isDirectory == true)
        }
    }
    override suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        if (path == "/") return DirectoryCapabilities()
        return io {
            val (location, remote) = resolve(path)
            DirectoryCapabilities(
                canCreate = backend(location).canWrite(remote),
                canOpenTerminal = kind == RemoteKind.SSH,
            )
        }.getOrElse { DirectoryCapabilities() }
    }
    override suspend fun createFile(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path); backend(location).createFile(mutablePath(remote))
    }
    override suspend fun createDirectory(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path); backend(location).createDirectory(mutablePath(remote))
    }
    override suspend fun delete(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path); backend(location).delete(mutablePath(remote))
    }
    override suspend fun rename(path: String, newName: String): Result<Unit> = io {
        val (location, remote) = resolve(path); backend(location).rename(mutablePath(remote), validName(newName))
    }
    override suspend fun prepareReplacement(original: String, replacement: String): Result<Unit> = io {
        val (location, source) = resolve(original)
        val (other, target) = resolve(replacement)
        require(location.id == other.id) { "Replacement must use the same remote connection" }
        backend(location).prepareReplacement(mutablePath(source), mutablePath(target))
    }
    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = io {
        val (location, remote) = resolve(path)
        val client = backend(location)
        val display = "${kind.scheme}://${location.pathAuthority}$remote"
        if (mode == OpenMode.WRITE) {
            val file = newCache()
            return@io try {
                mutablePath(remote)
                SeekableCacheFileSource(file, mode, display,
                    commit = { staged -> client.upload(remote, staged) })
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
        val info = client.stat(remote) ?: throw FileNotFoundException("Remote file does not exist")
        require(!info.isDirectory) { "Cannot open a directory as a file" }
        // Remote reads stream straight off the wire; nothing is staged first.
        if (client.streams) return@io client.openRead(remote, info.size)
        val file = newCache()
        try {
            client.download(remote, file)
            SeekableCacheFileSource(file, mode, display, commit = null)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }
    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        require(from != to) { "Source and destination are the same file" }
        val source = open(from, OpenMode.READ).getOrThrow()
        source.use { copy(it, to, isCopy).collect { progress -> emit(progress) } }
    }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (location, remote) = resolve(destPath)
        mutablePath(remote)
        val client = backend(location)
        val total = src.size
        if (client.streams && src.readable && total > 0) {
            // Read and write at the same time: progress tracks the live wire,
            // not a local staging copy that finishes before the upload starts.
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
        } finally { staged.delete() }
    }.flowOn(Dispatchers.IO)

    private fun newCache(): File = File.createTempFile("remote-", ".stage", cacheDir)
    private fun resolve(path: String): Pair<RemoteLocation, String> {
        require(path != "/") { "Select a remote connection first" }
        val inner = path.trimStart('/')
        val authority = inner.substringBefore('/')
        val candidates = locations.remoteLocations().filter { it.kind == kind }
        val location = candidates.firstOrNull { it.id == authority }
            ?: candidates.firstOrNull { it.pathAuthority == authority }
            ?: error("Remote connection $authority no longer exists; edit the location")
        return location to normalizeRemotePath(if ('/' in inner) "/" + inner.substringAfter('/') else "/")
    }
    private suspend fun <T> io(block: () -> T): Result<T> = runInterruptible(Dispatchers.IO) { runCatching(block) }
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
