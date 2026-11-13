package io.furryr.file.core

import io.furryr.file.core.source.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

enum class ContentKind {
    TEXT,
    CODE,
    IMAGE,
    AUDIO,
    VIDEO,
    ARCHIVE,
    PDF,
    BINARY,
}

/**
 * Content facts consumed by renderers. This deliberately contains no provider
 * or path information: a renderer only needs to know what kind of bytes it is
 * looking at and whether it can seek.
 */
data class ContentInfo(
    val name: String,
    val size: Long,
    val mime: String?,
    val kind: ContentKind,
    val seekable: Boolean,
) {
    val isArchive: Boolean get() = kind == ContentKind.ARCHIVE
}

object ContentTypes {
    /** First bytes are enough for the formats we care about here. */
    const val SNIFF_BYTES = 8 * 1024

    private val mimeByExtension: Map<String, String> = buildMap {
        fun register(mime: String, vararg extensions: String) {
            for (extension in extensions) put(extension, mime)
        }
        register("text/plain", "txt", "text", "log", "md", "markdown", "csv", "ini", "cfg", "conf")
        register("text/html", "html", "htm", "xhtml")
        register("text/css", "css")
        register("application/json", "json")
        register("application/xml", "xml")
        register("application/javascript", "js", "mjs")
        register("application/typescript", "ts", "tsx")
        register("text/x-kotlin", "kt", "kts")
        register("text/x-java", "java")
        register("text/x-python", "py")
        register("text/x-rust", "rs")
        register("text/x-go", "go")
        register("text/x-c", "c", "h")
        register("text/x-cpp", "cpp", "cc", "cxx", "hpp")
        register("text/x-shellscript", "sh", "bash", "zsh")
        register("text/x-fish", "fish")
        register("text/x-ruby", "rb")
        register("text/x-php", "php")
        register("text/x-lua", "lua")
        register("text/x-dart", "dart")
        register("text/x-swift", "swift")
        register("text/x-csharp", "cs")
        register("text/x-scss", "scss", "sass", "less")
        register("text/x-vue", "vue")
        register("text/x-svelte", "svelte")
        register("text/x-jsonc", "jsonc")
        register("text/x-gradle", "gradle")
        register("text/x-cmake", "cmake")
        register("text/x-powershell", "ps1")
        register("text/x-batch", "bat", "cmd")
        register("text/x-diff", "patch", "diff")
        register("text/x-properties", "properties", "env")
        register("application/x-yaml", "yaml", "yml")
        register("application/toml", "toml")
        register("application/sql", "sql")
        register("image/png", "png")
        register("image/jpeg", "jpg", "jpeg")
        register("image/gif", "gif")
        register("image/webp", "webp")
        register("image/bmp", "bmp")
        register("image/svg+xml", "svg")
        register("image/x-icon", "ico")
        register("image/heic", "heic", "heif")
        register("image/tiff", "tif", "tiff")
        register("audio/mpeg", "mp3")
        register("audio/wav", "wav")
        register("audio/flac", "flac")
        register("audio/aac", "aac")
        register("audio/ogg", "ogg", "opus")
        register("audio/mp4", "m4a")
        register("audio/x-ms-wma", "wma")
        register("video/mp4", "mp4", "m4v")
        register("video/x-matroska", "mkv")
        register("video/webm", "webm")
        register("video/quicktime", "mov")
        register("video/x-msvideo", "avi")
        register("video/x-ms-wmv", "wmv")
        register("video/x-flv", "flv")
        register("video/3gpp", "3gp")
        register("video/mpeg", "mpg", "mpeg")
        register("application/pdf", "pdf")
        register("application/zip", "zip", "jar", "epub")
        register("application/vnd.android.package-archive", "apk")
        register("application/x-tar", "tar")
        register("application/gzip", "gz", "tgz")
        register("application/x-7z-compressed", "7z")
        register("application/vnd.rar", "rar")
        register("application/x-xz", "xz")
        register("application/x-bzip2", "bz2")
        register("application/octet-stream", "bin", "so", "dll", "dex", "class")
    }

    private val codeMimes = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/typescript",
        "application/x-yaml",
        "application/toml",
        "application/sql",
        "text/css",
        "text/html",
        "text/x-kotlin",
        "text/x-java",
        "text/x-python",
        "text/x-rust",
        "text/x-go",
        "text/x-c",
        "text/x-cpp",
        "text/x-shellscript",
        "text/x-fish",
        "text/x-ruby",
        "text/x-php",
        "text/x-lua",
        "text/x-dart",
        "text/x-swift",
        "text/x-csharp",
        "text/x-scss",
        "text/x-vue",
        "text/x-svelte",
        "text/x-jsonc",
        "text/x-gradle",
        "text/x-cmake",
        "text/x-powershell",
        "text/x-batch",
        "text/x-diff",
        "text/x-properties",
    )

    private val archiveMimes = setOf(
        "application/zip",
        "application/x-tar",
        "application/gzip",
        "application/x-7z-compressed",
        "application/vnd.rar",
        "application/x-xz",
        "application/x-bzip2",
    )

    /**
     * Extensions contributed at runtime (built-in modules and plugins). Kept
     * apart from the baked-in table so an extension can be replaced without
     * rebuilding the map.
     */
    private val registered = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Teach the table one extension; later registrations win. */
    fun register(extension: String, mime: String) {
        registered[extension.trimStart('.').lowercase(Locale.ROOT)] = mime
    }

    fun register(mime: String, vararg extensions: String) {
        for (extension in extensions) register(extension, mime)
    }

    fun unregister(extension: String) {
        registered.remove(extension.trimStart('.').lowercase(Locale.ROOT))
    }

    fun fromName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        return registered[extension] ?: mimeByExtension[extension]
    }

    /** The kind implied by the name alone, or null when the extension is unknown. */
    fun kindOfName(name: String): ContentKind? = fromName(name)?.let { kindOf(it, name) }

    /** True for the types a browser must render as a page, not save as a file. */
    fun isHtmlMime(mime: String?): Boolean {
        val value = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return value == "text/html" || value == "application/xhtml+xml"
    }

    /** Server headers that carry no usable type; the name/sniff must decide. */
    private val genericMimes = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/unknown",
        "unknown/unknown",
    )

    /** True when [mime] names no concrete type (null/octet-stream). */
    fun isGenericMime(mime: String?): Boolean {
        val value = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() } ?: return true
        return value in genericMimes
    }

    fun kindOf(mime: String?, name: String): ContentKind {
        val value = mime?.lowercase(Locale.ROOT)
        if (value == null) return ContentKind.BINARY
        if (value in archiveMimes) return ContentKind.ARCHIVE
        if (value == "application/pdf") return ContentKind.PDF
        if (value.startsWith("image/")) return ContentKind.IMAGE
        if (value.startsWith("audio/")) return ContentKind.AUDIO
        if (value.startsWith("video/")) return ContentKind.VIDEO
        if (value in codeMimes) return ContentKind.CODE
        if (value.startsWith("text/")) return ContentKind.TEXT
        return ContentKind.BINARY
    }

    /** Sniff a MIME type from the first bytes; returns null when inconclusive. */
    fun sniffMime(head: ByteArray): String? {
        if (head.isEmpty()) return null
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte()) &&
            (head[3] == 0x04.toByte() || head[3] == 0x06.toByte() || head[3] == 0x08.toByte())
        ) return "application/zip"
        if (head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
            return "application/gzip"
        }
        if (head.size >= 265) {
            val marker = String(head, 257, 5, Charsets.US_ASCII)
            if (marker == "ustar") return "application/x-tar"
        }
        if (head.size >= 5 && String(head, 0, 5, Charsets.US_ASCII) == "%PDF-") {
            return "application/pdf"
        }
        if (head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        ) return "image/png"
        if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte()
        ) return "image/jpeg"
        if (head.size >= 6 && String(head, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) {
            return "image/gif"
        }
        if (head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return "image/webp"
        if (looksLikeHtml(head)) return "text/html"
        isProbablyText(head)?.let { return if (it) "text/plain" else null }
        return null
    }

    /**
     * A page starts with a doctype or `<html` once BOM and whitespace are
     * skipped. Servers that mislabel pages as `text/plain`/octet-stream still
     * reach the WebView through this check.
     */
    private fun looksLikeHtml(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val text = String(head, Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
            .take(512)
            .lowercase(Locale.ROOT)
        return text.startsWith("<!doctype html") ||
            text.startsWith("<html") ||
            text.startsWith("<head") ||
            (text.startsWith("<?xml") && text.contains("<html"))
    }

    /**
     * Cheap binary/text heuristic. UTF-8 validation is preferred; a short
     * prefix with no NUL and few control characters is treated as text even
     * when it ends mid-codepoint.
     */
    fun isProbablyText(bytes: ByteArray): Boolean? {
        if (bytes.isEmpty()) return null
        var control = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return false
            if (value < 0x09 || (value in 0x0E..0x1F)) control++
        }
        if (control > bytes.size / 20) return false
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            // The probe is a bounded prefix: a multi-byte code point split at
            // the tail is inconclusive, not binary. Any earlier replacement
            // character still means the bytes are not UTF-8 text.
            val first = text.indexOf('\uFFFD')
            return first >= text.length - 3 && text.indexOf('\uFFFD', first + 1) < 0
        }
        return true
    }

    fun describe(
        name: String,
        size: Long,
        head: ByteArray,
        seekable: Boolean,
        declaredMime: String? = null,
    ): ContentInfo {
        // The response header is authoritative when the server names a real
        // type. Octet-stream means "no type": fall back to the extension, then
        // to magic bytes (a `.git` link served as HTML must still be a page).
        val declared = declaredMime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it !in genericMimes }
        val mime = declared ?: fromName(name) ?: sniffMime(head)
        var kind = kindOf(mime, name)
        if (kind == ContentKind.BINARY && mime == null && isProbablyText(head) == true) {
            kind = ContentKind.TEXT
        }
        return ContentInfo(
            name = name,
            size = size,
            mime = mime,
            kind = kind,
            seekable = seekable,
        )
    }

    /** True when [mime] is one of the archive types this app understands. */
    fun isArchiveMime(mime: String?): Boolean =
        mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) in archiveMimes

    /**
     * The one content probe shared by stored files and archive entries: a
     * bounded prefix read, never a whole-body transfer.
     */
    suspend fun describe(name: String, size: Long, source: FileSource?): ContentInfo {
        val actualSize = source?.size ?: size
        val head = if (source != null && actualSize > 0L) {
            val length = minOf(actualSize, SNIFF_BYTES.toLong()).toInt()
            withContext(Dispatchers.IO) {
                runCatchingCancellable { source.readAt(0L, length) }.getOrDefault(ByteArray(0))
            }
        } else {
            ByteArray(0)
        }
        return describe(name, actualSize, head, source?.seekable ?: false)
    }
}
