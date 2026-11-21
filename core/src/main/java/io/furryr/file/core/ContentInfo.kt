package io.furryr.file.core

import java.util.Locale

/** A MIME type, without parameters and compared in lowercase. */
typealias MimeType = String

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

object ContentTypes {
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
        register("font/ttf", "ttf")
        register("font/otf", "otf")
        register("font/collection", "ttc")
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

    /** Archive types the app can browse as a view. */
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

    /** Every MIME the table knows, baked in or registered at runtime. */
    val mimes: Set<String>
        get() = buildSet {
            addAll(mimeByExtension.values)
            addAll(registered.values)
        }

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

    /** Canonical MIME text: no parameters, trimmed, lowercase, or null when blank. */
    fun normalizeMime(mime: String?): MimeType? =
        mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

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

    /** True when [mime] is one of the archive types this app understands. */
    private fun isArchiveMime(mime: String?): Boolean =
        mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) in archiveMimes
}
