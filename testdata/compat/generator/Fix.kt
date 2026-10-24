package app.deixis.phone

import app.deixis.core.DeixisJson
import app.deixis.core.FileRef
import app.deixis.core.ScriptureOptions
import app.deixis.core.TextStyle
import app.deixis.core.Pairing
import app.deixis.core.FileTokenStore
import java.io.File
import app.deixis.desktop.with

/** Образец библиотеки, записанный кодом версии __VERSION__. */
fun main(args: Array<String>) {
    val pdf = FileRef("a".repeat(64), "Лекция.pdf", 123456, "application/pdf")
    val style = TextStyle(background = 0xFF18233A, color = 0xFFF4EFE6, sizePercent = 14f, caps = true)
    val items = listOf(
        LibItem("p1", Kind.PRESENTATION, "Годовой отчёт", uri = "content://media/1", mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation", size = 2048000, pages = 12, created = 1700000000000),
        LibItem("v1", Kind.VIDEO, "Заставка", uri = "content://media/2", mime = "video/mp4", size = 99000000, durationMs = 61000, created = 1700000000001),
        LibItem("f1", Kind.PHOTO, "Фото группы", uri = "content://media/3", mime = "image/jpeg", size = 3000000, created = 1700000000002),
        LibItem("d1", Kind.FILE, "Программа", uri = "content://media/4", mime = "application/pdf", size = 123456, pages = 3, created = 1700000000003),
        LibItem("s1", Kind.SONG, "Великий Бог", slides = listOf("Великий Бог, когда на мир смотрю я", "Тогда поёт душа моя"), labels = listOf("Куплет 1", "Припев"), style = style, created = 1700000000004),
        LibItem("t1", Kind.TEXT, "Объявления", slides = listOf("Чай после собрания", "Сбор в субботу"), created = 1700000000005),
        LibItem("b1", Kind.BIBLE, "Ин 3:16-18", bible = BiblePick(43, 3, 16, 18, listOf("RST", "KJV"), ScriptureOptions(versesPerSlide = 2, sideBySide = true)), created = 1700000000006),
    )
    val data = StoreData(
        items = items,
        setlists = listOf(Setlist("set1", "Воскресенье", "2026-10-25", listOf("s1", "b1", "p1", "v1")), Setlist("set2", "Пара", "2026-09-01", listOf("d1"))),
        active = "set1",
        recent = listOf("s1", "p1"),
        styles = mapOf("all" to style, "item:t1" to style.copy(caps = false)),
        settings = AppSettings(theme = "dark", alwaysPreview = true, caps = true, church = true),
        hashes = mapOf("p1" to FileRef("b".repeat(64), "Годовой отчёт.pptx", 2048000, "application/vnd.openxmlformats-officedocument.presentationml.presentation")),
        conversions = mapOf("p1" to Conversion("desk-1", pdf, 12)),
        themes = listOf(Theme("my1", "Моя тема", style.copy(name = "Моя тема", backgroundImage = FileRef("c".repeat(64), "фон.jpg", 50000, "image/jpeg")))), localFiles = mapOf("c".repeat(64) to "content://media/9"), demoSeeded = true,
    )
    val out = File(args[0]).apply { mkdirs() }
    File(out, "library-__VERSION__.json").writeText(DeixisJson.encodeToString(StoreData.serializer(), data))
    // Сопряжённые пульты приёмника.
    val tokens = File(out, "tokens-__VERSION__.json").apply { delete() }
    val p = Pairing(FileTokenStore(tokens))
    p.pair(p.pin, "Телефон ведущего")
    // Мониторы компьютера (появились в 0.9).
    val mon = app.deixis.desktop.MonitorSettings().with(app.deixis.core.MonitorRequest("\\.\\DISPLAY2", true, app.deixis.core.OutputLayout.STAGE))
        .with(app.deixis.core.MonitorRequest("\\.\\DISPLAY1", false, app.deixis.core.OutputLayout.HALL))
    app.deixis.desktop.MonitorSettingsStore(File(out, "monitors-__VERSION__.json")).save(mon)
}
