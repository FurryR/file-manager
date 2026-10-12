package io.furryr.file.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import io.furryr.file.FileRepository
import io.furryr.file.ForwardProviderAuthority
import io.furryr.file.core.source.FdFileSource
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.util.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * ContentProvider that serves files the app itself cannot hand to the platform
 * [androidx.core.content.FileProvider] — root-only locations, other volumes and
 * provider-backed paths (container/SAF/…).
 *
 * When the backend can hand over a real kernel descriptor (local/container
 * daemons do), it is passed through as a `dup`'d, seekable fd (zero-copy).
 * Backends without one (SAF/FTP/SFTP, virtual handles) are streamed through a
 * pipe instead. URIs are built with [buildUri] and carry the source path.
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
        val path = pathOf(uri) ?: throw FileNotFoundException("Missing path in $uri")
        if (!mode.contains('r')) {
            throw FileNotFoundException("ForwardFileProvider is read-only: $path")
        }
        val source = try {
            runBlocking { openSource(path) }
        } catch (e: Exception) {
            throw FileNotFoundException("Cannot open $path: ${e.message}")
        }

        if (source is FdFileSource) {
            // Zero-copy: hand the caller the daemon's real, seekable descriptor.
            try {
                return ParcelFileDescriptor.dup(source.fileDescriptor)
            } finally {
                runCatching { source.close() }
            }
        }
        // Virtual/streaming backend without a kernel fd: pipe the bytes.
        return stream(source)
    }

    override fun getType(uri: Uri): String? = pathOf(uri)?.let(::mimeTypeOf)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val path = pathOf(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(Path.parse(path).name ?: path)
                OpenableColumns.SIZE -> row.add(sizeOf(path))
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
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("ForwardFileProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("ForwardFileProvider is read-only")

    // ── Internals ───────────────────────────────────────────────────

    /**
     * Stream [source] to the caller through a reliable pipe: return the read
     * end right away and pump from a worker. Only used for backends that have
     * no kernel fd to hand over (SAF/FTP/SFTP, virtual handles).
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

    private suspend fun openSource(path: String): FileSource {
        val resolved = FileRepository.resolve(path).getOrThrow()
        return resolved.provider.open(resolved.innerPath, OpenMode.READ).getOrThrow()
    }

    private fun pump(source: FileSource, writeEnd: ParcelFileDescriptor) {
        val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
        try {
            var offset = 0L
            while (true) {
                val data = source.readChunk(offset, COPY_BUFFER_SIZE)
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

    private fun sizeOf(path: String): Long = runCatching {
        runBlocking { openSource(path).use { it.size } }
    }.getOrDefault(-1L)

    companion object {
        private const val TAG = "ForwardFileProvider"
        private const val QUERY_PATH = "path"
        private const val COPY_BUFFER_SIZE = 256 * 1024

        /** Build a shareable `content://` URI for an arbitrary source path. */
        fun buildUri(path: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(ForwardProviderAuthority)
            .appendPath("file")
            .appendQueryParameter(QUERY_PATH, path)
            .build()

        private fun pathOf(uri: Uri): String? = uri.getQueryParameter(QUERY_PATH)

        private fun mimeTypeOf(path: String): String {
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        }
    }
}
