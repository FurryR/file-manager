package io.furryr.file

import android.net.Uri
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.StreamFileSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.furryr.file.model.DirectoryInfo
import io.furryr.file.core.model.FileEntry
import io.furryr.file.provider.FileProvider
import io.furryr.file.provider.DirectoryCapabilities
import io.furryr.file.core.PermissionDeniedException
import io.furryr.file.provider.ResolvedProvider
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking

object FileRepository {
    private val writeLocks = ConcurrentHashMap<String, Any>()
    private val providers = mutableMapOf<String, FileProvider>()
    private var default: FileProvider? = null

    fun register(scheme: String, provider: FileProvider) { providers[scheme] = provider }
    fun setDefault(provider: FileProvider) { default = provider }

    fun resolve(uri: String): Result<ResolvedProvider> {
        if (uri.startsWith("/")) {
            val def = default ?: return Result.failure(IllegalStateException("No default provider"))
            return Result.success(ResolvedProvider(def, Path.parse(uri), uri))
        }
        val parsed = try { Uri.parse(uri) } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Cannot parse URI: $uri"))
        }
        val scheme = parsed.scheme ?: return Result.failure(IllegalArgumentException("No scheme in URI: $uri"))
        val provider = providers[scheme]
            ?: return Result.failure(IllegalStateException("No provider for scheme '$scheme'"))
        return Result.success(ResolvedProvider(provider, Path.parse(uri), uri))
    }

    fun navigate(currentPath: String, relPath: String): String? {
        val p = Path.parse(currentPath)
        val result = p.navigate(relPath)
        if (result == p) return null
        return result.toString()
    }

    // ── Simple ops ──────────────────────────────────────────────

    fun list(path: String) = dispatch(path) { p, ip -> runBlocking { p.list(ip) } }

    fun stat(path: String) = dispatch(path) { p, ip -> runBlocking { p.stat(ip) } }

    /** Stage beside the destination; a failed transfer never truncates the original. */
    fun writeStream(path: String, input: InputStream, size: Long, expectedDigest: String? = null,
        beforePublish: () -> Unit = {}): Result<Unit> = runCatching {
        StreamFileSource(input, size).use { source -> synchronized(writeLocks.computeIfAbsent(path) { Any() }) {
            val target = resolve(path).getOrThrow()
            val parent = target.path.parent() ?: error("Cannot replace a provider root")
            val name = target.path.name ?: error("Missing file name")
            val marker = UUID.randomUUID().toString()
            val temporaryName = ".${name.take(48)}.fm-$marker.tmp"
            val backupName = ".${name.take(48)}.fm-$marker.bak"
            val temporary = resolve(parent.child(temporaryName).toString()).getOrThrow()
            val backup = resolve(parent.child(backupName).toString()).getOrThrow()
            runBlocking(Dispatchers.IO) {
                val original = target.provider.stat(target.innerPath).getOrThrow()
                require(!original.isDirectory) { "Cannot write a directory" }
                var staged = false; var movedOriginal = false; var published = false
                try {
                    target.provider.copy(source, temporary.innerPath, true).collect { }
                    staged = true
                    if (expectedDigest != null) check(digest(path).getOrThrow() == expectedDigest) { "The file changed while saving; reopen it before overwriting" }
                    val current = target.provider.stat(target.innerPath).getOrThrow()
                    check(current.exists == original.exists && !current.isDirectory) { "Destination changed while saving" }
                    if (original.exists) target.provider.prepareReplacement(target.innerPath, temporary.innerPath).getOrThrow()
                    beforePublish()
                    if (original.exists) {
                        target.provider.rename(target.innerPath, backupName).getOrThrow()
                        movedOriginal = true
                    }
                    target.provider.rename(temporary.innerPath, name).getOrThrow()
                    published = true
                    if (movedOriginal) target.provider.delete(backup.innerPath).onFailure {
                        android.util.Log.w("FileRepository", "Saved file, but the previous version remains at ${backup.path}", it)
                    }
                } catch (failure: Exception) {
                    if (movedOriginal && !published) {
                        try {
                            if (!target.provider.stat(target.innerPath).getOrThrow().exists) {
                                target.provider.rename(backup.innerPath, name).getOrThrow()
                                movedOriginal = false
                            }
                        } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                    }
                    if (!staged || failure is CancellationException && !movedOriginal && !published) {
                        target.provider.delete(temporary.innerPath)
                            .onSuccess { staged = false }
                            .onFailure { android.util.Log.w("FileRepository", "Incomplete save staging file could not be removed", it) }
                    }
                    val recovery = buildList {
                        if (staged && !published) add(temporary.path.toString())
                        if (movedOriginal && !published) add(backup.path.toString())
                    }
                    if (failure is CancellationException) throw failure
                    throw IOException((failure.message ?: "Save failed") + if (recovery.isEmpty()) "" else "\nRecovery files: ${recovery.joinToString(", ")}", failure)
                }
            }
        } }
    }

    fun writeBytes(path: String, bytes: ByteArray, expectedDigest: String? = null): Result<Unit> =
        writeStream(path, ByteArrayInputStream(bytes), bytes.size.toLong(), expectedDigest)

    fun digest(path: String): Result<String> = runCatching {
        val target = resolve(path).getOrThrow()
        val digest = MessageDigest.getInstance("SHA-256")
        runBlocking { target.provider.open(target.innerPath, OpenMode.READ).getOrThrow() }.use { source ->
            var offset = 0L
            while (true) {
                val bytes = source.readChunk(offset, 256 * 1024); if (bytes.isEmpty()) break
                digest.update(bytes); offset += bytes.size
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun directoryCapabilities(path: String): DirectoryCapabilities {
        val resolved = resolve(path).getOrNull() ?: return DirectoryCapabilities()
        return resolved.provider.directoryCapabilities(resolved.innerPath)
    }

    fun createFile(parentPath: String, name: String): Result<String> = runCatching {
        if (!runBlocking { directoryCapabilities(parentPath) }.canCreate) throw PermissionDeniedException(parentPath)
        val fullPath = Path.parse(parentPath).child(name).toString()
        dispatch(fullPath) { p, ip -> runBlocking { p.createFile(ip) } }.getOrThrow()
        fullPath
    }

    fun createDirectory(parentPath: String, name: String): Result<String> = runCatching {
        if (!runBlocking { directoryCapabilities(parentPath) }.canCreate) throw PermissionDeniedException(parentPath)
        val fullPath = Path.parse(parentPath).child(name).toString()
        dispatch(fullPath) { p, ip -> runBlocking { p.createDirectory(ip) } }.getOrThrow()
        fullPath
    }

    // ── Event-driven copy/move ──────────────────────────────────

    fun copy(from: String, toDir: String): Flow<CopyProgress> = flow {
        val srcName = Path.parse(from).name ?: from.substringAfterLast('/')
        val toPath = Path.parse(toDir).child(srcName).toString()
        val srcResolved = resolve(from).getOrThrow()
        val dstResolved = resolve(toPath).getOrThrow()
        copyRecursive(srcResolved, dstResolved, isCopy = true).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun move(from: String, toDir: String): Flow<CopyProgress> = flow {
        val srcName = Path.parse(from).name ?: from.substringAfterLast('/')
        val toPath = Path.parse(toDir).child(srcName).toString()
        val srcResolved = resolve(from).getOrThrow()
        val dstResolved = resolve(toPath).getOrThrow()
        copyRecursive(srcResolved, dstResolved, isCopy = false).collect { emit(it) }
        runBlocking(Dispatchers.IO) {
            runCatching { srcResolved.provider.delete(srcResolved.innerPath) }
        }
    }.flowOn(Dispatchers.IO)

    fun rename(path: String, newName: String): Result<Unit> =
        dispatch(path) { p, ip -> runBlocking { p.rename(ip, newName) } }

    fun delete(path: String): Result<Unit> =
        dispatch(path) { p, ip -> runBlocking { p.delete(ip) } }

    suspend fun info(path: String, entries: List<FileEntry>): DirectoryInfo {
        val stat = resolve(path).getOrNull()?.let { resolved ->
            resolved.provider.stat(resolved.innerPath).getOrNull()
        }
        return DirectoryInfo(
            folderCount = entries.count { it.isDirectory },
            fileCount = entries.count { !it.isDirectory },
            usableBytes = stat?.usableBytes ?: 0,
            totalBytes = stat?.totalBytes ?: 0,
        )
    }

    // ── Internal ─────────────────────────────────────────────────

    private inline fun <T> dispatch(path: String, block: (FileProvider, String) -> Result<T>): Result<T> {
        val resolved = resolve(path).getOrElse { return Result.failure(it) }
        return block(resolved.provider, resolved.innerPath)
    }

    /**
     * Kotlin-side recursive copy. Directories: create dest → list → recurse.
      * Files: same provider → [FileProvider.copy]; cross → [FileProvider.open] + [FileProvider.copy].
     */
    private fun copyRecursive(
        src: ResolvedProvider, dst: ResolvedProvider, isCopy: Boolean,
    ): Flow<CopyProgress> = flow {
        val stat = runBlocking(Dispatchers.IO) { src.provider.stat(src.innerPath).getOrThrow() }

        if (stat.isDirectory) {
            runBlocking(Dispatchers.IO) {
                dst.provider.createDirectory(dst.innerPath).getOrThrow()
            }
            val entries = runBlocking(Dispatchers.IO) {
                src.provider.list(src.innerPath).getOrThrow()
            }
            for (entry in entries) {
                val childSrcPath = src.path.child(entry.name).toString()
                val childDstPath = dst.path.child(entry.name).toString()
                val childSrc = resolve(childSrcPath).getOrNull() ?: continue
                val childDst = resolve(childDstPath).getOrNull() ?: continue
                copyRecursive(childSrc, childDst, isCopy).collect { emit(it) }
            }
        } else if (src.provider === dst.provider) {
            dst.provider.copy(src.innerPath, dst.innerPath, isCopy).collect { emit(it) }
        } else {
            val fileSrc = runBlocking(Dispatchers.IO) {
                src.provider.open(src.innerPath, OpenMode.READ).getOrThrow()
            }
            try {
                dst.provider.copy(fileSrc, dst.innerPath, isCopy).collect { emit(it) }
            } finally {
                runCatching { fileSrc.close() }
            }
        }
    }.flowOn(Dispatchers.IO)
}
