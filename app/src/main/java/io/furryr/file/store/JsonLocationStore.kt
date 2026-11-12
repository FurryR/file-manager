package io.furryr.file.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ordered preference list shared by the JSON-backed location stores.
 *
 * A store supplies the model projection (one decode and one encode) and the
 * four field accessors; rename, delete and reorder are implemented once.
 */
internal class JsonLocationStore<T>(
    context: Context,
    prefsName: String,
    private val label: String,
    private val idOf: (T) -> String,
    private val orderOf: (T) -> Int,
    private val withName: (T, String) -> T,
    private val withOrder: (T, Int) -> T,
    private val decode: (JSONObject, Int) -> T,
    private val encode: (T) -> JSONObject,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<T> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val records = JSONArray(raw)
            (0 until records.length())
                .map { index -> decode(records.getJSONObject(index), index) }
                .sortedBy(orderOf)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A location name is required" }
        write(all().map { if (idOf(it) == id) withName(it, trimmed) else it })
    }

    @Synchronized
    fun delete(id: String) {
        write(all().filterNot { idOf(it) == id }.mapIndexed { index, item -> withOrder(item, index) })
    }

    @Synchronized
    fun reorder(ids: List<String>) {
        val existing = all()
        val byId = existing.associateBy(idOf)
        val ordered = ids.mapNotNull { byId[it] } + existing.filter { idOf(it) !in ids }
        write(ordered.mapIndexed { index, item -> withOrder(item, index) })
    }

    @Synchronized
    fun write(items: List<T>) {
        val records = JSONArray()
        items.forEach { records.put(encode(it)) }
        check(prefs.edit().putString(KEY, records.toString()).commit()) {
            "Could not save $label"
        }
    }

    private companion object {
        const val KEY = "items"
    }
}
