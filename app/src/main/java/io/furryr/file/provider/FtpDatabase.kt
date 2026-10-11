package io.furryr.file.provider

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class FtpServer(
    val host: String,
    val name: String,
    val port: Int,
    val username: String,
    val password: String,
    val sortOrder: Int,
)

class FtpDatabase(context: Context) : SQLiteOpenHelper(context, "ftp_servers.db", null, 1) {
    private val db = writableDatabase

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE servers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                port INTEGER NOT NULL DEFAULT 21,
                username TEXT NOT NULL DEFAULT 'anonymous',
                password TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS servers")
        onCreate(db)
    }

    fun insert(host: String, name: String, port: Int, username: String, password: String) {
        val uniqueName = uniqueName(name)
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO servers (host, name, port, username, password, sort_order) VALUES (?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order),0)+1 FROM servers))",
            arrayOf(host, uniqueName, port, username, password)
        )
    }

    fun delete(host: String) =
        writableDatabase.execSQL("DELETE FROM servers WHERE host=?", arrayOf(host))

    fun rename(host: String, name: String) =
        writableDatabase.execSQL("UPDATE servers SET name=? WHERE host=?", arrayOf(name, host))

    fun update(host: String, newHost: String, name: String, port: Int, username: String, password: String) =
        writableDatabase.execSQL(
            "UPDATE servers SET host=?, name=?, port=?, username=?, password=? WHERE host=?",
            arrayOf(newHost, name, port, username, password, host)
        )

    fun update(host: String, port: Int, username: String, password: String) =
        writableDatabase.execSQL(
            "UPDATE servers SET port=?, username=?, password=? WHERE host=?",
            arrayOf(port, username, password, host)
        )

    fun reorder(hosts: List<String>) {
        writableDatabase.execSQL("UPDATE servers SET sort_order=-1")
        hosts.forEachIndexed { i, host ->
            writableDatabase.execSQL("UPDATE servers SET sort_order=? WHERE host=?", arrayOf(i, host))
        }
        writableDatabase.execSQL("DELETE FROM servers WHERE sort_order=-1")
    }

    fun getAll(): List<FtpServer> {
        val list = mutableListOf<FtpServer>()
        readableDatabase.rawQuery(
            "SELECT host, name, port, username, password, sort_order FROM servers ORDER BY sort_order",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(FtpServer(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getInt(5)))
            }
        }
        return list
    }

    fun findByHost(host: String): FtpServer? {
        readableDatabase.rawQuery(
            "SELECT host, name, port, username, password, sort_order FROM servers WHERE host=?",
            arrayOf(host)
        ).use { c ->
            if (c.moveToFirst()) {
                return FtpServer(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getInt(5))
            }
        }
        return null
    }

    private fun uniqueName(base: String): String {
        val names = getAll().map { it.name }.toSet()
        if (base !in names) return base
        var i = 1
        while ("$base ($i)" in names) i++
        return "$base ($i)"
    }
}
