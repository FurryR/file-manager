package io.furryr.file

import android.content.Context
import android.content.SharedPreferences
import io.furryr.file.model.LocalLocation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists user-pinned local filesystem locations shown in the drawer. */
object LocalLocationStore {
    private const val KEY = "items"
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("local_locations", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): List<LocalLocation> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val records = JSONArray(raw)
            (0 until records.length()).map { index ->
                records.getJSONObject(index).let { row ->
                    LocalLocation(
                        id = row.getString("id"),
                        name = row.getString("name"),
                        path = row.getString("path"),
                        sortOrder = row.optInt("sortOrder", index),
                    )
                }
            }.sortedBy { it.sortOrder }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(path: String, name: String = ""): String {
        require(path.startsWith('/')) { "A local location must use an absolute path" }
        val existing = all()
        existing.firstOrNull { it.path == path }?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val base = name.trim().ifBlank { path.trimEnd('/').substringAfterLast('/').ifBlank { path } }
        val names = existing.map { it.name }.toSet()
        var unique = base
        var index = 1
        while (unique in names) {
            unique = "$base ($index)"
            index++
        }
        write(existing + LocalLocation(id, unique, path, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1))
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

    private fun write(items: List<LocalLocation>) {
        val records = JSONArray()
        items.forEach { item ->
            records.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("path", item.path)
                    .put("sortOrder", item.sortOrder),
            )
        }
        check(prefs.edit().putString(KEY, records.toString()).commit()) {
            "Could not save local locations"
        }
    }
}
