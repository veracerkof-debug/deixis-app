package app.deixis.receiver

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.deixis.core.SqlSource
import java.io.File

/** SQLite Android для модулей MyBible, только чтение. */
class AndroidSql(file: File) : SqlSource {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    override fun query(sql: String, vararg args: Any): List<List<Any?>> =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { i -> value(c, i) })
            }
        }

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_STRING -> c.getString(i)
        else -> null
    }

    override fun close() = db.close()
}
