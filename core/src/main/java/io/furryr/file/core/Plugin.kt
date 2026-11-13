package io.furryr.file.core

/**
 * One installable unit of extension.
 *
 * A plugin never receives a private back door: [PluginHost] exposes exactly the
 * core registries the built-in modules use — providers, view openers and
 * content types. App-level contributions (renderers, location kinds, open
 * rules) are wired by the app itself and are not plugin surfaces.
 */
interface Plugin {
    /** Stable identity; re-registering the same [id] replaces the old entry. */
    val id: String

    val version: String get() = "1.0"

    /** Larger values install first. */
    val priority: Int get() = 0

    /** Register contributions. Called once per process after the app is initialized. */
    fun install(host: PluginHost)
}

/**
 * Registration surface handed to a plugin.
 *
 * Keep this interface additive: every new kind of contribution belongs here,
 * not in a plugin-facing side channel.
 */
interface PluginHost {
    /** Register [provider] for [scheme]; [isDefault] also makes it the bare-path provider. */
    fun provider(scheme: String, provider: FileProvider, isDefault: Boolean = false)

    /** Register a view opener (a file format that opens as a folder). */
    fun viewOpener(priority: Int, opener: ViewOpener)

    /** Teach the MIME table one extension. */
    fun contentType(extension: String, mime: String)
}

/** The registries installed by the app itself; the only host implementation today. */
object CorePluginHost : PluginHost {
    override fun provider(scheme: String, provider: FileProvider, isDefault: Boolean) {
        if (isDefault) Vfs.setDefault(provider)
        Vfs.register(scheme, provider)
    }

    override fun viewOpener(priority: Int, opener: ViewOpener) {
        ViewOpeners.register(priority, opener)
    }

    override fun contentType(extension: String, mime: String) {
        ContentTypes.register(extension, mime)
    }
}

/** A plugin that failed to install; the rest of the registry keeps working. */
data class PluginFailure(val id: String, val cause: Throwable)

/**
 * Process-wide plugin registry. Registration order is not install order: the
 * list is sorted by [Plugin.priority] when [installAll] runs.
 */
object Plugins {
    private val entries = mutableListOf<Plugin>()

    @Synchronized
    fun register(plugin: Plugin) {
        entries.removeAll { it.id == plugin.id }
        entries += plugin
    }

    @Synchronized
    fun unregister(id: String) {
        entries.removeAll { it.id == id }
    }

    @Synchronized
    fun installed(): List<Plugin> = entries.toList()

    /**
     * Install every registered plugin. A throwing plugin is reported and
     * skipped, so one bad extension cannot keep the app from starting.
     */
    fun installAll(host: PluginHost = CorePluginHost): List<PluginFailure> =
        installed()
            .sortedByDescending { it.priority }
            .mapNotNull { plugin ->
                runCatching { plugin.install(host) }
                    .exceptionOrNull()
                    ?.let { PluginFailure(plugin.id, it) }
            }
}
