package io.furryr.file.store

import android.content.Context
import io.furryr.file.provider.model.LocalLocation
import org.json.JSONObject
import java.util.UUID

/** Persists user-pinned local filesystem locations shown in the drawer. */
object LocalLocationStore {
    private lateinit var store: JsonLocationStore<LocalLocation>

    @Synchronized
    fun init(context: Context) {
        store = JsonLocationStore(
            context = context,
            prefsName = "local_locations",
            label = "local locations",
            idOf = { it.id },
            orderOf = { it.sortOrder },
            withName = { item, name -> item.copy(name = name) },
            withOrder = { item, order -> item.copy(sortOrder = order) },
            decode = { row, index ->
                LocalLocation(
                    id = row.getString("id"),
                    name = row.getString("name"),
                    path = row.getString("path"),
                    sortOrder = row.optInt("sortOrder", index),
                )
            },
            encode = { item ->
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("path", item.path)
                    .put("sortOrder", item.sortOrder)
            },
        )
    }

    fun all(): List<LocalLocation> = store.all()

    @Synchronized
    fun add(path: String, name: String = ""): String {
        require(path.startsWith('/')) { "A local location must use an absolute path" }
        val existing = store.all()
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
        store.write(existing + LocalLocation(id, unique, path, (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1))
        return id
    }

    fun rename(id: String, name: String) = store.rename(id, name)

    fun delete(id: String) = store.delete(id)

    fun reorder(ids: List<String>) = store.reorder(ids)
}
