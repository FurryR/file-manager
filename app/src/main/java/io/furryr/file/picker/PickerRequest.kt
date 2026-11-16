package io.furryr.file.picker

import android.content.Intent
import android.net.Uri
import io.furryr.file.DefaultPath

enum class PickerMode { OPEN, CREATE, TREE }

/** One picker invocation, folded from the caller's intent. */
data class PickerRequest(
    val mode: PickerMode,
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
    val localOnly: Boolean,
    val initialPath: String,
    val suggestedName: String,
    val persistable: Boolean,
) {
    val selectFiles: Boolean get() = mode == PickerMode.OPEN
    val selectFolder: Boolean get() = mode == PickerMode.TREE
    val createFile: Boolean get() = mode == PickerMode.CREATE

    /** True when [name] is a file the caller's MIME filter accepts. */
    fun accepts(name: String): Boolean {
        if (mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" }) return true
        val mime = io.furryr.file.core.ContentTypes.fromName(name) ?: return false
        return mimeTypes.any { filter ->
            filter == mime ||
                (filter.endsWith("/*") && mime.startsWith(filter.dropLast(1)))
        }
    }

    companion object {
        fun from(intent: Intent): PickerRequest? {
            val mode = when (intent.action) {
                Intent.ACTION_GET_CONTENT,
                Intent.ACTION_OPEN_DOCUMENT,
                -> PickerMode.OPEN
                Intent.ACTION_CREATE_DOCUMENT -> PickerMode.CREATE
                Intent.ACTION_OPEN_DOCUMENT_TREE -> PickerMode.TREE
                else -> return null
            }
            val type = intent.type?.trim()?.takeIf { it.isNotEmpty() } ?: "*/*"
            @Suppress("DEPRECATION")
            val extraTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            val mimeTypes = (listOf(type) + extraTypes.orEmpty())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .ifEmpty { listOf("*/*") }
            val allowMultiple = mode == PickerMode.OPEN &&
                intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            val localOnly = mode == PickerMode.OPEN &&
                intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
            val initial = initialPath(intent)
            val suggested = intent.getStringExtra(Intent.EXTRA_TITLE)
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.trim()
                .orEmpty()
            return PickerRequest(
                mode = mode,
                mimeTypes = mimeTypes,
                allowMultiple = allowMultiple,
                localOnly = localOnly,
                initialPath = initial?.takeIf { it.isNotBlank() } ?: DefaultPath,
                suggestedName = suggested,
                persistable = intent.action != Intent.ACTION_GET_CONTENT,
            )
        }

        @Suppress("DEPRECATION")
        private fun initialPath(intent: Intent): String? {
            val uri = runCatching {
                intent.getParcelableExtra("android.provider.extra.INITIAL_URI") as? Uri
            }.getOrNull()
            if (uri != null) {
                when (uri.scheme?.lowercase()) {
                    "file" -> uri.path?.let { return it }
                    "content" -> PickerDocuments.pathOf(uri)?.let { return it }
                }
            }
            val data = intent.data ?: return null
            return when (data.scheme?.lowercase()) {
                "file" -> data.path
                "content" -> PickerDocuments.pathOf(data)
                null -> data.toString().takeIf { it.startsWith('/') }
                else -> null
            }
        }
    }
}
