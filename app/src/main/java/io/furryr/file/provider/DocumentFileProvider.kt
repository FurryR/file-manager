package io.furryr.file.provider

import android.content.Context
import android.net.Uri
import android.util.Log
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
 * FileProvider backed by Android's Storage Access Framework (SAF).
 *
 * Maps `document://<hex-id>/<path>` URIs to content URIs obtained via
 * `ACTION_OPEN_DOCUMENT_TREE`. The hex ID (8 hex chars) is a shorthand
 * for a persisted tree URI stored in SharedPreferences.
 *
 * ## Usage
 * ```kotlin
 * // Register after user picks a directory
 * val treeUri = intent.data  // from ACTION_OPEN_DOCUMENT_TREE
 * val id = DocumentFileProvider.registerTree(context, treeUri)
 * // → "a1b2c3d4"
 *
 * // Then access files via:
 * // document://a1b2c3d4/Download/file.txt
 * ```
 */
object DocumentFileProvider : FileProvider {
    private const val TAG = "DocumentFileProvider"
    private const val PREFS_NAME = "document_trees"
    private const val SCHEME = "document"

    private lateinit var appContext: Context

    override val scheme: String get() = SCHEME
    override val authority: String? get() = null
    override val label: String get() = "Document Provider"
    override val features: Set<ProviderFeature> get() = setOf(
        ProviderFeature.LIST, ProviderFeature.STAT,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR,
        ProviderFeature.DELETE, ProviderFeature.OPEN,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Register a tree URI obtained from [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE].
     * Persists the mapping and returns the hex ID.
     */
    fun registerTree(treeUri: Uri): String {
        val id = generateId()
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(id, treeUri.toString()).apply()
        Log.i(TAG, "Registered document tree $id → $treeUri")
        return id
    }

    /**
     * List all registered document tree IDs with their URIs.
     */
    fun listTrees(): Map<String, String> {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.mapValues { it.value.toString() }
    }

    /**
     * Remove a registered tree by ID.
     */
    fun unregisterTree(id: String) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(id).apply()
    }

    private fun generateId(): String {
        val chars = "0123456789abcdef"
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        while (true) {
            val id = (1..8).map { chars.random() }.joinToString("")
            if (!prefs.contains(id)) return id
        }
    }

    // ── FileProvider implementation ─────────────────────────────────

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val docFile = resolve(path) ?: throw FileNotFoundException("Not found: $path")
        val children = docFile.listFiles()
        val baseUri = "${scheme}://${path.trimStart('/')}"
        children.map { child ->
            FileEntry(
                name = child.name ?: "unknown",
                path = "$baseUri/${child.name ?: "unknown"}",
                isDirectory = child.isDirectory,
                modifiedAt = child.lastModified(),
                size = child.length(),
            )
        }.toList()
    }

    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val docFile = resolve(path)
        if (docFile == null) {
            ProviderStat(exists = false)
        } else {
            ProviderStat(
                exists = true,
                isDirectory = docFile.isDirectory,
            )
        }
    }

    override suspend fun createFile(path: String): Result<Unit> = runCatching {
        val parent = resolveParent(path)
        val name = path.substringAfterLast('/')
        val created = parent.createFile("*/*", name)
            ?: throw IllegalStateException("Failed to create file: $path")
        Unit
    }

    override suspend fun createDirectory(path: String): Result<Unit> = runCatching {
        val parent = resolveParent(path)
        val name = path.substringAfterLast('/')
        val created = parent.createDirectory(name)
            ?: throw IllegalStateException("Failed to create directory: $path")
        Unit
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val docFile = resolve(path) ?: throw FileNotFoundException("Not found: $path")
        if (!docFile.delete()) {
            throw IllegalStateException("Failed to delete: $path")
        }
    }

    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
        val docFile = resolve(path) ?: throw FileNotFoundException("Not found: $path")
        if (!docFile.renameTo(newName)) {
            throw IllegalStateException("Failed to rename: $path → $newName")
        }
    }

    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        val docFile = resolve(path) ?: throw FileNotFoundException("Not found: $path")
        val uri = docFile.uri
        val size = docFile.length()
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open: $path")
        StreamFileSource(inputStream = stream, size = size, absolutePath = path)
    }

    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> =
        flow<CopyProgress> { throw UnsupportedOperationException("SAF copy not implemented") }.flowOn(Dispatchers.IO)

    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> =
        flow<CopyProgress> { throw UnsupportedOperationException("SAF copy not implemented") }.flowOn(Dispatchers.IO)

    override fun isAvailable(): Boolean = true

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() {}

    // ── Internal ───────────────────────────────────────────────────

    /**
     * Resolve a `document://<id>/<path>` to a [DocumentFile].
     */
    private fun resolve(uriPath: String): DocumentFile? {
        val (treeUri, relativePath) = parseDocumentPath(uriPath)
        val treeDoc = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        if (relativePath.isEmpty() || relativePath == "/") return treeDoc
        return treeDoc.findFile(relativePath.trimStart('/'))
    }

    private fun resolveParent(uriPath: String): DocumentFile {
        val parentPath = uriPath.substringBeforeLast('/')
        val parent = resolve(parentPath) ?: resolve("/")!!
        return parent
    }

    private fun parseDocumentPath(uriPath: String): Pair<Uri, String> {
        // uriPath is like "/a1b2c3d4/Download/file.txt" or just "/a1b2c3d4"
        val parts = uriPath.trimStart('/').split('/', limit = 2)
        val id = parts[0]
        val relativePath = if (parts.size > 1) "/${parts[1]}" else "/"

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val treeUriStr = prefs.getString(id, null)
            ?: throw IllegalArgumentException("Unknown document tree ID: $id")
        return Uri.parse(treeUriStr) to relativePath
    }

}


