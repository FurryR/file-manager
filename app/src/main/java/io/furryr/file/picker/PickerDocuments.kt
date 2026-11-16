package io.furryr.file.picker

import android.net.Uri
import android.provider.DocumentsContract
import io.furryr.file.PickerProviderAuthority
import io.furryr.file.core.VNode

/** Builds the `content://` addresses a picker returns to its caller. */
object PickerDocuments {
    fun documentUri(worldId: String, access: String, node: VNode): Uri =
        documentUri(worldId, access, node.path.toString())

    fun documentUri(worldId: String, access: String, path: String): Uri {
        val id = PickerTokens.issue(PickerDoc(worldId, access, path))
        return DocumentsContract.buildDocumentUri(PickerProviderAuthority, id)
    }

    fun treeUri(worldId: String, path: String, access: String = "rw"): Uri {
        val id = PickerTokens.issue(PickerDoc(worldId, access, path))
        return DocumentsContract.buildTreeDocumentUri(PickerProviderAuthority, id)
    }

    /** The address inside a picker document/tree URI, or null for a foreign URI. */
    fun pathOf(uri: Uri): String? {
        if (uri.authority != PickerProviderAuthority) return null
        val id = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull() ?: return null
        return PickerTokens.verify(id)?.path
    }
}
