package io.furryr.file.archive

import android.content.Context
import io.furryr.file.FileRepository
import io.furryr.file.core.model.CopyProgress
import io.furryr.file.core.model.FileEntry
import io.furryr.file.core.model.ProviderStat
import io.furryr.file.core.source.FileSource
import io.furryr.file.core.source.OpenMode
import io.furryr.file.core.source.SeekableCacheFileSource
import io.furryr.file.core.source.StreamFileSource
import io.furryr.file.provider.DirectoryCapabilities
import io.furryr.file.provider.FileProvider
import io.furryr.file.provider.ProviderFeature
import io.furryr.file.util.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ExtraDataRecord
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ArchivePasswordRequired : Exception("This archive requires a password")

/** ZIP is editable; TAR/compressed TAR and 7z expose safe, read-only virtual trees. */
object ArchiveProvider : FileProvider {
    override val scheme = "archive"
    override val authority: String? = null
    override val label = "Archive"
    override val features = setOf(ProviderFeature.LIST, ProviderFeature.STAT, ProviderFeature.OPEN,
        ProviderFeature.CREATE_FILE, ProviderFeature.CREATE_DIR, ProviderFeature.DELETE,
        ProviderFeature.RENAME, ProviderFeature.STREAM_COPY)
    private lateinit var context: Context
    private val sessions = ConcurrentHashMap<String, Session>()
    // Different archive:// mounts of one source must serialize the digest
    // check and upload together, rather than only locking their local caches.
    private val originLocks = ConcurrentHashMap<String, Any>()
    private val suffixes = listOf(".zip", ".apk", ".jar", ".7z", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz")
    fun supports(name: String) = suffixes.any { name.lowercase().endsWith(it) }
    fun install(context: Context) { this.context = context.applicationContext; FileRepository.register(scheme, this) }

    fun mount(origin: String, password: String? = null): String {
        val id = UUID.randomUUID().toString()
        val local = File(context.cacheDir, "archives/$id/archive").apply { parentFile!!.mkdirs() }
        download(origin, local)
        val type = when { origin.lowercase().endsWith(".7z") -> "7z"
            listOf(".zip", ".apk", ".jar").any { origin.lowercase().endsWith(it) } -> "zip"
            else -> "tar" }
        val session = Session(id, origin, local, type, password?.toCharArray(), hash(local))
        if (type == "zip") zip(session).use { archive ->
            if (archive.isEncrypted && password == null) throw ArchivePasswordRequired()
            // A mixed archive may start with an unencrypted file. Validate an
            // encrypted member all the way to EOF so MAC/CRC verification also
            // rejects passwords that happen to pass a short header check.
            archive.fileHeaders.firstOrNull { !it.isDirectory && it.isEncrypted }?.let { header ->
                archive.getInputStream(header).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (input.read(buffer) >= 0) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Archive password check cancelled")
                    }
                }
            }
        }
        entries(session) // Validate before publishing a navigable URI.
        sessions[id] = session
        context.getSharedPreferences("archive_sessions", Context.MODE_PRIVATE).edit()
            .putString("$id.origin", origin).putString("$id.type", type).apply()
        return "archive://$id/"
    }

    fun sourceOf(uri: String): String = resolve(Path.parse(uri).toInnerPath()).first.origin
    override fun isAvailable() = true
    override suspend fun connect() = Result.success(Unit)
    override suspend fun disconnect() { sessions.values.forEach { it.password?.fill('\u0000') }; sessions.clear() }
    override suspend fun directoryCapabilities(path: String) = DirectoryCapabilities(canCreate = resolve(path).first.type == "zip")

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val (session, parent) = resolve(path)
        synchronized(session) {
            val all = entries(session)
            val prefix = parent.takeIf { it.isNotEmpty() }?.plus("/").orEmpty()
            val children = linkedMapOf<String, Node>()
            all.filter { it.name.startsWith(prefix) && it.name != parent }.forEach { node ->
                val rest = node.name.removePrefix(prefix)
                val leaf = rest.substringBefore('/')
                val child = if ('/' in rest) Node(prefix + leaf, true, 0, 0) else node
                if (children[leaf]?.directory != true) children[leaf] = child
            }
            children.map { (name, node) -> FileEntry(name = name, path = "archive://${session.id}/${node.name}",
                isDirectory = node.directory, size = node.size, modifiedAt = node.modified) }
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
    override suspend fun stat(path: String): Result<ProviderStat> = runCatching {
        val (session, name) = resolve(path)
        if (name.isEmpty()) ProviderStat(exists = true, isDirectory = true)
        else synchronized(session) {
            val all = entries(session)
            val node = all.lastOrNull { it.name == name }
            val directory = node?.directory == true || all.any { it.name.startsWith("$name/") }
            ProviderStat(exists = node != null || all.any { it.name.startsWith("$name/") },
                isDirectory = directory,
                totalBytes = if (directory) 0 else node?.size ?: 0)
        }
    }
    override suspend fun createFile(path: String) = put(path, ByteArrayInputStream(byteArrayOf()))
    override suspend fun createDirectory(path: String) = put(path.trimEnd('/') + "/", ByteArrayInputStream(byteArrayOf()), true)
    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val (session, name) = resolve(path); require(name.isNotEmpty()) { "Cannot delete an archive root" }
        mutate(session) { archive ->
            val matches = archive.fileHeaders.filter { header ->
                safeName(header.fileName)?.let { it == name || it.startsWith("$name/") } == true
            }
            require(matches.isNotEmpty()) { "Archive entry no longer exists" }
            archive.removeFiles(matches.map { it.fileName }.distinct())
        }
    }
    override suspend fun rename(path: String, newName: String): Result<Unit> = runCatching {
        require(newName.isNotEmpty() && '/' !in newName && safeName(newName) == newName) { "Invalid entry name" }
        val (session, old) = resolve(path); require(old.isNotEmpty())
        val new = old.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" }
        mutate(session) { archive ->
            require(archive.fileHeaders.none { header ->
                safeName(header.fileName)?.let { it == new || it.startsWith("$new/") } == true
            }) { "Destination exists" }
            val replacements = archive.fileHeaders.mapNotNull { header ->
                val canonical = safeName(header.fileName) ?: return@mapNotNull null
                if (canonical != old && !canonical.startsWith("$old/")) return@mapNotNull null
                header.fileName to (new + canonical.removePrefix(old) + if (header.isDirectory) "/" else "")
            }.toMap()
            require(replacements.isNotEmpty()) { "Archive entry no longer exists" }
            archive.renameFiles(replacements)
        }
    }
    override suspend fun prepareReplacement(original: String, replacement: String): Result<Unit> = runCatching {
        val (session, sourceName) = resolve(original)
        val (targetSession, targetName) = resolve(replacement)
        require(session === targetSession && sourceName != targetName) { "Replacement must be another entry in the same archive session" }
        mutate(session) { archive ->
            val source = archive.fileHeaders.lastOrNull { safeName(it.fileName) == sourceName }
                ?: throw FileNotFoundException(sourceName)
            val target = archive.fileHeaders.lastOrNull { safeName(it.fileName) == targetName }
                ?: throw FileNotFoundException(targetName)
            require(!source.isDirectory && !target.isDirectory && !zipSymlink(source) && !zipSymlink(target)) {
                "Replacing directory or symbolic-link archive entries is not supported"
            }
            target.versionMadeBy = source.versionMadeBy
            target.externalFileAttributes = source.externalFileAttributes?.clone() ?: ByteArray(4)
            target.fileComment = source.fileComment
            // Info-ZIP's 0x7875 record carries only UID/GID. Never copy unknown
            // extras wholesale: they can describe old CRC/size/encryption data.
            val uidRecords = source.extraDataRecords.orEmpty().filter { it.header == 0x7875L }.map { record ->
                ExtraDataRecord().apply { header = record.header; sizeOfData = record.sizeOfData; data = record.data?.clone() }
            }
            target.extraDataRecords = target.extraDataRecords.orEmpty().filterNot { it.header == 0x7875L } + uidRecords
            // zip4j 2.11.5 exposes a live central-directory model; setComment
            // rewrites it. Creator, external attributes, UID/GID and comment
            // are copied; zip4j itself clears internal attributes. CRC, sizes,
            // encryption/ZIP64 extras and the new modification time stay intact.
            archive.setComment(archive.comment.orEmpty())
        }
    }
    override suspend fun open(path: String, mode: OpenMode): Result<FileSource> = runCatching {
        val (session, name) = resolve(path); require(name.isNotEmpty())
        val local = File.createTempFile("entry-", ".bin", session.file.parentFile)
        try {
            if (mode != OpenMode.WRITE) synchronized(session) { extractOne(session, name, local) }
            if (mode == OpenMode.READ) SeekableCacheFileSource(local, mode, "archive://${session.id}/$name")
            else {
                require(session.type == "zip") { "This archive format is read-only; extract it to edit" }
                SeekableCacheFileSource(local, mode, "archive://${session.id}/$name") { modified ->
                    modified.inputStream().use { put(path, it).getOrThrow() }
                }
            }
        } catch (error: Throwable) { local.delete(); throw error }
    }
    override fun copy(from: String, to: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        open(from, OpenMode.READ).getOrThrow().use { source -> copy(source, to, isCopy).collect { emit(it) } }
    }.flowOn(Dispatchers.IO)
    override fun copy(src: FileSource, destPath: String, isCopy: Boolean): Flow<CopyProgress> = flow {
        val temp = File.createTempFile("upload-", ".bin", File(context.cacheDir, "archives").apply { mkdirs() })
        try {
            var offset = 0L
            temp.outputStream().use { output -> while (true) {
                currentCoroutineContext().ensureActive()
                val bytes = src.readChunk(offset, 256 * 1024); if (bytes.isEmpty()) break
                output.write(bytes); offset += bytes.size
                emit(CopyProgress(src.size, offset, destPath, false, isCopy))
            } }
            temp.inputStream().use { put(destPath, it).getOrThrow() }
            emit(CopyProgress(src.size, offset, destPath, true, isCopy))
        } finally { temp.delete() }
    }.flowOn(Dispatchers.IO)

    fun createZip(paths: List<String>, destination: String, password: String? = null) {
        require(paths.isNotEmpty()); require(!FileRepository.stat(destination).getOrThrow().exists) { "Destination exists" }
        val local = File.createTempFile("create-", ".zip", context.cacheDir).apply { delete() }
        try {
            ZipFile(local, password?.toCharArray()).use { archive ->
                val names = mutableSetOf<String>()
                paths.forEach { uri ->
                    val name = Path.parse(uri).name ?: error("Cannot archive a filesystem root")
                    require(names.add(name)) { "Duplicate top-level name: $name" }
                    addTree(archive, uri, name, password != null)
                }
            }
            FileRepository.writeStream(destination, local.inputStream(), local.length()).getOrThrow()
        } finally { local.delete() }
    }

    private fun addTree(zip: ZipFile, uri: String, name: String, encrypted: Boolean) {
        require(safeName(name) == name.trimEnd('/'))
        val resolved = FileRepository.resolve(uri).getOrThrow()
        val stat = runBlocking { resolved.provider.stat(resolved.innerPath).getOrThrow() }
        if (stat.isDirectory) {
            val entries = FileRepository.list(uri).getOrThrow()
            if (entries.isEmpty()) zip.addStream(ByteArrayInputStream(byteArrayOf()), parameters("$name/", false))
            entries.forEach { require(!it.isSymlink) { "Extract or resolve symbolic links before creating a ZIP: ${it.name}" }; addTree(zip, it.path, "$name/${it.name}", encrypted) }
        } else runBlocking { resolved.provider.open(resolved.innerPath, OpenMode.READ).getOrThrow() }.use { source ->
            zip.addStream(SourceInput(source), parameters(name, encrypted))
        }
    }
    private fun put(path: String, input: InputStream, directory: Boolean = false): Result<Unit> = runCatching {
        val (session, name) = resolve(path); require(name.isNotEmpty())
        mutate(session) { archive ->
            val matches = archive.fileHeaders.filter { safeName(it.fileName) == name }
            val hasChildren = archive.fileHeaders.any { safeName(it.fileName)?.startsWith("$name/") == true }
            if (directory) {
                require(matches.isEmpty() && !hasChildren) { "Directory or file already exists" }
            } else {
                // zip4j removes directory children as well when given a name
                // ending in '/'. Never turn a file save into subtree deletion.
                require(matches.none { it.isDirectory } && !hasChildren) { "Cannot replace an archive directory with a file" }
                require(matches.none(::zipSymlink)) { "Cannot replace a symbolic-link archive entry" }
                if (matches.isNotEmpty()) archive.removeFiles(matches.map { it.fileName }.distinct())
            }
            archive.addStream(input, parameters(name + if (directory) "/" else "", session.password != null && !directory))
        }
    }
    private fun parameters(name: String, encrypted: Boolean) = ZipParameters().apply {
        fileNameInZip = name; isEncryptFiles = encrypted
        if (encrypted) encryptionMethod = EncryptionMethod.AES
    }
    private fun mutate(session: Session, action: (ZipFile) -> Unit) =
      synchronized(originLocks.computeIfAbsent(Path.parse(session.origin).toString()) { Any() }) {
       synchronized(session) {
        require(session.type == "zip") { "This archive format is read-only; extract it to edit" }
        zip(session).use { existing ->
            if (existing.isEncrypted && session.password == null) throw ArchivePasswordRequired()
        }
        val fresh = File.createTempFile("check-", ".bin", session.file.parentFile)
        val replacement = File.createTempFile("update-", ".zip", session.file.parentFile)
        var downloaded = false
        var prepared = false
        var keepTemporary = false
        try {
            download(session.origin, fresh)
            downloaded = true
            check(hash(fresh) == session.digest) { "The original archive changed. Reopen it before editing" }
            fresh.copyTo(replacement, overwrite = true)
            prepared = true
            ZipFile(replacement, session.password).use { require(!it.isSplitArchive) { "Split ZIP archives are read-only" }; action(it) }
            FileRepository.writeStream(session.origin, replacement.inputStream(), replacement.length(), expectedDigest = session.digest).getOrThrow()
            Files.move(replacement.toPath(), session.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            session.digest = hash(session.file)
        } catch (failure: Throwable) {
            val recovery = try {
                preserveRecovery(session, if (downloaded) fresh else session.file,
                    if (prepared && replacement.exists()) replacement else session.file)
            } catch (recoveryFailure: Throwable) {
                keepTemporary = true
                failure.addSuppressed(recoveryFailure)
                session.file.parentFile!!.absolutePath
            }
            if (failure is CancellationException) {
                android.util.Log.w("Archive", "Cancelled archive edit; recovery files: $recovery", failure)
                throw failure
            }
            throw IOException("${failure.message ?: "Archive edit failed"}. Recovery copies: $recovery", failure)
        } finally { if (!keepTemporary) { fresh.delete(); replacement.delete() } }
       }
      }

    private fun preserveRecovery(session: Session, original: File, replacement: File): String {
        val directory = File(context.noBackupFilesDir, "archive-recovery/${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Cannot create an archive recovery directory" }
        original.copyTo(File(directory, "original.zip"))
        replacement.copyTo(File(directory, "replacement.zip"))
        // Also retain the mounted base when an external edit caused a conflict.
        if (hash(session.file) != hash(original)) session.file.copyTo(File(directory, "mounted-base.zip"))
        return directory.absolutePath
    }
    private fun resolve(path: String): Pair<Session, String> {
        val parts = path.trimStart('/').split('/', limit = 2)
        val id = parts.first(); require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid archive session" }
        val session = sessions.computeIfAbsent(id) {
            val prefs = context.getSharedPreferences("archive_sessions", Context.MODE_PRIVATE)
            val origin = prefs.getString("$id.origin", null) ?: throw FileNotFoundException("Reopen the archive")
            val local = File(context.cacheDir, "archives/$id/archive").apply { parentFile!!.mkdirs() }
            if (!local.exists()) download(origin, local)
            Session(id, origin, local, prefs.getString("$id.type", "zip")!!, null, hash(local))
        }
        val name = parts.getOrElse(1) { "" }.trimEnd('/')
        if (name.isNotEmpty()) require(safeName(name) == name) { "Invalid archive path" }
        return session to name
    }
    private fun zip(session: Session) = ZipFile(session.file, session.password)
    private fun zipSymlink(header: FileHeader): Boolean {
        val attributes = header.externalFileAttributes ?: return false
        return attributes.size >= 4 && (attributes[3].toInt() and 0xf0) == 0xa0
    }
    private fun entries(session: Session): List<Node> = (when (session.type) {
        "zip" -> zip(session).use { archive -> archive.fileHeaders.mapNotNull { header ->
            safeName(header.fileName)?.let { Node(it, header.isDirectory, header.uncompressedSize, header.lastModifiedTimeEpoch) }
        } }
        "7z" -> SevenZFile.builder().setFile(session.file).setPassword(session.password).get().use { archive ->
            archive.entries.mapNotNull { entry -> safeName(entry.name)?.let { Node(it, entry.isDirectory, entry.size,
                if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else 0) } }
        }
        else -> tar(session).use { archive -> buildList { while (true) {
            val entry = archive.nextTarEntry ?: break
            safeName(entry.name)?.let { add(Node(it, entry.isDirectory, entry.size, entry.modTime.time)) }
        } } }
    }).associateBy { it.name }.values.toList()
    private fun extractOne(session: Session, name: String, destination: File) {
        destination.outputStream().use { output -> when (session.type) {
            "zip" -> zip(session).use { archive ->
                val entry = archive.fileHeaders.lastOrNull { safeName(it.fileName) == name } ?: throw FileNotFoundException(name)
                require(!entry.isDirectory && archive.fileHeaders.none { safeName(it.fileName)?.startsWith("$name/") == true }) { "Cannot open an archive directory" }
                archive.getInputStream(entry).use { it.copyTo(output) }
            }
            "7z" -> SevenZFile.builder().setFile(session.file).setPassword(session.password).get().use { archive ->
                val entry = archive.entries.lastOrNull { safeName(it.name) == name } ?: throw FileNotFoundException(name)
                require(!entry.isDirectory && archive.entries.none { safeName(it.name)?.startsWith("$name/") == true }) { "Cannot open an archive directory" }
                archive.getInputStream(entry).use { it.copyTo(output) }
            }
            else -> tar(session).use { archive ->
                var found = false
                var readable = false
                var hasChildren = false
                while (true) {
                    val entry = archive.nextTarEntry ?: break
                    val canonical = safeName(entry.name)
                    if (canonical?.startsWith("$name/") == true) hasChildren = true
                    if (canonical == name) {
                        readable = entry.isFile && !entry.isSymbolicLink && !entry.isLink
                        output.channel.truncate(0L); output.channel.position(0L)
                        if (readable) archive.copyTo(output)
                        found = true
                    }
                }
                if (!found) throw FileNotFoundException(name)
                require(readable && !hasChildren) { "Directories, links and special archive entries cannot be extracted as files" }
            }
        } }
    }
    private fun tar(session: Session): TarArchiveInputStream {
        val input = session.file.inputStream().buffered()
        return try {
            if (session.origin.lowercase().endsWith(".tar")) TarArchiveInputStream(input)
            else TarArchiveInputStream(CompressorStreamFactory().createCompressorInputStream(input))
        } catch (e: Exception) { input.close(); throw e }
    }
    private fun safeName(raw: String): String? {
        val name = raw.removePrefix("./").trimEnd('/')
        if (name.isEmpty() || name.startsWith('/') || '\\' in name || '\u0000' in name ||
            name.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return name
    }
    private fun download(origin: String, file: File) {
        val target = FileRepository.resolve(origin).getOrThrow()
        runBlocking { target.provider.open(target.innerPath, OpenMode.READ).getOrThrow() }.use { source ->
            file.outputStream().use { output -> SourceInput(source).copyTo(output) }
        }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(256 * 1024); while (true) {
            val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private data class Session(val id: String, val origin: String, val file: File, val type: String,
        val password: CharArray?, var digest: String)
    private data class Node(val name: String, val directory: Boolean, val size: Long, val modified: Long)
    private class SourceInput(private val source: FileSource) : InputStream() {
        private var offset = 0L
        override fun read(): Int { val bytes = source.readAt(offset, 1); if (bytes.isEmpty()) return -1; offset++; return bytes[0].toInt() and 255 }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val data = source.readAt(offset, len); if (data.isEmpty()) return -1
            data.copyInto(bytes, off); offset += data.size; return data.size
        }
    }
}
