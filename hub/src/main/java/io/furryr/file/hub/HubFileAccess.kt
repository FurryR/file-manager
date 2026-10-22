package io.furryr.file.hub

import com.google.protobuf.ByteString
import io.furryr.file.core.Vfs
import io.furryr.file.core.source.OpenMode
import io.furryr.file.daemon.DaemonManager
import io.furryr.file.daemon.ipc.*
import io.furryr.file.daemon.DaemonTarget
import io.furryr.file.proto.HandleMode
import io.furryr.file.proto.hub.CopyFileRequest
import io.furryr.file.proto.hub.CopyFileResponse
import io.furryr.file.proto.hub.GlobRequest
import io.furryr.file.proto.hub.GlobResponse
import io.furryr.file.proto.hub.ListDirectoryEntry
import io.furryr.file.proto.hub.ListDirectoryRequest
import io.furryr.file.proto.hub.ListDirectoryResponse
import io.furryr.file.proto.hub.ReadFileRequest
import io.furryr.file.proto.hub.ReadFileResponse
import io.furryr.file.proto.hub.WriteFileRequest
import io.furryr.file.proto.hub.WriteFileResponse
import java.io.ByteArrayOutputStream

private const val DEFAULT_READ_SIZE = 64L * 1024
private const val MAX_READ_SIZE = 1024L * 1024
private const val CHUNK_SIZE = 64 * 1024
private const val DEFAULT_GLOB_RESULTS = 200
private const val MAX_GLOB_RESULTS = 1000
private const val MAX_GLOB_VISITS = 20_000
private const val MAX_DIRECTORY_ENTRIES = 2_000

internal suspend fun readFile(request: ReadFileRequest, target: DaemonTarget): ReadFileResponse {
    require(request.path.isNotBlank()) { "read requires a path" }
    if (isVfsPath(request.path)) return readVfsFile(request)
    val connection = DaemonManager.connection(target)
    val handle = connection.openHandle(request.path, HandleMode.READ, wantFd = false)
        .getOrThrow()
    try {
        val size = handle.size
        val offset = request.offset.coerceAtMost(size)
        val limit = if (request.length <= 0) DEFAULT_READ_SIZE
        else request.length.toLong().coerceAtMost(MAX_READ_SIZE)
        val output = ByteArrayOutputStream()
        var position = offset
        var eof = position >= size
        while (!eof && output.size() < limit) {
            val remaining = (limit - output.size()).coerceAtMost(CHUNK_SIZE.toLong()).toInt()
            val chunk = connection.readHandle(handle.handleId, position, remaining).getOrThrow()
            val data = chunk.data.toByteArray()
            if (data.isEmpty()) {
                eof = true
                break
            }
            output.write(data)
            position += data.size
            eof = chunk.eof || position >= size
        }
        return ReadFileResponse.newBuilder()
            .setData(ByteString.copyFrom(output.toByteArray()))
            .setSize(size)
            .setEof(eof)
            .build()
    } finally {
        runCatching { connection.closeHandle(handle.handleId) }
    }
}

internal suspend fun readVfsFile(request: ReadFileRequest): ReadFileResponse {
    val source = Vfs.file(request.path).getOrThrow().open(OpenMode.READ).getOrThrow()
    return source.use {
        val size = source.size
        val offset = request.offset.coerceAtMost(size)
        val limit = if (request.length <= 0) DEFAULT_READ_SIZE
        else request.length.toLong().coerceAtMost(MAX_READ_SIZE)
        val output = ByteArrayOutputStream()
        var position = offset
        var eof = position >= size
        while (!eof && output.size() < limit) {
            val remaining = (limit - output.size()).coerceAtMost(CHUNK_SIZE.toLong()).toInt()
            val data = source.readChunk(position, remaining)
            if (data.isEmpty()) {
                eof = true
                break
            }
            output.write(data)
            position += data.size
            eof = position >= size
        }
        ReadFileResponse.newBuilder()
            .setData(ByteString.copyFrom(output.toByteArray()))
            .setSize(size)
            .setEof(eof)
            .build()
    }
}

internal suspend fun listDirectory(
    request: ListDirectoryRequest,
    target: DaemonTarget,
): ListDirectoryResponse {
    val path = request.path.ifBlank { "/" }
    val entries = if (isVfsPath(path)) {
        Vfs.folder(path).getOrThrow().list().getOrThrow()
    } else {
        DaemonManager.connection(target).listFiles(path).getOrThrow()
    }
    val sorted = entries.sortedWith(
        compareByDescending<io.furryr.file.core.model.FileEntry> { it.isDirectory }
            .thenBy { it.name.lowercase() },
    )
    val limited = sorted.take(MAX_DIRECTORY_ENTRIES)
    return ListDirectoryResponse.newBuilder()
        .addAllEntries(
            limited.map { entry ->
                ListDirectoryEntry.newBuilder()
                    .setName(entry.name)
                    .setPath(entry.path)
                    .setDirectory(entry.isDirectory)
                    .setSize(entry.size)
                    .setSymlink(entry.isSymlink)
                    .build()
            },
        )
        .setTruncated(sorted.size > limited.size)
        .build()
}

internal suspend fun writeFile(request: WriteFileRequest, target: DaemonTarget): WriteFileResponse {
    require(request.path.isNotBlank()) { "write requires a path" }
    val data = request.data.toByteArray()
    if (isVfsPath(request.path)) {
        Vfs.file(request.path).getOrThrow().open(OpenMode.WRITE).getOrThrow().use { source ->
            if (data.isNotEmpty()) source.writeAt(0, data)
            if (source.seekable) source.truncate(data.size.toLong())
        }
        return WriteFileResponse.newBuilder().setBytesWritten(data.size.toLong()).build()
    }
    val connection = DaemonManager.connection(target)
    val handle = connection.openHandle(request.path, HandleMode.WRITE, wantFd = false)
        .getOrThrow()
    try {
        if (data.isNotEmpty()) {
            connection.writeHandle(handle.handleId, 0, data).getOrThrow()
        }
        return WriteFileResponse.newBuilder().setBytesWritten(data.size.toLong()).build()
    } finally {
        runCatching { connection.closeHandle(handle.handleId) }
    }
}

internal suspend fun copyFile(request: CopyFileRequest, target: DaemonTarget): CopyFileResponse {
    val from = callerPath(target, request.from, "copy source")
    val to = callerPath(target, request.to, "copy destination")
    var copied = 0L
    Vfs.copyTo(from, to).collect { progress ->
        if (progress.finished) copied += progress.copiedBytes
    }
    return CopyFileResponse.newBuilder().setBytesCopied(copied).build()
}

// Resolve a caller-relative bare path into its provider URI so copy can span
// containers and network locations. VFS URIs are passed through untouched.
internal fun callerPath(target: DaemonTarget, path: String, label: String): String {
    require(path.isNotBlank()) { "$label requires a path" }
    if (isVfsPath(path)) return path
    require(path.startsWith('/')) { "$label must be absolute or a VFS URI" }
    return when (target) {
        is DaemonTarget.Named -> "container://${target.name}$path"
        else -> path
    }
}

internal suspend fun glob(request: GlobRequest, target: DaemonTarget): GlobResponse {
    val base = request.path.ifBlank { "/" }
    if (isVfsPath(base)) return globVfs(request, base)
    val connection = DaemonManager.connection(target)
    require(base.startsWith('/')) { "glob path must be absolute" }
    val basePath = base.trimEnd('/').ifEmpty { "/" }
    val prefix = if (basePath == "/") "/" else "$basePath/"
    val maxResults = if (request.maxResults <= 0) DEFAULT_GLOB_RESULTS
    else request.maxResults.coerceAtMost(MAX_GLOB_RESULTS)
    val matcher = GlobMatcher(request.pattern.ifBlank { "*" })
    val pending = ArrayDeque<String>()
    pending.addLast(basePath)
    val paths = ArrayList<String>()
    var visited = 0
    var truncated = false
    while (pending.isNotEmpty()) {
        if (++visited > MAX_GLOB_VISITS) {
            truncated = true
            break
        }
        val directory = pending.removeLast()
        val entries = try {
            connection.listFiles(directory).getOrThrow()
        } catch (e: Exception) {
            if (directory == basePath) throw e else continue
        }
        for (entry in entries) {
            val relative = if (entry.path.startsWith(prefix)) {
                entry.path.removePrefix(prefix)
            } else {
                entry.path.trimStart('/')
            }
            if (relative.isNotEmpty() && matcher.matches(relative)) {
                paths.add(entry.path)
                if (paths.size >= maxResults) {
                    truncated = true
                    break
                }
            }
            if (entry.isDirectory && !entry.isSymlink && relative.isNotEmpty()) {
                pending.addLast(entry.path)
            }
        }
        if (truncated) break
    }
    paths.sort()
    return GlobResponse.newBuilder().addAllPaths(paths).setTruncated(truncated).build()
}

internal suspend fun globVfs(request: GlobRequest, base: String): GlobResponse {
    val basePath = base.trimEnd('/').ifEmpty { base }
    val prefix = if (basePath.endsWith('/')) basePath else "$basePath/"
    val maxResults = if (request.maxResults <= 0) DEFAULT_GLOB_RESULTS
    else request.maxResults.coerceAtMost(MAX_GLOB_RESULTS)
    val matcher = GlobMatcher(request.pattern.ifBlank { "*" })
    val pending = ArrayDeque<String>()
    pending.addLast(basePath)
    val paths = ArrayList<String>()
    var visited = 0
    var truncated = false
    while (pending.isNotEmpty()) {
        if (++visited > MAX_GLOB_VISITS) {
            truncated = true
            break
        }
        val directory = pending.removeLast()
        val entries = try {
            Vfs.folder(directory).getOrThrow().list().getOrThrow()
        } catch (e: Exception) {
            if (directory == basePath) throw e else continue
        }
        for (entry in entries) {
            val relative = if (entry.path.startsWith(prefix)) {
                entry.path.removePrefix(prefix)
            } else {
                entry.path
            }
            if (relative.isNotEmpty() && matcher.matches(relative)) {
                paths.add(entry.path)
                if (paths.size >= maxResults) {
                    truncated = true
                    break
                }
            }
            if (entry.isDirectory && !entry.isSymlink && relative.isNotEmpty()) {
                pending.addLast(entry.path)
            }
        }
        if (truncated) break
    }
    paths.sort()
    return GlobResponse.newBuilder().addAllPaths(paths).setTruncated(truncated).build()
}

internal fun isVfsPath(path: String): Boolean = path.contains("://")

internal class GlobMatcher(pattern: String) {
    private val regex = Regex(buildString {
        append('^')
        var index = 0
        while (index < pattern.length) {
            when (val c = pattern[index]) {
                '*' -> {
                    if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                        if (index + 2 < pattern.length && pattern[index + 2] == '/') {
                            append("(?:.*/)?")
                            index += 2
                        } else {
                            append(".*")
                            index += 1
                        }
                    } else {
                        append("[^/]*")
                    }
                }
                '?' -> append("[^/]")
                '[' -> {
                    val end = pattern.indexOf(']', index + 1)
                    if (end < 0) {
                        append("\\[")
                    } else {
                        append('[')
                        val start = index + 1
                        val first = if (start < end && (pattern[start] == '!' || pattern[start] == '^')) start + 1 else start
                        if (first > start) append('^')
                        for (i in first until end) {
                            val item = pattern[i]
                            if (item == '\\') append("\\\\") else append(item)
                        }
                        append(']')
                        index = end
                    }
                }
                '\\' -> {
                    if (index + 1 < pattern.length) {
                        append(Regex.escape(pattern[index + 1].toString()))
                        index += 1
                    } else {
                        append("\\\\")
                    }
                }
                '.', '+', '(', ')', '^', '$', '{', '}', '|' -> append('\\').append(c)
                else -> append(c)
            }
            index += 1
        }
        append('$')
    })
    fun matches(path: String): Boolean = regex.matches(path)
}
