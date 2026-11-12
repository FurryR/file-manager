package io.furryr.file.store

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Ordered `(host, name, port, username, password)` table shared by the FTP and
 * SFTP stores. Only the database name, default port and row model differ.
 */
abstract class ServerDatabase<T>(
    context: Context,
    name: String,
    private val defaultPort: Int,
    private val mapper: (
        host: String,
        name: String,
        port: Int,
        username: String,
        password: String,
        sortOrder: Int,
    ) -> T,
    private val nameOf: (T) -> String,
) : SQLiteOpenHelper(context, name, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE servers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                port INTEGER NOT NULL DEFAULT $defaultPort,
                username TEXT NOT NULL DEFAULT '',
                password TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS servers")
        onCreate(db)
    }

    fun insert(host: String, name: String, port: Int, username: String, password: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO servers (host, name, port, username, password, sort_order) VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order),0)+1 FROM servers))",
            arrayOf(host, uniqueName(name), port, username, password),
        )
    }

    fun delete(host: String) =
        writableDatabase.execSQL("DELETE FROM servers WHERE host=?", arrayOf(host))

    fun rename(host: String, name: String) =
        writableDatabase.execSQL("UPDATE servers SET name=? WHERE host=?", arrayOf(name, host))

    fun update(host: String, newHost: String, name: String, port: Int, username: String, password: String) =
        writableDatabase.execSQL(
            "UPDATE servers SET host=?, name=?, port=?, username=?, password=? WHERE host=?",
            arrayOf(newHost, name, port, username, password, host),
        )

    fun reorder(hosts: List<String>) {
        writableDatabase.execSQL("UPDATE servers SET sort_order=-1")
        hosts.forEachIndexed { index, host ->
            writableDatabase.execSQL("UPDATE servers SET sort_order=? WHERE host=?", arrayOf(index, host))
        }
        writableDatabase.execSQL("DELETE FROM servers WHERE sort_order=-1")
    }

    fun getAll(): List<T> = read(null, null)

    fun findByHost(host: String): T? = read("host=?", arrayOf(host), firstOnly = true).firstOrNull()

    private fun read(where: String?, args: Array<String>?, firstOnly: Boolean = false): List<T> {
        val filter = if (where == null) "" else " WHERE $where"
        val order = if (firstOnly) "" else " ORDER BY sort_order"
        return readableDatabase.rawQuery(
            "SELECT host, name, port, username, password, sort_order FROM servers$filter$order",
            args,
        ).use { cursor: Cursor ->
            val items = mutableListOf<T>()
            while (cursor.moveToNext()) {
                items += mapper(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getInt(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getInt(5),
                )
                if (firstOnly) break
            }
            items
        }
    }

    private fun uniqueName(base: String): String {
        val names = getAll().map(nameOf).toSet()
        if (base !in names) return base
        var index = 1
        while ("$base ($index)" in names) index++
        return "$base ($index)"
    }
}
