package io.furryr.file.picker

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import io.furryr.file.AppRuntime
import io.furryr.file.FileWorlds
import io.furryr.file.R
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.VNode
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.daemon.source.FdFileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * The document provider behind picker results.
 *
 * A picker hands the caller a `content://` document or tree URI whose id names
 * a world, a VFS address and a read/write access mode. Ids are HMAC-signed with
 * a persisted key ([PickerTokens]), so they survive the activity and a process
 * restart; the system's URI grant remains the revocable capability.
 *
 * It is the DocumentsProvider contract, not a re-shaped forward provider:
 * callers may list a tree, create, rename and delete through
 * `DocumentsContract`, and `openDocument("w")` stages a write that commits when
 * the caller closes the descriptor.
 */
class PickerDocumentsProvider : DocumentsProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        // A provider may be the first component in the process; install the
        // process services and the persistent id key before any lookup.
        io.furryr.file.ensureProcessServices(ctx)
        runCatching { PickerTokens.install(File(ctx.filesDir, "picker-token.bin")) }
        return true
    }

    // ── Roots ───────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        val rootId = PickerTokens.issue(PickerDoc("app", "rw", "/"))
        for (column in columns) {
            when (column) {
                DocumentsContract.Root.COLUMN_ROOT_ID -> row.add("vfs")
                Document.COLUMN_DOCUMENT_ID -> row.add(rootId)
                DocumentsContract.Root.COLUMN_TITLE -> row.add(context?.getString(R.string.app_name).orEmpty())
                Document.COLUMN_FLAGS -> row.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
                Document.COLUMN_ICON -> row.add(R.mipmap.ic_launcher)
                DocumentsContract.Root.COLUMN_MIME_TYPES -> row.add("*/*")
                DocumentsContract.Root.COLUMN_SUMMARY -> row.add(context?.getString(R.string.picker_roots).orEmpty())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor =
        emptyCursor(projection)

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor = emptyCursor(projection)

    // ── Documents ───────────────────────────────────────────────────

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val doc = decode(documentId)
        val node = resolve(doc)
        return documentCursor(doc, node, projection)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val doc = decode(parentDocumentId)
        val folder = runBlocking(Dispatchers.IO) {
            world(doc).vfs.folder(Path.parse(doc.path)).getOrThrow()
        }
        val entries = runBlocking(Dispatchers.IO) { folder.listWithNodes().getOrThrow() }
        val columns = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(columns, entries.second.size)
        val ordered = entries.second.sortedWith(
            compareBy({ it !is VFolder }, { it.name.lowercase() }),
        )
        for (node in ordered) {
            val childDoc = PickerDoc(doc.worldId, doc.access, node.path.toString())
            fillRow(cursor.newRow(), columns, childDoc, node)
        }
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        val doc = decode(documentId)
        val node = resolve(doc)
        return facts(doc, node).mime
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = PickerTokens.verify(parentDocumentId) ?: return false
        val child = PickerTokens.verify(documentId) ?: return false
        if (parent.worldId != child.worldId) return false
        if (parent.path == "/") return child.path.startsWith("/") && child.path != "/"
        return child.path.startsWith("${parent.path}/")
    }

    // ── Mutation ────────────────────────────────────────────────────

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = requireWritable(parentDocumentId)
        val name = sanitize(displayName)
        val node = runBlocking(Dispatchers.IO) {
            val folder = world(parent).vfs.folder(Path.parse(parent.path)).getOrThrow()
            // `destination` reuses an existing child for copy targets. A
            // document provider must not silently return the old file when a
            // caller asked to create a new one; DocumentsUI has already
            // proposed a free name when it wanted one.
            if (folder.child(name).getOrNull() != null) {
                throw java.nio.file.FileAlreadyExistsException(name)
            }
            if (mimeType == Document.MIME_TYPE_DIR) {
                folder.mkdir(name).getOrThrow()
            } else {
                folder.destination(name).getOrThrow()
            }
        }
        val child = PickerDoc(parent.worldId, "rw", node.path.toString())
        return PickerTokens.issue(child)
    }

    override fun deleteDocument(documentId: String) {
        val doc = requireWritable(documentId)
        runBlocking(Dispatchers.IO) { resolve(doc).delete().getOrThrow() }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val doc = requireWritable(documentId)
        val name = sanitize(displayName)
        val renamed = runBlocking(Dispatchers.IO) { resolve(doc).rename(name).getOrThrow() }
        val next = PickerDoc(doc.worldId, doc.access, renamed.path.toString())
        return PickerTokens.issue(next)
    }

    // ── Bytes ───────────────────────────────────────────────────────

    override fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
        val doc = decode(documentId)
        val wantsWrite = mode.contains('w') || mode.contains('+')
        if (wantsWrite && !doc.writable) {
            throw FileNotFoundException("Document is read-only: ${doc.path}")
        }
        val openMode = when {
            wantsWrite && (mode.contains('r') || mode.contains('+')) -> OpenMode.READ_WRITE
            wantsWrite -> OpenMode.WRITE
            else -> OpenMode.READ
        }
        val truncate = mode.contains('t') || openMode == OpenMode.WRITE
        val file = runBlocking(Dispatchers.IO) {
            (world(doc).vfs.node(Path.parse(doc.path)).getOrThrow() as? VFile)
                ?: throw FileNotFoundException("Not a file: ${doc.path}")
        }
        if (wantsWrite && !file.writable) {
            throw FileNotFoundException("Document is read-only: ${doc.path}")
        }
        val source = runBlocking(Dispatchers.IO) { file.open(openMode).getOrThrow() }
        return descriptors(source, openMode, truncate)
    }

    // ── Internals ───────────────────────────────────────────────────

    private data class Facts(
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
        val flags: Int,
    )

    private fun world(doc: PickerDoc) =
        FileWorlds.find(doc.worldId)
            ?: error("Unknown document world: ${doc.worldId}")

    private fun resolve(doc: PickerDoc): VNode = runBlocking(Dispatchers.IO) {
        world(doc).vfs.node(Path.parse(doc.path)).getOrThrow()
    }

    private fun decode(documentId: String): PickerDoc =
        PickerTokens.verify(documentId)
            ?: throw FileNotFoundException("Invalid document id")

    private fun requireWritable(documentId: String): PickerDoc {
        val doc = decode(documentId)
        if (!doc.writable) throw SecurityException("Document is read-only")
        return doc
    }

    private fun sanitize(name: String): String {
        val value = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (value.isEmpty() || value == "." || value == "..") {
            throw IllegalArgumentException("Invalid display name")
        }
        return value
    }

    private fun facts(doc: PickerDoc, node: VNode): Facts = when (node) {
        is VFolder -> {
            val caps = runBlocking(Dispatchers.IO) { node.capabilities() }
            var flags = 0
            if (doc.writable && node.parent != null) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            }
            if (doc.writable && (caps.createFile != null || caps.createDirectory != null)) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
            Facts(
                name = node.name.ifBlank { node.path.name ?: doc.path },
                mime = Document.MIME_TYPE_DIR,
                size = 0L,
                modified = 0L,
                flags = flags,
            )
        }
        is VFile -> {
            val stat = runBlocking(Dispatchers.IO) { node.stat().getOrThrow() }
            var flags = 0
            if (doc.writable && node.writable) flags = flags or Document.FLAG_SUPPORTS_WRITE
            if (doc.writable && node.parent != null) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            }
            Facts(
                name = node.name.ifBlank { node.path.name ?: doc.path },
                mime = stat.mime
                    ?: ContentTypes.fromName(node.name)
                    ?: "application/octet-stream",
                size = stat.size,
                modified = stat.modifiedAt,
                flags = flags,
            )
        }
        else -> throw FileNotFoundException("Unsupported node: ${doc.path}")
    }

    private fun documentCursor(
        doc: PickerDoc,
        node: VNode,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: DEFAULT_PROJECTION
        val cursor = MatrixCursor(columns, 1)
        fillRow(cursor.newRow(), columns, doc, node)
        return cursor
    }

    private fun fillRow(
        row: MatrixCursor.RowBuilder,
        columns: Array<out String>,
        doc: PickerDoc,
        node: VNode,
    ) {
        val facts = facts(doc, node)
        for (column in columns) {
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> row.add(PickerTokens.issue(doc))
                Document.COLUMN_DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> row.add(facts.name)
                Document.COLUMN_MIME_TYPE -> row.add(facts.mime)
                Document.COLUMN_SIZE, OpenableColumns.SIZE -> row.add(facts.size)
                Document.COLUMN_LAST_MODIFIED -> row.add(facts.modified)
                Document.COLUMN_FLAGS -> row.add(facts.flags)
                DocumentsContract.Root.COLUMN_SUMMARY -> row.add(null)
                Document.COLUMN_ICON -> row.add(null)
                else -> row.add(null)
            }
        }
    }

    private fun emptyCursor(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_PROJECTION, 0)

    private fun descriptors(
        source: FileSource,
        openMode: OpenMode,
        truncate: Boolean,
    ): ParcelFileDescriptor {
        val readable = openMode != OpenMode.WRITE
        val writable = openMode != OpenMode.READ
        if (writable && truncate) runCatching { source.truncate(0) }
        if (source is FdFileSource) {
            try {
                return ParcelFileDescriptor.dup(source.fileDescriptor)
            } finally {
                runCatching { source.close() }
            }
        }
        if (source.seekable) {
            return try {
                proxy(source, readable, writable, truncate)
            } catch (error: Throwable) {
                runCatching { source.close() }
                throw error
            }
        }
        return if (writable) pipeWrite(source) else pipeRead(source)
    }

    /**
     * Seekable source as a random-access descriptor. Writes go straight to
     * [source]; [ProxyFileDescriptorCallback.onRelease] closes it, which is the
     * commit point for staged remote/SAF destinations.
     */
    private fun proxy(
        source: FileSource,
        readable: Boolean,
        writable: Boolean,
        truncate: Boolean,
    ): ParcelFileDescriptor {
        val storage = context?.getSystemService(StorageManager::class.java)
            ?: throw java.io.IOException("StorageManager unavailable")
        val thread = HandlerThread("picker-fd").apply { start() }
        var released = false
        var writtenEnd = -1L
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = runCatching { source.size }.getOrDefault(0L)

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (released) throw ErrnoException("onRead", OsConstants.EBADF)
                if (!readable || size <= 0) return 0
                try {
                    var total = 0
                    while (total < size) {
                        val bytes = source.readAt(offset + total, size - total)
                        if (bytes.isEmpty()) break
                        val take = minOf(bytes.size, size - total)
                        System.arraycopy(bytes, 0, data, total, take)
                        total += take
                    }
                    if (total == 0 && source.size > 0L && offset < source.size) {
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }
                    return total
                } catch (error: ErrnoException) {
                    throw error
                } catch (error: Exception) {
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
            }

            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                if (released) throw ErrnoException("onWrite", OsConstants.EBADF)
                if (!writable || size <= 0) return 0
                try {
                    source.writeAt(offset, data.copyOf(size))
                    if (offset + size > writtenEnd) writtenEnd = offset + size
                    return size
                } catch (error: Exception) {
                    throw ErrnoException("onWrite", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                if (released) return
                released = true
                if (writable && truncate) {
                    runCatching { source.truncate(if (writtenEnd >= 0L) writtenEnd else 0L) }
                }
                runCatching { source.close() }
                thread.quitSafely()
            }
        }
        val mode = when {
            readable && writable -> ParcelFileDescriptor.MODE_READ_WRITE
            writable -> ParcelFileDescriptor.MODE_WRITE_ONLY
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return try {
            storage.openProxyFileDescriptor(mode, callback, Handler(thread.looper))
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    private fun pipeRead(source: FileSource): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        scope.launch {
            try {
                source.use { stream ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        var offset = 0L
                        while (true) {
                            val data = stream.readAt(offset, COPY_BUFFER)
                            if (data.isEmpty()) break
                            out.write(data)
                            offset += data.size
                        }
                        out.flush()
                    }
                }
            } catch (error: Exception) {
                Log.d(TAG, "picker read failed: ${error.message}")
                runCatching { writeEnd.closeWithError(error.message ?: "read failed") }
            }
        }
        return readEnd
    }

    private fun pipeWrite(source: FileSource): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        scope.launch {
            var offset = 0L
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (size == 0) continue
                        source.writeAt(offset, buffer.copyOf(size))
                        offset += size
                    }
                }
                runCatching { source.truncate(offset) }
            } catch (error: Exception) {
                Log.d(TAG, "picker write failed: ${error.message}")
            } finally {
                runCatching { source.close() }
            }
        }
        return writeEnd
    }

    private companion object {
        const val TAG = "PickerDocuments"
        const val COPY_BUFFER = 256 * 1024

        val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_SUMMARY,
        )
    }
}
