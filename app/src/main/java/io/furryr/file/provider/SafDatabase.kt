package io.furryr.file.provider

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SafLocation(
    val hexId: String,
    val name: String,
    val treeUri: String,
    val sortOrder: Int,
)

class SafDatabase(context: Context) : SQLiteOpenHelper(context, "saf_locations.db", null, 2) {
    private val db = writableDatabase

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hex_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                tree_uri TEXT NOT NULL UNIQUE,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("DROP TABLE IF EXISTS locations")
            onCreate(db)
        }
    }

    private fun generateHexId(): String {
        val chars = "0123456789ABCDEF"
        while (true) {
            val id = (1..4).map { chars.random() }.joinToString("") + "-" +
                     (1..4).map { chars.random() }.joinToString("")
            if (!hexIdExists(id)) return id
        }
    }

    private fun hexIdExists(hexId: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT 1 FROM locations WHERE hex_id=?", arrayOf(hexId))
        val exists = c.moveToFirst()
        c.close()
        return exists
    }

    fun insert(treeUri: String): String {
        val name = guessName(treeUri)
        val uniqueName = uniqueName(name)
        val hexId = generateHexId()
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO locations (hex_id, name, tree_uri, sort_order) VALUES (?, ?, ?, (SELECT COALESCE(MAX(sort_order),0)+1 FROM locations))",
            arrayOf(hexId, uniqueName, treeUri)
        )
        return hexId
    }

    fun delete(hexId: String) =
        writableDatabase.execSQL("DELETE FROM locations WHERE hex_id=?", arrayOf(hexId))

    fun rename(hexId: String, name: String) =
        writableDatabase.execSQL("UPDATE locations SET name=? WHERE hex_id=?", arrayOf(name, hexId))

    fun reorder(hexIds: List<String>) {
        writableDatabase.execSQL("UPDATE locations SET sort_order=-1")
        hexIds.forEachIndexed { i, id ->
            writableDatabase.execSQL("UPDATE locations SET sort_order=? WHERE hex_id=?", arrayOf(i, id))
        }
        writableDatabase.execSQL("DELETE FROM locations WHERE sort_order=-1")
    }

    fun getAll(): List<SafLocation> {
        val list = mutableListOf<SafLocation>()
        readableDatabase.rawQuery("SELECT hex_id, name, tree_uri, sort_order FROM locations ORDER BY sort_order", null).use { c ->
            while (c.moveToNext()) list.add(SafLocation(c.getString(0), c.getString(1), c.getString(2), c.getInt(3)))
        }
        return list
    }

    fun findByHexId(hexId: String): SafLocation? {
        readableDatabase.rawQuery("SELECT hex_id, name, tree_uri, sort_order FROM locations WHERE hex_id=?", arrayOf(hexId)).use { c ->
            if (c.moveToFirst()) return SafLocation(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
        }
        return null
    }

    private fun guessName(treeUri: String): String {
        val decoded = java.net.URLDecoder.decode(treeUri, "UTF-8")
        val lastSegment = decoded.substringAfterLast("primary:").substringAfterLast('%')
            .let { it.substringAfterLast('/') }.takeIf { it.isNotEmpty() }
        return lastSegment ?: "Location"
    }

    private fun uniqueName(base: String): String {
        val names = getAll().map { it.name }.toSet()
        if (base !in names) return base
        var i = 1
        while ("$base ($i)" in names) i++
        return "$base ($i)"
    }
}
