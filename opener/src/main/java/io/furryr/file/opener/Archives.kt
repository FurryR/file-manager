package io.furryr.file.opener

import io.furryr.file.core.VFile
import io.furryr.file.core.ViewRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Process-wide archive mounts. A source is keyed by the backing file's
 * [ViewRef], so opening the same zip twice reuses the index, and a zip nested
 * inside another zip gets its own mount.
 */
object Archives {
    private val mutex = Mutex()
    private val sources = mutableMapOf<ViewRef, ArchiveSource>()

    suspend fun open(stagingDir: File, file: VFile): Result<ArchiveFolder> = runCatching {
        val source = mutex.withLock {
            sources[file.ref()] ?: ZipArchiveSource.open(stagingDir, file).getOrThrow().also {
                sources[file.ref()] = it
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
