package io.furryr.file.store

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.furryr.file.provider.model.WebLocation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists web pages pinned in the drawer's network group. These are links, not
 * filesystem locations: they open in the web renderer instead of a provider.
 */
object WebLocationStore {
    private const val KEY = "items"
    private val schemes = setOf("http", "https")
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("web_locations", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): List<WebLocation> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val records = JSONArray(raw)
            (0 until records.length()).map { index ->
                records.getJSONObject(index).let { row ->
                    WebLocation(
                        id = row.getString("id"),
                        name = row.getString("name"),
                        url = row.getString("url"),
                        sortOrder = row.optInt("sortOrder", index),
                    )
                }
            }.sortedBy { it.sortOrder }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(url: String, name: String = ""): String {
        val normalized = normalize(url)
        val existing = all()
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
        write(existing + WebLocation(id, unique, normalized, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1))
        return id
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A location name is required" }
        write(all().map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    @Synchronized
    fun delete(id: String) {
        write(all().filterNot { it.id == id }.mapIndexed { index, item -> item.copy(sortOrder = index) })
    }

    @Synchronized
    fun reorder(ids: List<String>) {
        val existing = all()
        val byId = existing.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + existing.filter { it.id !in ids }
        write(ordered.mapIndexed { index, item -> item.copy(sortOrder = index) })
    }

    /** Validate and trim a web address; only explicit http/https URLs are accepted. */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val uri = Uri.parse(trimmed)
        require(uri.scheme?.lowercase() in schemes && !uri.host.isNullOrBlank()) {
            "A web location needs an http:// or https:// URL"
        }
        return trimmed
    }

    private fun write(items: List<WebLocation>) {
        val records = JSONArray()
        items.forEach { item ->
            records.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("sortOrder", item.sortOrder),
            )
        }
        check(prefs.edit().putString(KEY, records.toString()).commit()) {
            "Could not save web locations"
        }
    }
}
