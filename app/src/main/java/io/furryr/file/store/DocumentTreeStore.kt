package io.furryr.file.store

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.furryr.file.provider.DocumentTreeSource

/**
 * Persists SAF tree URIs used by `document://` paths. The provider receives
 * this store as a read-only [DocumentTreeSource]; registration lives here so
 * no library module touches preferences.
 */
object DocumentTreeStore : DocumentTreeSource {
    private const val PREFS_NAME = "document_trees"
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun documentTree(id: String): String? = prefs.getString(id, null)

    /** List all registered document tree IDs with their URIs. */
    @Synchronized
    fun all(): Map<String, String> = prefs.all.mapValues { it.value.toString() }

    /** Register a tree URI from `ACTION_OPEN_DOCUMENT_TREE` and return its id. */
    @Synchronized
    fun register(treeUri: Uri): String {
        val id = generateId()
        prefs.edit().putString(id, treeUri.toString()).apply()
        return id
    }

    @Synchronized
    fun unregister(id: String) {
        prefs.edit().remove(id).apply()
    }

    private fun generateId(): String {
        val chars = "0123456789abcdef"
        while (true) {
            val id = (1..8).map { chars.random() }.joinToString("")
            if (!prefs.contains(id)) return id
        }
    }
}
