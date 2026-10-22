package app.deixis.desktop

import app.deixis.core.SqlSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** SQLite через sqlite-jdbc для модулей MyBible; только чтение. */
class JdbcSql(file: File) : SqlSource {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}?open_mode=1")

    override fun query(sql: String, vararg args: Any): List<List<Any?>> = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }
    }

    override fun close() = conn.close()
}
