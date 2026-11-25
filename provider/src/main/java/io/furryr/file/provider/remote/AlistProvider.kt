package io.furryr.file.provider.remote

import io.furryr.file.core.FileProvider
import io.furryr.file.core.ProviderFeature
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import io.furryr.file.provider.RemoteLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileNotFoundException

/** The AList provider; file bytes are staged because the API is not seekable. */
internal class AlistProvider(
    private val cacheDir: File,
    private val locations: RemoteLocationSource,
) : FileProvider {
    override val schemes: Set<String> get() = setOf(RemoteSchemes.ALIST)
    override val label: String get() = "AList"
    override val features = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.OPEN,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE, ProviderFeature.RENAME, ProviderFeature.STREAM_COPY,
    )

    override fun isAvailable(): Boolean = true
    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() = Unit

    override suspend fun list(path: String): Result<List<FileEntry>> = io {
        if (path == "/") {
            candidates().map {
                FileEntry(it.rootEntryName(), it.entryUri, true, 0, mode = 0x41ED)
            }
        } else {
            val (location, remote) = resolve(path)
            AlistBackend(location).list(remote).map { entry ->
                validName(entry.name)
                FileEntry(
                    name = entry.name,
                    path = "${RemoteSchemes.ALIST}://${location.pathAuthority}${remote.trimEnd('/')}/${entry.name}",
                    isDirectory = entry.isDirectory,
                    modifiedAt = entry.modifiedAt,
                    isSymlink = entry.isSymlink,
                    mode = entry.mode,
                    size = entry.size,
                )
            }
        }
    }

    override suspend fun stat(path: String): Result<ProviderStat> = io {
        if (path == "/") ProviderStat(exists = true, isDirectory = true) else {
            val (location, remote) = resolve(path)
            val info = AlistBackend(location).stat(remote)
            ProviderStat(
                exists = info != null,
                isDirectory = info?.isDirectory == true,
                totalBytes = info?.size ?: 0,
                size = info?.size ?: 0,
                modifiedAt = info?.modifiedAt ?: 0,
            )
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        AlistBackend(location).createFile(mutablePath(remote))
    }

    override suspend fun createDirectory(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        AlistBackend(location).createDirectory(mutablePath(remote))
    }

    override suspend fun delete(path: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        AlistBackend(location).delete(mutablePath(remote))
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = io {
        val (location, remote) = resolve(path)
        AlistBackend(location).rename(mutablePath(remote), validName(newName))
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = io {
        val (location, remote) = resolve(path)
        val client = AlistBackend(location)
        val display = "${RemoteSchemes.ALIST}://${location.pathAuthority}$remote"
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
        val client = AlistBackend(location)
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
            client.upload(remote, staged)
            emit(CopyProgress(offset, offset, remote, true, isCopy))
        } finally {
            staged.delete()
        }
    }.flowOn(Dispatchers.IO)

    private fun candidates(): List<AlistLocation> =
        locations.remoteLocations().filterIsInstance<AlistLocation>()

    private fun resolve(path: String): Pair<AlistLocation, String> {
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
