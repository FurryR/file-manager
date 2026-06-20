package io.furryr.file.provider

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.furryr.file.copy.CopyProgress
import io.furryr.file.copy.FileSource
import io.furryr.file.copy.OpenMode
import io.furryr.file.copy.StreamFileSource
import io.furryr.file.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.FileNotFoundException

/**
 * FileProvider for SAF document tree locations.
 * Maps `saf://<hexId>/<path>` URIs — the authority `<hexId>` is an 8-char hex ID.
 * `saf:///` lists all registered locations.
 */
object SAFProvider : FileProvider {
    override val scheme: String get() = "saf"
    override val authority: String? get() = null
    override val label: String get() = "SAF Location"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR, ProviderFeature.DELETE,
        ProviderFeature.OPEN,
        ProviderFeature.RENAME, ProviderFeature.STREAM_COPY,
    )

    private lateinit var db: SafDatabase
        private lateinit var appContext: Context

            fun init(context: Context) {
                appContext = context.applicationContext
                db = SafDatabase(appContext)
            }

            fun getDatabase(): SafDatabase = db

            override fun isAvailable(): Boolean = true
            override suspend fun connect(): Result<Unit> = Result.success(Unit)
            override suspend fun disconnect() {}

            override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
                // saf:/// root → list all locations by their hex ID
                if (path == "/") {
                    return@runCatching db.getAll().map { loc ->
                        FileEntry(
                            name = loc.hexId,
                            path = "saf://${loc.hexId}/",
                            isDirectory = true,
                            modifiedAt = 0,
                            size = 0,
                            mode = 0x41ED.toInt(), // drwxrwxr-x
                        )
                    }
                }
                val (loc, relPath) = resolveLocation(path)
                val treeUri = Uri.parse(loc.treeUri)

                // 用 DocumentsContract 查询目录下所有子项，一次 IPC
                val parentDocUri = resolveDocUri(treeUri, relPath)
                ?: throw FileNotFoundException("Not found: saf://${loc.hexId}/$relPath")
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getDocumentId(parentDocUri)
                )

                val entries = mutableListOf<FileEntry>()
                val baseUri = "saf://${loc.hexId}/${relPath.trimEnd('/')}"
                appContext.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    ),
                    null, null, null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: "unknown"
                        val mime = cursor.getString(mimeIdx) ?: ""
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        entries.add(
                            FileEntry(
                                name = name,
                                path = "$baseUri/$name",
                                isDirectory = isDir,
                                modifiedAt = cursor.getLong(modIdx),
                                      size = if (isDir) 0L else cursor.getLong(sizeIdx),
                            )
                        )
                    }
                }
                entries
            }

            override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
                if (path == "/") return@runCatching ProviderStat(exists = true, isDirectory = true)
                    val (loc, relPath) = resolveLocation(path)
                    val treeUri = Uri.parse(loc.treeUri)
                    val docUri = resolveDocUri(treeUri, relPath)
                    if (docUri == null) {
                        ProviderStat(exists = false, isDirectory = false)
                    } else {
                        val mime = queryMimeType(docUri)
                        ProviderStat(exists = true, isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR)
                    }
            }

            override suspend fun createFile(path: String): Result<Unit> = runCatching {
                val (loc, relPath) = resolveLocation(path)
                val name = relPath.substringAfterLast('/')
                val parentPath = relPath.substringBeforeLast('/', "")
                val treeUri = Uri.parse(loc.treeUri)
                val parentDoc = resolveDoc(treeUri, parentPath)
                ?: throw FileNotFoundException("Parent not found: $path")
                parentDoc.createFile("*/*", name) ?: throw IllegalStateException("Failed to create file")
            }

            override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
                val (loc, relPath) = resolveLocation(path)
                val name = relPath.substringAfterLast('/')
                val parentPath = relPath.substringBeforeLast('/', "")
                val treeUri = Uri.parse(loc.treeUri)
                val parentDoc = resolveDoc(treeUri, parentPath)
                ?: throw FileNotFoundException("Parent not found: $path")
                parentDoc.createDirectory(name) ?: throw IllegalStateException("Failed to create dir")
            }

            override suspend fun delete(path: String): Result<Unit> = runCatching {
                if (path == "/") throw IllegalArgumentException("Cannot delete saf:/// root")
                    val (loc, relPath) = resolveLocation(path)
                    val treeUri = Uri.parse(loc.treeUri)
                    val docUri = resolveDocUri(treeUri, relPath)
                    ?: throw FileNotFoundException("Not found: $path")
                    if (!DocumentsContract.deleteDocument(appContext.contentResolver, docUri))
                        throw IllegalStateException("Failed to delete")
            }

            override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
                val (loc, relPath) = resolveLocation(path)
                val treeUri = Uri.parse(loc.treeUri)
                val docUri = resolveDocUri(treeUri, relPath)
                ?: throw FileNotFoundException("Not found: $path")
                val newUri = DocumentsContract.renameDocument(appContext.contentResolver, docUri, newName)
                if (newUri == null) throw IllegalStateException("Failed to rename")
            }

            override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
                if (mode != OpenMode.READ) throw UnsupportedOperationException("SAFProvider supports READ only")
                val (loc, relPath) = resolveLocation(path)
                val treeUri = Uri.parse(loc.treeUri)
                val docUri = resolveDocUri(treeUri, relPath)
                    ?: throw FileNotFoundException("Not found: $path")
                val stream = appContext.contentResolver.openInputStream(docUri)
                    ?: throw FileNotFoundException("Cannot open: $path")
                val size = querySize(docUri)
                StreamFileSource(inputStream = stream, size = size, absolutePath = null)
            }

            override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
                val (fromLoc, fromRelPath) = resolveLocation(from)
                val (toLoc, toRelPath) = resolveLocation(to)
                val srcTreeUri = Uri.parse(fromLoc.treeUri)
                val srcUri = resolveDocUri(srcTreeUri, fromRelPath)
                    ?: throw FileNotFoundException("Not found: $from")
                val dstTreeUri = Uri.parse(toLoc.treeUri)
                val dstParentRelPath = toRelPath.substringBeforeLast('/', "")
                val dstName = toRelPath.substringAfterLast('/')
                val srcSize = querySize(srcUri)

                if (fromLoc.hexId == toLoc.hexId) {
                    val dstParentDocId = resolveDocId(dstTreeUri, dstParentRelPath)
                    val dstParentDocUri = DocumentsContract.buildDocumentUriUsingTree(dstTreeUri, dstParentDocId)
                    val newUri = DocumentsContract.copyDocument(
                        appContext.contentResolver, srcUri, dstParentDocUri
                    )
                    if (newUri == null) throw IllegalStateException("Failed to copy")
                    emit(CopyProgress(srcSize, srcSize, from, true, isCopy))
                } else {
                    val dstParentDoc = resolveDoc(dstTreeUri, dstParentRelPath)
                        ?: throw FileNotFoundException("Destination parent not found: $to")
                    val dstDoc = dstParentDoc.createFile("*/*", dstName)
                        ?: throw IllegalStateException("Failed to create destination")
                    copyWithProgress(srcUri, dstDoc.uri, srcSize, from, isCopy)
                        .collect { emit(it) }
                }
            }.flowOn(Dispatchers.IO)

            override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
                val (loc, relPath) = resolveLocation(destPath)
                val treeUri = Uri.parse(loc.treeUri)
                val parentRelPath = relPath.substringBeforeLast('/', "")
                val name = relPath.substringAfterLast('/')
                val parentDoc = resolveDoc(treeUri, parentRelPath)
                    ?: throw FileNotFoundException("Destination parent not found: $destPath")
                parentDoc.findFile(name)?.delete()
                val dstDoc = parentDoc.createFile("*/*", name)
                    ?: throw IllegalStateException("Failed to create destination")
                val output = appContext.contentResolver.openOutputStream(dstDoc.uri)
                    ?: throw IllegalStateException("Cannot open output stream")
                output.use { outputStream ->
                    val buf = ByteArray(256 * 1024)
                    var offset = 0L
                    var copied = 0L
                    while (true) {
                        val data = src.readChunk(offset, buf.size)
                        if (data.isEmpty()) break
                        outputStream.write(data)
                        offset += data.size
                        copied += data.size
                        emit(CopyProgress(src.size, copied, destPath, copied >= src.size, isCopy))
                        if (copied >= src.size) break
                    }
                    if (copied >= src.size && src.size == 0L) {
                        emit(CopyProgress(0, 0, destPath, true, isCopy))
                    }
                }
            }.flowOn(Dispatchers.IO)

            // ── Internal ───────────────────────────────────────────────────

            private fun resolveDocId(treeUri: Uri, relPath: String): String {
                if (relPath.isEmpty()) return DocumentsContract.getTreeDocumentId(treeUri)
                val segments = relPath.split('/').filter { it.isNotEmpty() }
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                return segments.fold(rootDocId) { acc, seg -> "$acc/$seg" }
            }

            private fun copyWithProgress(
                srcUri: Uri, dstUri: Uri, totalSize: Long, name: String, isCopy: Boolean,
            ): Flow<CopyProgress> = flow {
                val input = appContext.contentResolver.openInputStream(srcUri)
                    ?: throw FileNotFoundException("Cannot open source")
                val output = appContext.contentResolver.openOutputStream(dstUri)
                    ?: throw IllegalStateException("Cannot open output stream")
                input.use { inputStream ->
                    output.use { outputStream ->
                        val buf = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val bytesRead = inputStream.read(buf)
                            if (bytesRead <= 0) break
                            outputStream.write(buf, 0, bytesRead)
                            copied += bytesRead
                            emit(CopyProgress(totalSize, copied, name, copied >= totalSize, isCopy))
                        }
                        if (totalSize == 0L) {
                            emit(CopyProgress(0, 0, name, true, isCopy))
                        }
                    }
                }
            }.flowOn(Dispatchers.IO)

            private data class ResolvedLoc(val loc: SafLocation, val relPath: String)

                private fun resolveLocation(innerPath: String): ResolvedLoc {
                    if (innerPath == "/") throw IllegalArgumentException("saf:/// root has no location")
                        val trimmed = innerPath.trimStart('/')
                        val slashIdx = trimmed.indexOf('/')
                        val hexId = if (slashIdx >= 0) trimmed.substring(0, slashIdx) else trimmed
                        val relPath = if (slashIdx >= 0) trimmed.substring(slashIdx + 1) else ""
                        val loc = db.findByHexId(hexId)
                        ?: throw IllegalStateException("SAF location $hexId not found")
                        return ResolvedLoc(loc, relPath)
                }

                /**
                 * 通过 documentId 拼接直接定位文档 Uri，避免逐层 findFile() 的多次 IPC。
                 * 原理：SAF tree 下，子文档的 documentId = parentDocumentId + "/" + 子名称。
                 */
                private fun resolveDocUri(treeUri: Uri, relPath: String): Uri? {
                    if (relPath.isEmpty()) {
                        // 根目录直接用 treeUri 构建 documentUri
                        return DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri)
                        )
                    }
                    // 获取根 documentId，逐段拼接
                    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                    // 注意：某些 provider 使用不同的 id 格式，这里保守地回退到逐层查询
                    // 先尝试直接拼接
                    val segments = relPath.split('/').filter { it.isNotEmpty() }
                    // 多数 SAF provider 支持这种拼接
                    val docId = segments.fold(rootDocId) { acc, seg -> "$acc/$seg" }
                    val candidate = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    // 验证是否存在（一次快速 query）
                    return if (queryExists(candidate)) candidate else null
                }

                /**
                 * 用于写操作的 DocumentFile 获取（仍需 DocumentFile 对象以调用 createFile/delete 等）。
                 * 但 delete/rename 已改用 DocumentsContract，create 仍需要 DocumentFile。
                 */
                private fun resolveDoc(treeUri: Uri, relPath: String): DocumentFile? {
                    if (relPath.isEmpty()) {
                        return DocumentFile.fromTreeUri(appContext, treeUri)
                    }
                    val name = relPath.substringAfterLast('/')
                    val parentPath = relPath.substringBeforeLast('/', "")
                    val parentDocUri = resolveDocUri(treeUri, parentPath) ?: return null
                    return DocumentFile.fromSingleUri(appContext, parentDocUri)?.findFile(name)
                }

                private fun queryExists(uri: Uri): Boolean {
                    appContext.contentResolver.query(
                        uri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                                                     null, null, null
                    )?.use { cursor ->
                        return cursor.moveToFirst()
                    }
                    return false
                }

                private fun queryMimeType(uri: Uri): String? {
                    appContext.contentResolver.query(
                        uri,
                        arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                                                     null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            return cursor.getString(0)
                        }
                    }
                    return null
                }

                private fun querySize(uri: Uri): Long {
                    appContext.contentResolver.query(
                        uri,
                        arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                                                     null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            return cursor.getLong(0)
                        }
                    }
                    return 0L
                }
}
