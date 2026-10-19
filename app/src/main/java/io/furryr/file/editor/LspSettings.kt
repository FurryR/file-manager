package io.furryr.file.editor

import android.content.Context
import io.furryr.file.agent.ContainerUriResolver

internal data class LspSettings(val container: String, val command: String, val root: String, val documentPath: String) {
    val valid: Boolean get() = ContainerUriResolver.isValidContainerName(container) && command.isNotBlank() &&
        root.startsWith('/') && documentPath.startsWith('/') && '\u0000' !in command && '\u0000' !in root && '\u0000' !in documentPath

    companion object {
        fun load(context: Context, path: String, language: String): LspSettings {
            val prefs = context.getSharedPreferences("editor_lsp", Context.MODE_PRIVATE)
            val location = if (path.startsWith("container://")) ContainerUriResolver.resolveContainerUri(path).getOrNull() else null
            val container = location?.containerName ?: prefs.getString("$language.container", "").orEmpty()
            val documentPath = location?.containerPath ?: if (path.startsWith('/')) path else ""
            return LspSettings(container, prefs.getString("$language.command", "").orEmpty(),
                prefs.getString("$language.root", null) ?: documentPath.substringBeforeLast('/', "").ifEmpty { "/" },
                documentPath)
        }
    }

    fun save(context: Context, language: String) {
        require(valid)
        context.getSharedPreferences("editor_lsp", Context.MODE_PRIVATE).edit()
            .putString("$language.container", container).putString("$language.command", command)
            .putString("$language.root", root).apply()
    }
}
