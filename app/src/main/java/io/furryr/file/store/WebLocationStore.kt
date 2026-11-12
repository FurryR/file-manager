package io.furryr.file.store

import android.content.Context
import android.net.Uri
import io.furryr.file.provider.ProviderSchemes
import io.furryr.file.provider.model.WebLocation
import org.json.JSONObject
import java.util.UUID

/**
 * Persists web pages pinned in the drawer's network group. These are links, not
 * filesystem locations: they open in the web renderer instead of a provider.
 */
object WebLocationStore {
    private lateinit var store: JsonLocationStore<WebLocation>
    private val schemes: Set<String> get() = ProviderSchemes.web

    @Synchronized
    fun init(context: Context) {
        store = JsonLocationStore(
            context = context,
            prefsName = "web_locations",
            label = "web locations",
            idOf = { it.id },
            orderOf = { it.sortOrder },
            withName = { item, name -> item.copy(name = name) },
            withOrder = { item, order -> item.copy(sortOrder = order) },
            decode = { row, index ->
                WebLocation(
                    id = row.getString("id"),
                    name = row.getString("name"),
                    url = row.getString("url"),
                    sortOrder = row.optInt("sortOrder", index),
                )
            },
            encode = { item ->
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("sortOrder", item.sortOrder)
            },
        )
    }

    fun all(): List<WebLocation> = store.all()

    @Synchronized
    fun add(url: String, name: String = ""): String {
        val normalized = normalize(url)
        val existing = store.all()
        existing.firstOrNull { it.url == normalized }?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val host = Uri.parse(normalized).host.orEmpty()
        val base = name.trim().ifBlank {
            host.ifBlank { normalized.trimEnd('/').substringAfterLast('/').ifBlank { normalized } }
        }
        val names = existing.map { it.name }.toSet()
        var unique = base
        var index = 1
        while (unique in names) {
            unique = "$base ($index)"
            index++
        }
        store.write(existing + WebLocation(id, unique, normalized, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1))
        return id
    }

    fun rename(id: String, name: String) = store.rename(id, name)

    fun delete(id: String) = store.delete(id)

    fun reorder(ids: List<String>) = store.reorder(ids)

    /** Validate and trim a web address; only explicit web-scheme URLs are accepted. */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val uri = Uri.parse(trimmed)
        require(uri.scheme?.lowercase() in schemes && !uri.host.isNullOrBlank()) {
            "A web location needs a supported URL scheme and a host"
        }
        return trimmed
    }
}
