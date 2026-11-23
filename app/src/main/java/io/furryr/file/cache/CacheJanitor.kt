package io.furryr.file.cache

import android.content.Context
import io.furryr.file.container.ContainerManager
import java.io.File

/**
 * One sweep of cache leftovers.
 *
 * On-demand reads stage a temporary file in `cacheDir` (FTP/SFTP/SAF/AList/
 * SSH/content URIs/archives) and every container keeps its own `/tmp`, `/run`
 * and proot directories under `cacheDir/container/<id>`. Normal operation
 * removes both when the resource closes; a killed process, a deleted or a
 * renamed container leaves them behind, and this sweep reclaims them.
 */
object CacheJanitor {
    /** A staging file untouched for this long cannot belong to a live read. */
    private const val STAGE_TTL_MS = 24L * 60 * 60 * 1000

    private val STAGE_PREFIXES = listOf("sftp-", "ftp-", "saf-", "remote-", "content-", "archive-")
    private val STAGE_SUFFIXES = listOf(".stage", ".zip")

    /**
     * Sweeps [Context.getCacheDir] only, so it is safe on a background thread
     * at process start. Live runtime sockets (`pulse`, `wayland`),
     * settings-referenced copies (`fonts`) and the rebuildable-but-expensive
     * adblock engine are not cache leftovers and stay untouched.
     */
    fun sweep(context: Context) {
        val cache = context.cacheDir
        sweepStages(cache)
        sweepContainers(context, File(cache, "container"))
    }

    private fun sweepStages(cache: File) {
        val cutoff = System.currentTimeMillis() - STAGE_TTL_MS
        cache.listFiles()?.forEach { file ->
            if (!file.isFile || file.lastModified() >= cutoff) return@forEach
            val name = file.name
            if (STAGE_PREFIXES.none { name.startsWith(it) }) return@forEach
            if (STAGE_SUFFIXES.none { name.endsWith(it) }) return@forEach
            runCatching { file.delete() }
        }
    }

    private fun sweepContainers(context: Context, root: File) {
        val entries = root.listFiles() ?: return
        // A read failure keeps every runtime dir: deleting a live one would
        // break a running container.
        val live = ContainerManager.listContainers(context).getOrNull()
            ?.mapTo(mutableSetOf()) { ContainerManager.runtimeId(it.name) }
            ?: return
        entries.forEach { entry ->
            // The shared resolver and login profile are files in this directory.
            if (!entry.isDirectory || entry.name in live) return@forEach
            runCatching { entry.deleteRecursively() }
        }
    }
}
