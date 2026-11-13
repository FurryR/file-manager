package io.furryr.file.opener

import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Process-wide archive mounts. A source is keyed by the backing file's [Path]
 * plus its size and mtime, so opening the same zip twice reuses the index while
 * replacing the zip invalidates it. The cache is bounded: the least recently
 * used mount is closed when a fifth archive is opened.
 */
object Archives {
    private data class Key(val path: Path, val size: Long, val modifiedAt: Long)

    private const val MAX_MOUNTS = 4

    private val mutex = Mutex()
    private val sources = LinkedHashMap<Key, ArchiveSource>()

    suspend fun open(stagingDir: File, file: VFile): Result<ArchiveFolder> = runCatchingCancellable {
        val stat = file.stat().getOrNull()
        val key = Key(file.path, stat?.size ?: -1L, stat?.modifiedAt ?: -1L)
        val result: Pair<ArchiveSource, List<ArchiveSource>> = mutex.withLock {
            val cached = sources.remove(key)?.also { sources[key] = it }
            if (cached != null) {
                cached to emptyList<ArchiveSource>()
            } else {
                val opened = ZipArchiveSource.open(stagingDir, file).getOrThrow()
                sources[key] = opened
                val stale = mutableListOf<ArchiveSource>()
                while (sources.size > MAX_MOUNTS) {
                    val oldest = sources.keys.first()
                    sources.remove(oldest)?.let(stale::add)
                }
                opened to stale
            }
        }
        val (source, evicted) = result
        for (stale in evicted) {
            try {
                stale.close()
            } catch (_: Throwable) {
                // A failed close must not keep the new mount from being used.
            }
        }
        ArchiveFolder(backing = file, source = source)
    }

    suspend fun closeAll() {
        val snapshot = mutex.withLock {
            val copy = sources.values.toList()
            sources.clear()
            copy
        }
        for (source in snapshot) {
            try {
                source.close()
            } catch (_: Throwable) {
                // A failed close must not prevent the other mounts from closing.
            }
        }
    }
}
