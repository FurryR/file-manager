package io.furryr.file.render

import android.content.Context
import io.furryr.file.core.ArchiveEntry
import io.furryr.file.core.ArchiveSource
import io.furryr.file.core.VFile
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min

/**
 * Read-only zip backend.
 *
 * A seekable [FileSource] (daemon handle, local fd, SFTP/SSH, AList cache) is
 * indexed in place: only the central directory is read up front and entry
 * bytes are inflated on demand, so opening a 4 GB archive costs a few KB of
 * I/O instead of a full copy into the cache directory. Stream-only backends
 * (SAF, FTP, content URIs) have no random access and are staged once.
 *
 * Entry reads stay sequential, like [io.furryr.file.core.source.StreamFileSource]:
 * consumers open a fresh handle per use.
 */
class ZipArchiveSource private constructor(
    private val source: FileSource,
    private val entries: Map<String, ZipEntryInfo>,
    private val dirs: Set<String>,
    private val children: Map<String, List<String>>,
) : ArchiveSource {

    override val label: String get() = "zip"

    override suspend fun entries(path: List<String>): Result<List<ArchiveEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val parent = path.joinToString("/")
            children[parent].orEmpty().map { name ->
                val full = if (parent.isEmpty()) name else "$parent/$name"
                if (full in dirs) {
                    ArchiveEntry(name = name, isDirectory = true)
                } else {
                    val entry = entries[full] ?: error("missing zip entry: $full")
                    ArchiveEntry(
                        name = name,
                        isDirectory = false,
                        size = entry.size,
                        modifiedAt = entry.modifiedAt,
                    )
                }
            }
        }
    }

    override suspend fun open(path: List<String>, mode: OpenMode): Result<FileSource> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (mode != OpenMode.READ) {
                    throw UnsupportedOperationException("zip entries are read-only")
                }
                openEntry(path.joinToString("/"))
            }
        }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { source.close() }
        Unit
    }

    /** Positional read from the backing archive. Every entry keeps its own cursor. */
    internal fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val end = min(offset + length, source.size)
        if (offset >= end) return ByteArray(0)
        val out = ByteArray((end - offset).toInt())
        var filled = 0
        while (filled < out.size) {
            val chunk = source.readAt(offset + filled, out.size - filled)
            if (chunk.isEmpty()) break
            System.arraycopy(chunk, 0, out, filled, chunk.size)
            filled += chunk.size
        }
        return if (filled == out.size) out else out.copyOf(filled)
    }

    private fun openEntry(full: String): FileSource {
        val info = entries[full] ?: throw java.io.FileNotFoundException("not found in zip: $full")
        if (info.flags and 0x1 != 0) {
            throw UnsupportedOperationException("encrypted zip entries are not supported")
        }
        if (info.method != METHOD_STORED && info.method != METHOD_DEFLATE) {
            throw UnsupportedOperationException("unsupported zip compression method ${info.method}")
        }
        val header = readAt(info.localHeaderOffset, 30)
        if (header.size < 30 || u32(header, 0) != LOCAL_SIG) {
            error("invalid local header for $full")
        }
        val dataOffset = info.localHeaderOffset + 30 + u16(header, 26) + u16(header, 28)
        return ZipEntrySource(
            archive = this,
            dataOffset = dataOffset,
            compressedSize = info.compressedSize,
            size = info.size,
            method = info.method,
        )
    }

    private class ZipEntrySource(
        private val archive: ZipArchiveSource,
        private val dataOffset: Long,
        private val compressedSize: Long,
        override val size: Long,
        private val method: Int,
    ) : FileSource() {
        override val readable get() = true
        override val writable get() = false
        override val seekable get() = false
        override val absolutePath: String? get() = null
        private var closed = false
        private var position = 0L
        private var inflater: Inflater? = null
        private var inflaterStream: InputStream? = null

        override fun readAt(offset: Long, len: Int): ByteArray {
            check(!closed) { "zip entry closed" }
            require(offset == position) { "zip entry source only supports sequential reads" }
            if (len <= 0 || position >= size) return ByteArray(0)
            val want = min(len.toLong(), size - position).toInt()
            if (method == METHOD_STORED) {
                val bytes = archive.readAt(dataOffset + position, want)
                position += bytes.size
                return bytes
            }
            val stream = inflaterStream ?: createInflater().also { inflaterStream = it }
            val buffer = ByteArray(want)
            var total = 0
            while (total < want) {
                val read = stream.read(buffer, total, want - total)
                if (read <= 0) break
                total += read
            }
            position += total
            return if (total == want) buffer else buffer.copyOf(total)
        }

        /** Raw-deflate stream over just this entry's compressed bytes. */
        private fun createInflater(): InputStream {
            var cursor = dataOffset
            var remaining = compressedSize
            val compressed = object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0L) return -1
                    val want = min(len.toLong(), remaining).toInt()
                    val bytes = archive.readAt(cursor, want)
                    if (bytes.isEmpty()) {
                        remaining = 0L
                        return -1
                    }
                    System.arraycopy(bytes, 0, b, off, bytes.size)
                    cursor += bytes.size
                    remaining -= bytes.size
                    return bytes.size
                }
            }
            return InflaterInputStream(compressed, Inflater(true).also { inflater = it }, READ_BUFFER)
        }

        override fun writeAt(offset: Long, data: ByteArray) =
            throw UnsupportedOperationException("zip entries are read-only")

        override fun seek(position: Long) =
            throw UnsupportedOperationException("zip entries are not seekable")

        override fun truncate(newSize: Long) =
            throw UnsupportedOperationException("zip entries are read-only")

        override fun close() {
            if (closed) return
            closed = true
            runCatching { inflaterStream?.close() }
            runCatching { inflater?.end() }
            inflaterStream = null
            inflater = null
        }
    }

    private class ZipEntryInfo(
        val size: Long,
        val compressedSize: Long,
        val method: Int,
        val flags: Int,
        val modifiedAt: Long,
        val localHeaderOffset: Long,
    )

    private class ZipIndex(
        val files: Map<String, ZipEntryInfo>,
        val dirs: Set<String>,
        val children: Map<String, List<String>>,
    )

    companion object Factory {
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATE = 8
        private const val READ_BUFFER = 64 * 1024
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val LOCAL_SIG = 0x04034b50L
        private const val MAX_INDEX_BYTES = 256L * 1024 * 1024

        suspend fun open(context: Context, backing: VFile): Result<ZipArchiveSource> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val opened = backing.open(OpenMode.READ).getOrThrow()
                    val seekable = opened.seekable
                    if (seekable) {
                        try {
                            fromSource(opened)
                        } catch (error: Throwable) {
                            runCatching { opened.close() }
                            throw error
                        }
                    } else {
                        val temp = try {
                            stage(context, opened)
                        } finally {
                            runCatching { opened.close() }
                        }
                        val staged = SeekableCacheFileSource(temp, OpenMode.READ)
                        try {
                            fromSource(staged)
                        } catch (error: Throwable) {
                            runCatching { staged.close() }
                            throw error
                        }
                    }
                }
            }

        /** Copy a stream-only backend once; [SeekableCacheFileSource] deletes it on close. */
        private fun stage(context: Context, source: FileSource): File {
            val temp = File.createTempFile("archive-", ".zip", context.cacheDir)
            try {
                FileOutputStream(temp).use { output ->
                    var offset = 0L
                    while (true) {
                        val chunk = source.readChunk(offset, 256 * 1024)
                        if (chunk.isEmpty()) break
                        output.write(chunk)
                        offset += chunk.size
                    }
                }
                return temp
            } catch (error: Throwable) {
                runCatching { temp.delete() }
                throw error
            }
        }

        private fun fromSource(source: FileSource): ZipArchiveSource {
            val index = readIndex(source)
            return ZipArchiveSource(source, index.files, index.dirs, index.children)
        }

        private fun readIndex(source: FileSource): ZipIndex {
            val size = source.size
            require(size >= 22) { "not a zip archive: file is too small" }
            val eocdOffset = findEndOfCentralDirectory(source, size)
            val eocd = readRange(source, eocdOffset, 22)
            var cdSize = u32(eocd, 12)
            var cdOffset = u32(eocd, 16)
            if (u16(eocd, 10) == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                val locatorOffset = eocdOffset - 20
                check(locatorOffset >= 0) { "invalid zip64 archive" }
                val locator = readRange(source, locatorOffset, 20)
                check(u32(locator, 0) == ZIP64_LOCATOR_SIG) { "invalid zip64 locator" }
                val recordOffset = u64(locator, 8)
                val record = readRange(source, recordOffset, 56)
                check(u32(record, 0) == ZIP64_EOCD_SIG) { "invalid zip64 record" }
                cdSize = u64(record, 40)
                cdOffset = u64(record, 48)
            }
            check(cdSize in 0..MAX_INDEX_BYTES) { "zip index is too large" }
            val directory = readRange(source, cdOffset, cdSize.toInt())

            val files = linkedMapOf<String, ZipEntryInfo>()
            val explicitDirs = linkedSetOf<String>()
            // Reuse one Calendar: creating it per entry dominates the index
            // time on archives with tens of thousands of members.
            val calendar = java.util.Calendar.getInstance()
            var pos = 0
            while (pos + 46 <= directory.size && u32(directory, pos) == CENTRAL_SIG) {
                val flags = u16(directory, pos + 8)
                val method = u16(directory, pos + 10)
                val time = u16(directory, pos + 12)
                val date = u16(directory, pos + 14)
                var compressedSize = u32(directory, pos + 20)
                var uncompressedSize = u32(directory, pos + 24)
                val nameLength = u16(directory, pos + 28)
                val extraLength = u16(directory, pos + 30)
                val commentLength = u16(directory, pos + 32)
                var localHeaderOffset = u32(directory, pos + 42)
                val nameStart = pos + 46
                val extraStart = nameStart + nameLength
                val next = extraStart + extraLength + commentLength
                if (extraStart + extraLength > directory.size || next > directory.size) break
                val rawName = String(directory, nameStart, nameLength, Charsets.UTF_8)

                if (uncompressedSize == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL ||
                    localHeaderOffset == 0xFFFFFFFFL
                ) {
                    var extra = extraStart
                    val extraEnd = extraStart + extraLength
                    while (extra + 4 <= extraEnd) {
                        val fieldId = u16(directory, extra)
                        val fieldLength = u16(directory, extra + 2)
                        if (extra + 4 + fieldLength > extraEnd) break
                        if (fieldId == 0x0001) {
                            var cursor = extra + 4
                            if (uncompressedSize == 0xFFFFFFFFL && cursor + 8 <= extra + 4 + fieldLength) {
                                uncompressedSize = u64(directory, cursor)
                                cursor += 8
                            }
                            if (compressedSize == 0xFFFFFFFFL && cursor + 8 <= extra + 4 + fieldLength) {
                                compressedSize = u64(directory, cursor)
                                cursor += 8
                            }
                            if (localHeaderOffset == 0xFFFFFFFFL && cursor + 8 <= extra + 4 + fieldLength) {
                                localHeaderOffset = u64(directory, cursor)
                            }
                            break
                        }
                        extra += 4 + fieldLength
                    }
                }

                pos = next
                val segments = sanitize(rawName) ?: continue
                val full = segments.joinToString("/")
                val isDirectory = rawName.endsWith('/') || rawName.endsWith('\\')
                if (isDirectory) {
                    explicitDirs += full
                } else {
                    files[full] = ZipEntryInfo(
                        size = uncompressedSize,
                        compressedSize = compressedSize,
                        method = method,
                        flags = flags,
                        modifiedAt = dosTime(calendar, date, time),
                        localHeaderOffset = localHeaderOffset,
                    )
                }
            }

            // Zips rarely contain explicit directory entries; derive them.
            val dirs = sortedSetOf<String>()
            dirs += explicitDirs
            for (file in files.keys) {
                var parent = file.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    dirs += parent
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            val children = linkedMapOf<String, LinkedHashSet<String>>()
            fun add(parent: String, name: String) {
                children.getOrPut(parent) { linkedSetOf() } += name
            }
            for (dir in dirs) {
                add(dir.substringBeforeLast('/', ""), dir.substringAfterLast('/'))
            }
            for (file in files.keys) {
                add(file.substringBeforeLast('/', ""), file.substringAfterLast('/'))
            }
            val sortedChildren = children.mapValues { (_, names) ->
                names.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            }
            return ZipIndex(files, dirs, sortedChildren)
        }

        private fun findEndOfCentralDirectory(source: FileSource, size: Long): Long {
            val window = min(size, 22L + 0xFFFF + 20).toInt()
            val start = size - window
            val tail = readRange(source, start, window)
            // Prefer an EOCD whose comment ends exactly at EOF; fall back to one
            // followed by trailing bytes (some writers append junk).
            for (strict in booleanArrayOf(true, false)) {
                for (index in tail.size - 22 downTo 0) {
                    if (u32(tail, index) != EOCD_SIG) continue
                    val commentLength = u16(tail, index + 20)
                    val end = index + 22 + commentLength
                    if (if (strict) end == tail.size else end <= tail.size) return start + index
                }
            }
            error("not a zip archive: end of central directory not found")
        }

        private fun readRange(source: FileSource, offset: Long, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val out = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val want = min(length - filled, 1024 * 1024)
                val chunk = source.readAt(offset + filled, want)
                if (chunk.isEmpty()) break
                System.arraycopy(chunk, 0, out, filled, chunk.size)
                filled += chunk.size
            }
            return if (filled == length) out else out.copyOf(filled)
        }

        private fun sanitize(name: String): List<String>? {
            val normalized = name.replace('\\', '/').trim('/')
            if (normalized.isEmpty()) return null
            val segments = normalized.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) return null
            return segments
        }

        private fun dosTime(calendar: java.util.Calendar, date: Int, time: Int): Long {
            val year = ((date shr 9) and 0x7F) + 1980
            val month = ((date shr 5) and 0x0F).coerceIn(1, 12)
            val day = (date and 0x1F).coerceIn(1, 31)
            val hour = (time shr 11) and 0x1F
            val minute = (time shr 5) and 0x3F
            val second = (time and 0x1F) * 2
            return runCatching {
                calendar.clear()
                calendar.set(year, month - 1, day, hour, minute, second)
                calendar.timeInMillis
            }.getOrDefault(0L)
        }

        private fun u16(bytes: ByteArray, offset: Int): Int {
            if (offset + 2 > bytes.size) return 0
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(bytes: ByteArray, offset: Int): Long {
            if (offset + 4 > bytes.size) return 0L
            return (u16(bytes, offset).toLong()) or (u16(bytes, offset + 2).toLong() shl 16)
        }

        private fun u64(bytes: ByteArray, offset: Int): Long {
            if (offset + 8 > bytes.size) return 0L
            return u32(bytes, offset) or (u32(bytes, offset + 4) shl 32)
        }
    }
}
