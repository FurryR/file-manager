package io.furryr.file.open

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.furryr.file.core.ContentTypes
import io.furryr.file.core.MimeType
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.core.VFolder
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import io.furryr.file.core.source.StreamFileSource
import java.io.File
import java.util.Locale

/** One system-delivered open: the addresses a tapped file or link asked for. */
data class OpenRequest(val id: Int, val addresses: List<String>)

/**
 * System intents that ask this app to show something.
 *
 * The `content://` spelling is platform wiring, never a VFS protocol: the
 * address arrives from Android, the app wraps it as a [VFile] here, and every
 * layer above only sees a node like any other. Nothing else in the app reads
 * this scheme.
 */
object IncomingOpen {
    private const val CONTENT_SCHEME = "content"

    private val OPEN_ACTIONS = setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

    /**
     * Addresses carried by [intent]: a storage path stays a path, a link keeps
     * its URL, a provider document keeps its URI. Anything else is not a file
     * this app can show, and is dropped.
     */
    @Suppress("DEPRECATION")
    fun addresses(intent: Intent): List<String> {
        if (intent.action !in OPEN_ACTIONS) return emptyList()
        val uris = buildList {
            intent.data?.let(::add)
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let(::add)
            }
            when (intent.action) {
                Intent.ACTION_SEND ->
                    (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
        }
        return uris.distinct().mapNotNull(::address)
    }

    /**
     * The document node for a system-delivered `content://` address, or null
     * when [address] is an ordinary address the VFS already routes.
     */
    fun document(context: Context, address: String): VFile? {
        val uri = runCatching { Uri.parse(address) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != CONTENT_SCHEME) return null
        return ContentDocument(context.applicationContext, uri)
    }

    private fun address(uri: Uri): String? = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> uri.path
        "http", "https" -> uri.toString()
        CONTENT_SCHEME -> uri.toString()
        else -> null
    }
}

private val PROJECTION = arrayOf(
    OpenableColumns.DISPLAY_NAME,
    OpenableColumns.SIZE,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)

private class Facts(
    val name: String,
    val size: Long,
    val modified: Long,
    val mime: MimeType?,
    val isDirectory: Boolean,
    val writable: Boolean,
)

private fun android.database.Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun android.database.Cursor.long(column: String): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0L else getLong(index)
}

private fun readFacts(context: Context, uri: Uri): Facts {
    var name: String? = null
    var size = 0L
    var modified = 0L
    var mime: MimeType? = null
    val resolver = context.contentResolver
    runCatching {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            name = cursor.string(OpenableColumns.DISPLAY_NAME)
            size = cursor.long(OpenableColumns.SIZE)
            modified = cursor.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            mime = cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE)
        }
    }
    if (mime == null) mime = runCatching { resolver.getType(uri) }.getOrNull()
    return Facts(
        // A provider that answers no row is still a file: the tap itself is the
        // grant, so the bytes can be opened without knowing its name.
        name = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: uri.toString(),
        size = size.coerceAtLeast(0L),
        modified = modified,
        mime = mime?.takeIf { it != DocumentsContract.Document.MIME_TYPE_DIR },
        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
        writable = context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED,
    )
}

/**
 * One `content://` document as a node.
 *
 * The bytes belong to another app, so this is a read-through wrapper over the
 * resolver the intent already granted: [stat] answers the facts the provider
 * published and [open] streams the document. A write grant (the sender passed
 * one) stages into the cache and uploads on close, the same contract every
 * other writable backend follows.
 */
private class ContentDocument(
    private val context: Context,
    private val uri: Uri,
) : VFile {
    private val facts = readFacts(context, uri)

    override val name: String get() = facts.name

    override val parent: VFolder? get() = null

    override val path: Path = Path.parse(uri.toString())

    override val writable: Boolean get() = facts.writable

    override suspend fun stat(): Result<ProviderStat> = Result.success(
        ProviderStat(
            exists = true,
            isDirectory = facts.isDirectory,
            size = facts.size,
            modifiedAt = facts.modified,
            mime = facts.mime ?: ContentTypes.fromName(facts.name),
        )
    )

    override suspend fun open(mode: OpenMode): Result<FileSource> = runCatching {
        require(!facts.isDirectory) { "$uri is a directory" }
        val resolver = context.contentResolver
        if (mode == OpenMode.READ) {
            val stream = resolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open: $uri")
            return@runCatching StreamFileSource(
                inputStream = stream,
                size = facts.size,
                absolutePath = uri.toString(),
            )
        }
        require(facts.writable) { "$uri is read-only" }
        val staged = File.createTempFile("content-", ".stage", context.cacheDir)
        try {
            if (mode == OpenMode.READ_WRITE) {
                val input = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open: $uri")
                input.use { source -> staged.outputStream().use { source.copyTo(it) } }
            }
            SeekableCacheFileSource(
                staged,
                mode,
                uri.toString(),
                commit = { file ->
                    val output = resolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Cannot write: $uri")
                    output.use { sink -> file.inputStream().use { it.copyTo(sink) } }
                },
            )
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
    }
}
