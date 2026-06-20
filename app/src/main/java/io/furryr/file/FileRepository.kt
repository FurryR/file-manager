package io.furryr.file

import android.net.Uri
import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.daemon.DaemonLauncher
import io.furryr.file.daemon.StatResult
import io.furryr.file.model.DirectoryInfo
import io.furryr.file.model.FileEntry
import io.furryr.file.provider.FileProvider
import io.furryr.file.provider.ResolvedProvider
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking

object FileRepository {
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

    fun createFile(parentPath: String, name: String): Result<String> = runCatching {
        val fullPath = Path.parse(parentPath).child(name).toString()
        dispatch(fullPath) { p, ip -> runBlocking { p.createFile(ip) } }.getOrThrow()
        fullPath
    }

    fun createDirectory(parentPath: String, name: String): Result<String> = runCatching {
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

    fun info(path: String, entries: List<FileEntry>): DirectoryInfo {
        val stat = DaemonLauncher.getConnection().stat(path).getOrDefault(StatResult(0, 0, false, false))
        return DirectoryInfo(
            folderCount = entries.count { it.isDirectory },
            fileCount = entries.count { !it.isDirectory },
            usableBytes = stat.usableBytes,
            totalBytes = stat.totalBytes,
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
                runCatching { dst.provider.createDirectory(dst.innerPath) }
            }
            val entries = runBlocking(Dispatchers.IO) {
                src.provider.list(src.innerPath).getOrDefault(emptyList())
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
