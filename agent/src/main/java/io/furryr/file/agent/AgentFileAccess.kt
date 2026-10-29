package io.furryr.file.agent

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
private const val GLOB_BUDGET_MS = 10_000L
private const val MAX_DIRECTORY_ENTRIES = 2_000

// Kernel pseudo filesystems: crawling them is meaningless for file globs and
// can keep a `**` walk running for minutes.
private val GLOB_SKIP_DIRS = setOf("/proc", "/sys", "/dev")

public suspend fun readFile(request: ReadFileRequest, target: DaemonTarget): ReadFileResponse {
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

public suspend fun readVfsFile(request: ReadFileRequest): ReadFileResponse {
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

public suspend fun listDirectory(
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

public suspend fun writeFile(request: WriteFileRequest, target: DaemonTarget): WriteFileResponse {
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

public suspend fun copyFile(request: CopyFileRequest, target: DaemonTarget): CopyFileResponse {
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
public fun callerPath(target: DaemonTarget, path: String, label: String): String {
    require(path.isNotBlank()) { "$label requires a path" }
    if (isVfsPath(path)) return path
    require(path.startsWith('/')) { "$label must be absolute or a VFS URI" }
    return when (target) {
        is DaemonTarget.Named -> "container://${target.name}$path"
        else -> path
    }
}

public suspend fun glob(request: GlobRequest, target: DaemonTarget): GlobResponse {
    val base = request.path.ifBlank { "/" }
    // Inside the caller's own scope the scope prefix is omitted: rewrite the
    // base to a plain path and walk the caller's daemon, so results are plain
    // paths like /src/Main.kt instead of container://name/src/Main.kt.
    ownScopePath(base, target)?.let { return globDaemon(request, it, target) }
    if (isVfsPath(base)) return globVfs(request, base)
    return globDaemon(request, base, target)
}

private suspend fun globDaemon(
    request: GlobRequest,
    base: String,
    target: DaemonTarget,
): GlobResponse {
    val connection = DaemonManager.connection(target)
    require(base.startsWith('/')) { "glob path must be absolute" }
    val basePath = base.trimEnd('/').ifEmpty { "/" }
    val prefix = if (basePath == "/") "/" else "$basePath/"
    return walkGlob(
        request,
        basePath,
        list = { directory ->
            try {
                connection.listFiles(directory).getOrThrow()
            } catch (e: Exception) {
                if (directory == basePath) throw e
                emptyList()
            }
        },
        toRelative = { path ->
            if (path.startsWith(prefix)) path.removePrefix(prefix) else path.trimStart('/')
        },
    )
}

public suspend fun globVfs(request: GlobRequest, base: String): GlobResponse {
    val basePath = base.trimEnd('/').ifEmpty { base }
    val prefix = if (basePath.endsWith('/')) basePath else "$basePath/"
    return walkGlob(
        request,
        basePath,
        list = { directory ->
            try {
                Vfs.folder(directory).getOrThrow().list().getOrThrow()
            } catch (e: Exception) {
                if (directory == basePath) throw e
                emptyList()
            }
        },
        toRelative = { path ->
            if (path.startsWith(prefix)) path.removePrefix(prefix) else path
        },
    )
}

/**
 * Depth-first glob walk with hard bounds: directories that cannot lead to a
 * match are pruned, kernel pseudo filesystems are skipped, and the walk stops
 * early once the result cap, visit cap or time budget is reached — a `**`
 * pattern over a huge tree must return (truncated) instead of stalling the
 * connection.
 */
private suspend fun walkGlob(
    request: GlobRequest,
    basePath: String,
    list: suspend (String) -> List<io.furryr.file.core.model.FileEntry>,
    toRelative: (String) -> String,
): GlobResponse {
    val maxResults = if (request.maxResults <= 0) DEFAULT_GLOB_RESULTS
    else request.maxResults.coerceAtMost(MAX_GLOB_RESULTS)
    val matcher = GlobMatcher(request.pattern.ifBlank { "*" })
    // Pending directories as absolute path plus path segments relative to the
    // base, so descent can be pruned without re-splitting.
    val pending = ArrayDeque<Pair<String, List<String>>>()
    pending.addLast(basePath to emptyList())
    val paths = ArrayList<String>()
    var visited = 0
    var truncated = false
    val deadline = System.nanoTime() + GLOB_BUDGET_MS * 1_000_000
    while (pending.isNotEmpty()) {
        if (++visited > MAX_GLOB_VISITS || System.nanoTime() > deadline) {
            truncated = true
            break
        }
        val (directory, segments) = pending.removeLast()
        val entries = list(directory)
        for (entry in entries) {
            val relative = toRelative(entry.path)
            if (relative.isNotEmpty() && matcher.matches(relative)) {
                paths.add(entry.path)
                if (paths.size >= maxResults) {
                    truncated = true
                    break
                }
            }
            val entrySegments = segments + entry.name
            if (entry.isDirectory && !entry.isSymlink && relative.isNotEmpty() &&
                entry.path !in GLOB_SKIP_DIRS && matcher.canDescend(entrySegments)
            ) {
                pending.addLast(entry.path to entrySegments)
            }
        }
        if (truncated) break
    }
    paths.sort()
    return GlobResponse.newBuilder().addAllPaths(paths).setTruncated(truncated).build()
}

public fun isVfsPath(path: String): Boolean = path.contains("://")

// The VFS root of the caller's own scope, resolved from the Hello handshake.
public fun ownScopeRoot(target: DaemonTarget): String? = when (target) {
    DaemonTarget.Host -> "file://"
    is DaemonTarget.Named -> "container://${target.name}"
    DaemonTarget.App -> null
}

/** `container://test/src` becomes `/src` for a caller inside container `test`. */
public fun ownScopePath(path: String, target: DaemonTarget): String? {
    val root = ownScopeRoot(target) ?: return null
    if (path == root) return "/"
    val prefix = "$root/"
    if (!path.startsWith(prefix)) return null
    return path.removePrefix(root)
}

public class GlobMatcher(pattern: String) {
    // Leading pattern segments without wildcards, e.g. `src/main/**` ->
    // ["src", "main"]. Directories off this track can never match.
    private val literalPrefix: List<String>

    // Number of path segments of a match when the pattern has no `**`;
    // directories at or below this depth cannot contain matches.
    private val fixedDepth: Int?

    init {
        val segments = pattern.split('/')
        literalPrefix = segments.takeWhile { segment ->
            segment.none { it == '*' || it == '?' || it == '[' || it == '\\' }
        }
        fixedDepth = if (pattern.contains("**")) null else segments.size
    }

    /** Whether a directory with these base-relative [segments] can still hold matches. */
    fun canDescend(segments: List<String>): Boolean {
        if (fixedDepth != null && segments.size >= fixedDepth) return false
        if (segments.size <= literalPrefix.size) {
            for (index in segments.indices) {
                if (segments[index] != literalPrefix[index]) return false
            }
        }
        return true
    }

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
