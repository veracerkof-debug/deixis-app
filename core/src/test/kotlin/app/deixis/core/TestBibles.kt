package app.deixis.core

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** SQLite через sqlite-jdbc — как на компьютере. */
class JdbcSql(file: File) : SqlSource {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    override fun query(sql: String, vararg args: Any): List<List<Any?>> = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }
    }
    override fun close() = conn.close()
}

/** Маленькие модули в формате MyBible для тестов: Синодальный перевод и KJV (оба — общественное достояние). */
object TestBibles {
    fun create(dir: File, name: String, description: String, books: List<Triple<Int, String, String>>, verses: List<List<Any>>): File {
        val f = File(dir, name)
        DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE info (name TEXT, value TEXT)")
                it.execute("CREATE TABLE books (book_color TEXT, book_number NUMERIC, short_name TEXT, long_name TEXT)")
                it.execute("CREATE TABLE verses (book_number NUMERIC, chapter NUMERIC, verse NUMERIC, text TEXT)")
            }
            c.prepareStatement("INSERT INTO info VALUES (?, ?)").use { st ->
                listOf("description" to description, "language" to "ru").forEach { (k, v) -> st.setString(1, k); st.setString(2, v); st.executeUpdate() }
            }
            c.prepareStatement("INSERT INTO books VALUES ('#ccc', ?, ?, ?)").use { st ->
                books.forEach { (n, s, l) -> st.setInt(1, n); st.setString(2, s); st.setString(3, l); st.executeUpdate() }
            }
            c.prepareStatement("INSERT INTO verses VALUES (?, ?, ?, ?)").use { st ->
                verses.forEach { v -> v.forEachIndexed { i, x -> st.setObject(i + 1, x) }; st.executeUpdate() }
            }
        }
        return f
    }

    fun rst(dir: File) = create(
        dir, "RST.SQLite3", "Синодальный перевод",
        listOf(Triple(10, "Быт", "Бытие"), Triple(230, "Пс", "Псалтирь"), Triple(500, "Ин", "От Иоанна"), Triple(530, "1Кор", "1-е Коринфянам")),
        listOf(
            listOf(500, 3, 16, "Ибо так возлюбил Бог<S>2316</S> мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него, не погиб, но имел жизнь вечную.<f>[1]</f>"),
            listOf(500, 3, 17, "Ибо не послал Бог Сына Своего в мир, чтобы судить мир, но чтобы мир спасен был чрез Него."),
            listOf(500, 3, 18, "Верующий в Него не судится, а неверующий уже осужден, потому что не уверовал во имя Единородного Сына Божия.<pb/>"),
            listOf(230, 22, 1, "<t>Господь — Пастырь мой;</t> я ни в чем не буду нуждаться:"),
            listOf(530, 13, 4, "Любовь долготерпит, милосердствует, любовь не завидует, любовь не превозносится, не гордится,"),
        ),
    )

    fun kjv(dir: File) = create(
        dir, "KJV.SQLite3", "King James Version",
        listOf(Triple(10, "Gen", "Genesis"), Triple(500, "John", "John"), Triple(530, "1Cor", "1 Corinthians")),
        listOf(
            listOf(500, 3, 16, "For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have <i>everlasting</i> life."),
            listOf(500, 3, 17, "For God sent not his Son into the world to condemn the world; but that the world through him might be saved."),
            listOf(500, 3, 18, "<J>He that believeth on him is not condemned</J>: but he that believeth not is condemned already."),
        ),
    )
}
