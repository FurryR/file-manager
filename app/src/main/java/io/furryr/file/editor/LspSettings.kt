package io.furryr.file.editor

import android.content.Context
import io.furryr.file.core.ContainerUriResolver

data class LspSettings(val container: String, val command: String, val root: String, val documentPath: String) {
    val valid: Boolean get() = ContainerUriResolver.isValidContainerName(container) && command.isNotBlank() &&
        root.startsWith('/') && documentPath.startsWith('/') && '\u0000' !in command && '\u0000' !in root && '\u0000' !in documentPath

    companion object {
        /**
         * Settings screen state for one language. A blank command means the
         * language has no configured server.
         */
        fun forFile(context: Context, path: String, language: String): LspSettings? {
            // View addresses stay textual; only container:// can be mapped to a
            // real document path inside a running container.
            val location = ContainerUriResolver.resolveContainerUri(path).getOrNull() ?: return null
            val container = location.containerName ?: return null
            val config = LspConfig.load(context, language) ?: return null
            if (!config.valid || config.container != container) return null
            return LspSettings(config.container, config.command, config.root, location.containerPath)
        }
    }
}

/** Per-language server configuration persisted for the settings screen. */
data class LspConfig(val container: String, val command: String, val root: String) {
    val valid: Boolean get() = ContainerUriResolver.isValidContainerName(container) && command.isNotBlank() &&
        root.startsWith('/') && '\u0000' !in command && '\u0000' !in root

    companion object {
        private const val PREFS = "editor_lsp"

        fun load(context: Context, language: String): LspConfig? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val command = prefs.getString("$language.command", "").orEmpty()
            if (command.isBlank()) return null
            return LspConfig(
                prefs.getString("$language.container", "").orEmpty(),
                command,
                prefs.getString("$language.root", "/") ?: "/",
            )
        }

        fun save(context: Context, language: String, config: LspConfig) {
            require(config.valid)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("$language.container", config.container)
                .putString("$language.command", config.command)
                .putString("$language.root", config.root)
                .apply()
        }

        fun clear(context: Context, language: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("$language.container")
                .remove("$language.command")
                .remove("$language.root")
                .apply()
        }

        fun configured(context: Context, language: String): Boolean =
            load(context, language)?.valid == true
    }
}
