package io.furryr.file.forward

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import io.furryr.file.ForwardProviderAuthority
import io.furryr.file.DefaultNodeService
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.Path
import io.furryr.file.core.RemoteFile
import io.furryr.file.core.VFile
import io.furryr.file.provider.web.withReferer
import io.furryr.file.core.Vfs
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.daemon.source.FdFileSource
import io.furryr.file.installFileProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * Facts captured before a forward URI is handed out.
 *
 * `query`/`getType` answer from these instead of probing the backend: the
 * provider runs on a Binder thread, and a remote probe there can block the
 * receiving app for its whole timeout budget.
 */
class ForwardFacts(
    val name: String? = null,
    val size: Long = -1L,
    val mime: String? = null,
) {
    /** `OpenableColumns.SIZE` must stay null when the size is unknown, never -1. */
    fun sizeOrNull(): Long? = size.takeIf { it >= 0L }
}

/**
 * ContentProvider that serves files the app itself cannot hand to the platform
 * [androidx.core.content.FileProvider] — root-only locations, other volumes and
 * provider-backed paths (container/SAF/…). Remote HTTP(S) URLs take the same
 * route: a live file node's handle is a [FileSource] like any other, so a URL
 * becomes a `content://` file instead of a browser intent. Archive entries and
 * container files are addressed by their `Path` text, re-resolved through
 * [DefaultNodeService], so opening a zip member never fabricates a VFS path.
 *
 * Every resource is one [FileSource] handle, and the descriptor follows the
 * handle's capabilities:
 *  - a backend with a kernel fd (local/container daemons) passes a `dup`'d,
 *    seekable descriptor straight through (zero-copy);
 *  - a seekable handle without an fd (range-capable HTTP, SFTP) is exposed
 *    through a [StorageManager] proxy descriptor, so the reader can seek;
 *  - a stream-only handle (SAF/FTP, non-range HTTP) is pumped through a pipe.
 * URIs are built with [buildUri] and carry the source path or URL. The display
 * name is also appended as the last path segment: share sheets that never call
 * [query] label a shared file after `getLastPathSegment()`, and the segment
 * used to be the constant "file".
 */
class ForwardFileProvider : ContentProvider() {

    private val writers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        // A provider may be created before the activity (e.g. a previously
        // granted URI is reopened), so wire everything up here too.
        installFileProviders(ctx)
        return true
    }

    // ── ContentProvider ─────────────────────────────────────────────

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = targetOf(uri)?.target ?: throw FileNotFoundException("Invalid or expired URI: $uri")
        if (!mode.contains('r')) {
            throw FileNotFoundException("ForwardFileProvider is read-only: ${target.label()}")
        }
        // The provider runs on a Binder thread that carries the caller's
        // StrictMode policy, so any network probe or daemon round-trip must
        // run on a worker instead.
        val source = try {
            runBlocking(Dispatchers.IO) { openSource(target) }
        } catch (e: Exception) {
            throw FileNotFoundException("Cannot open ${target.label()}: ${e.message}")
        }

        if (source is FdFileSource) {
            // Zero-copy: hand the caller the daemon's real, seekable descriptor.
            try {
                return ParcelFileDescriptor.dup(source.fileDescriptor)
            } finally {
                runCatching { source.close() }
            }
        }
        if (source.seekable && source.size > 0L) {
            // A seekable handle without a kernel fd still behaves like a file:
            // expose it through a proxy descriptor instead of a one-way pipe.
            runCatching { proxy(source) }.onSuccess { return it }
        }
        // Virtual/streaming backend without random access: pipe the bytes.
        return stream(source)
    }

    override fun getType(uri: Uri): String? {
        val resolved = targetOf(uri) ?: return null
        val facts = cachedFacts(resolved.token)
        return when (val target = resolved.target) {
            is Target.Path -> facts?.mime
                ?: ContentTypes.fromName(facts?.name ?: target.displayName())
                ?: mimeTypeOf(target.path)
            is Target.Node -> facts?.mime
                ?: ContentTypes.fromName(facts?.name ?: target.displayName())
                ?: "*/*"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val resolved = targetOf(uri) ?: return null
        val target = resolved.target
        val facts = cachedFacts(resolved.token)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(facts?.name ?: target.displayName())
                OpenableColumns.SIZE -> row.add(facts?.sizeOrNull())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("ForwardFileProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException("ForwardFileProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("ForwardFileProvider is read-only")

    // ── Internals ───────────────────────────────────────────────────

    private sealed interface Target {
        /** A provider path or a URL, kept as text so the referer can ride along. */
        data class Path(val path: String, val referer: String?) : Target

        /** Any address, including `!` view levels; resolved through the node service. */
        data class Node(val path: io.furryr.file.core.Path) : Target
    }

    /**
     * Expose a seekable [source] as a file descriptor. Reads arrive on a
     * private thread, because a proxy read blocks on the network and must not
     * run on the main looper.
     *
     * FUSE treats a short read as end-of-file, so [ProxyFileDescriptorCallback.onRead]
     * must fill the requested buffer completely unless the source is at EOF.
     * Errors travel as [ErrnoException]s: anything else is reported to the
     * kernel as `EBADF`, which receivers treat as a dead file handle rather
     * than a transient I/O failure.
     */
    private fun proxy(source: FileSource): ParcelFileDescriptor {
        val storage = context?.getSystemService(StorageManager::class.java)
            ?: throw IOException("StorageManager unavailable")
        val thread = HandlerThread("forward-file-fd").apply { start() }
        var released = false
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = try {
                source.size
            } catch (error: Exception) {
                throw ErrnoException("onGetSize", OsConstants.EIO)
            }

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (released) throw ErrnoException("onRead", OsConstants.EBADF)
                if (size <= 0) return 0
                try {
                    var total = 0
                    while (total < size) {
                        val bytes = source.readAt(offset + total, size - total)
                        if (bytes.isEmpty()) break
                        val take = minOf(bytes.size, size - total)
                        System.arraycopy(bytes, 0, data, total, take)
                        total += take
                    }
                    // An empty read before the advertised end is a backend
                    // failure, not EOF; never let it truncate the file.
                    if (total == 0 && offset < source.size) {
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }
                    return total
                } catch (error: ErrnoException) {
                    throw error
                } catch (error: Exception) {
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                if (released) return
                released = true
                runCatching { source.close() }
                thread.quitSafely()
            }
        }
        return try {
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                Handler(thread.looper),
            )
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    /**
     * Stream [source] to the caller through a reliable pipe: return the read
     * end right away and pump from a worker. Only used when the handle has no
     * way to serve positional reads.
     */
    private fun stream(source: FileSource): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        writers.launch {
            try {
                source.use { pump(it, writeEnd) }
            } catch (e: Exception) {
                Log.d(TAG, "forward stream failed for ${source.absolutePath}: ${e.message}")
                runCatching { writeEnd.closeWithError(e.message ?: "read failed") }
            }
        }
        return readEnd
    }

    private suspend fun openSource(target: Target): FileSource = when (target) {
        // One provider lookup whatever the address is. The signed referer only
        // changes anything for a live file, which is a capability of the node.
        is Target.Path -> {
            val node = Vfs.node(target.path).getOrThrow()
            val file = node as? VFile ?: throw FileNotFoundException("not a file: ${target.path}")
            file.withReferer(target.referer).open(OpenMode.READ).getOrThrow()
        }
        is Target.Node -> {
            val node = DefaultNodeService().resolve(target.path).getOrThrow()
            val file = node as? VFile ?: throw FileNotFoundException("not a file: ${target.path}")
            file.open(OpenMode.READ).getOrThrow()
        }
    }

    private fun Target.label(): String = when (this) {
        is Target.Path -> path
        is Target.Node -> path.toString()
    }

    private fun Target.displayName(): String = when (this) {
        is Target.Path -> Path.parse(path).name ?: path
        is Target.Node -> path.name ?: path.toString()
    }

    private fun pump(source: FileSource, writeEnd: ParcelFileDescriptor) {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
        try {
            var offset = 0L
            while (true) {
                // `readChunk` clamps to `size`, which is 0 for handles whose
                // length is unknown (special files); readAt lets the handle
                // decide where EOF is.
                val data = source.readAt(offset, COPY_BUFFER_SIZE)
                if (data.isEmpty()) break
                out.write(data)
                offset += data.size
            }
            out.flush()
        } catch (e: IOException) {
            // The reader closed its end (probe / one-shot open). Expected.
            Log.d(TAG, "forward reader closed for ${source.absolutePath}: ${e.message}")
        } finally {
            runCatching { out.close() }
        }
    }

    private fun mimeTypeOf(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    companion object {
        private const val TAG = "ForwardFileProvider"
        private const val QUERY_PATH = "path"
        private const val QUERY_REF = "ref"
        private const val QUERY_REFERER = "referer"
        private const val QUERY_EXPIRES = "expires"
        private const val QUERY_TOKEN = "token"
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val FACT_CACHE_ENTRIES = 128

        /**
         * Metadata captured when a URI is built, keyed by its share token.
         * Entries disappear when the token expires or the cache evicts them,
         * so `query`/`getType` never need to open the backend.
         */
        private class CachedFacts(val facts: ForwardFacts, val expires: Long)

        private val factCache = object : LinkedHashMap<String, CachedFacts>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedFacts>?): Boolean =
                size > FACT_CACHE_ENTRIES
        }

        private fun rememberFacts(token: String, expires: Long, facts: ForwardFacts?) {
            if (facts == null) return
            synchronized(factCache) {
                val now = System.currentTimeMillis()
                factCache.entries.removeAll { it.value.expires < now }
                factCache[token] = CachedFacts(facts, expires)
            }
        }

        private fun cachedFacts(token: String): ForwardFacts? = synchronized(factCache) {
            val cached = factCache[token] ?: return null
            if (cached.expires < System.currentTimeMillis()) {
                factCache.remove(token)
                return null
            }
            cached.facts
        }

        /**
         * Build a short-lived, token-authorized `content://` URI.
         *
         * [referer] only applies to HTTP(S) paths: range requests must carry
         * the page that linked the file, or hotlink-protected hosts reject
         * them. It is part of the signed payload, like the path itself.
         *
         * [facts] answers `query`/`getType` without touching the backend; it
         * is held by token and dropped with the token.
         */
        fun buildUri(
            path: String,
            referer: String? = null,
            facts: ForwardFacts? = null,
        ): Uri {
            val (expires, token) = ShareToken.sign(payload(path, referer))
            rememberFacts(token, expires, facts)
            return Uri.Builder()
                .scheme("content")
                .authority(ForwardProviderAuthority)
                .appendPath("file")
                // The display name is a path segment, not only a queried fact:
                // some ROMs never call query() and label a shared file after
                // `getLastPathSegment()`, which would otherwise be "file".
                .apply {
                    displaySegment(
                        facts?.name ?: runCatching { Path.parse(path).name }.getOrNull(),
                    )
                }
                .appendQueryParameter(QUERY_PATH, path)
                .apply {
                    if (!referer.isNullOrBlank()) appendQueryParameter(QUERY_REFERER, referer)
                }
                .appendQueryParameter(QUERY_EXPIRES, expires.toString())
                .appendQueryParameter(QUERY_TOKEN, token)
                .build()
        }

        /**
         * Build a URI for a node.
         *
         * A live file is addressed by its own URL and carries the page it came
         * from; any other node is addressed by its `Path` text, so an archive
         * entry or a container file behind `!` re-resolves through the opener
         * route instead of being flattened into a provider path.
         */
        fun buildUri(file: VFile, facts: ForwardFacts? = null): Uri {
            val remote = file as? RemoteFile
            return if (remote != null) {
                buildUri(remote.address, remote.referer, facts)
            } else {
                buildUri(file.path, facts)
            }
        }

        /**
         * Build a URI for a node address that is not a plain provider path — an
         * archive entry, a container file, anything behind `!`. The signed
         * payload is the address text itself, so the provider re-resolves the
         * same node through the opener route; nothing has to be encoded twice.
         */
        fun buildUri(path: io.furryr.file.core.Path, facts: ForwardFacts? = null): Uri {
            val encoded = path.toString()
            val (expires, token) = ShareToken.sign(refPayload(encoded))
            rememberFacts(token, expires, facts)
            return Uri.Builder()
                .scheme("content")
                .authority(ForwardProviderAuthority)
                .appendPath("file")
                .apply { displaySegment(facts?.name ?: path.name) }
                .appendQueryParameter(QUERY_REF, encoded)
                .appendQueryParameter(QUERY_EXPIRES, expires.toString())
                .appendQueryParameter(QUERY_TOKEN, token)
                .build()
        }

        /** Last path segment, for receivers that never ask for the columns. */
        private fun android.net.Uri.Builder.displaySegment(name: String?) {
            val segment = name
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                ?: return
            appendPath(segment)
        }

        private class ResolvedTarget(val target: Target, val token: String)

        private fun targetOf(uri: Uri): ResolvedTarget? {
            val expires = uri.getQueryParameter(QUERY_EXPIRES)?.toLongOrNull() ?: return null
            val token = uri.getQueryParameter(QUERY_TOKEN) ?: return null
            val encoded = uri.getQueryParameter(QUERY_REF)
            if (encoded != null) {
                if (!ShareToken.verify(refPayload(encoded), expires, token)) return null
                val path = runCatching { io.furryr.file.core.Path.parse(encoded) }.getOrNull()
                    ?: return null
                return ResolvedTarget(Target.Node(path), token)
            }
            val path = uri.getQueryParameter(QUERY_PATH) ?: return null
            val referer = uri.getQueryParameter(QUERY_REFERER)
            if (!ShareToken.verify(payload(path, referer), expires, token)) return null
            return ResolvedTarget(Target.Path(path, referer), token)
        }

        private fun payload(path: String, referer: String?): String =
            if (referer.isNullOrBlank()) path else "$path\u0000$referer"

        private fun refPayload(encoded: String): String = "ref\u0000$encoded"

    }
}
