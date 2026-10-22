package io.furryr.file.provider.remote

import android.content.Context
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileNotFoundException

object RemoteProviders {
    fun install(context: Context) {
        RemoteLocationStore.init(context.applicationContext)
    }

    fun providers(context: Context): List<FileProvider> {
        val appContext = context.applicationContext
        return RemoteKind.entries.map { kind -> RemoteProvider(appContext, kind) }
    }

    suspend fun verify(location: RemoteLocation): Result<Unit> = runInterruptible(Dispatchers.IO) {
        runCatching { location.validate(); backend(location).list(normalizeRemotePath(location.basePath)); Unit }
    }
}

/** Both remote protocols share complete file-source and cross-server copy semantics. */
private class RemoteProvider(private val context: Context, private val kind: RemoteKind) : FileProvider {
    override val scheme get() = kind.scheme
    override val authority: String? = null
    override val label get() = if (kind == RemoteKind.ALIST) "AList" else "SSH commands"
    override val features = setOf(ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.OPEN,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR, ProviderFeature.DELETE, ProviderFeature.RENAME, ProviderFeature.STREAM_COPY)
    override fun isAvailable() = true
    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() = Unit // each operation owns its connection

    override suspend fun list(path: String): Result<List<FileEntry>> = io {
        if (path == "/") RemoteLocationStore.all().filter { it.kind == kind }.map {
            FileEntry(it.name, it.uri, true, 0, mode = 0x41ED)
        } else {
            val (location, remote) = resolve(path)
            backend(location).list(remote).map { entry ->
                validName(entry.name)
                FileEntry(entry.name, "${kind.scheme}://${location.id}${remote.trimEnd('/')}/${entry.name}",
                    entry.isDirectory, entry.modifiedAt, entry.isSymlink, mode = entry.mode, size = entry.size)
            }
        }
    }
    override suspend fun stat(path: String): Result<ProviderStat> = io {
        if (path == "/") ProviderStat(exists = true, isDirectory = true) else {
            val (location, remote) = resolve(path)
            val info = backend(location).stat(remote)
            ProviderStat(exists = info != null, isDirectory = info?.isDirectory == true,
                totalBytes = info?.size ?: 0, canEnter = info?.isDirectory == true)
        }
    }
    override suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        if (path == "/") return DirectoryCapabilities()
        return io { val (location, remote) = resolve(path); DirectoryCapabilities(canCreate = backend(location).canWrite(remote)) }
            .getOrDefault(DirectoryCapabilities())
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
        val file = newCache()
        try {
            if (mode != OpenMode.WRITE) {
                val info = client.stat(remote) ?: throw FileNotFoundException("Remote file does not exist")
                require(!info.isDirectory) { "Cannot open a directory as a file" }
                client.download(remote, file)
            } else mutablePath(remote)
            SeekableCacheFileSource(file, mode, "${kind.scheme}://${location.id}$remote",
                commit = if (mode == OpenMode.READ) null else ({ staged -> client.upload(remote, staged) }))
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        require(from != to) { "Source and destination are the same file" }
        val source = open(from, OpenMode.READ).getOrThrow()
        source.use { copy(it, to, isCopy).collect { progress -> emit(progress) } }
    }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val (location, remote) = resolve(destPath)
        mutablePath(remote)
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
            backend(location).upload(remote, staged)
            emit(CopyProgress(offset, offset, remote, true, isCopy))
        } finally { staged.delete() }
    }.flowOn(Dispatchers.IO)

    private fun newCache(): File = File.createTempFile("remote-", ".stage", context.cacheDir)
    private fun resolve(path: String): Pair<RemoteLocation, String> {
        require(path != "/") { "Select a remote connection first" }
        val inner = path.trimStart('/')
        val id = inner.substringBefore('/')
        val location = RemoteLocationStore.find(id)
        require(location.kind == kind) { "Remote protocol does not match this connection" }
        return location to normalizeRemotePath(if ('/' in inner) "/" + inner.substringAfter('/') else "/")
    }
    private suspend fun <T> io(block: () -> T): Result<T> = runInterruptible(Dispatchers.IO) { runCatching(block) }
}
