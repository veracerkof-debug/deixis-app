package app.deixis.desktop

import app.deixis.core.Discovery
import app.deixis.core.Dlna
import app.deixis.core.DlnaController
import app.deixis.core.DlnaFileServer
import app.deixis.core.mimeOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `deixis dlna`: показ на обычный телевизор с DLNA, без приложения Deixis на нём.
 * Запасной путь: фото и видео, без слайдов и режимов «Чёрный» и «Логотип».
 */
fun dlnaCommand(opts: Map<String, String>): Int = runBlocking {
    val found = Dlna.discover((opts["seconds"]?.toIntOrNull() ?: 3) * 1000)
    val path = opts["file"]
    if (path == null) {
        if (found.isEmpty()) println("DLNA-экраны не найдены") else found.forEach { println("${it.name}  ${it.host}") }
        return@runBlocking 0
    }
    val name = opts["to"]
    val target = found.firstOrNull { name == null || it.name.contains(name, ignoreCase = true) || it.host == name }
        ?: run { System.err.println("Не найден DLNA-экран «${name ?: ""}»"); return@runBlocking 1 }
    val file = File(path)
    if (!file.isFile) { System.err.println("Нет файла $path"); return@runBlocking 2 }
    val address = Discovery.localAddress()?.hostAddress ?: "127.0.0.1"
    val server = DlnaFileServer()
    server.start()
    DlnaController(target).use { tv ->
        val mime = mimeOf(file.name)
        tv.play(server.share(file, mime, address), file.nameWithoutExtension, mime)
        println("Показано на «${target.name}»")
        repeat(opts["watch"]?.toIntOrNull() ?: 5) {
            delay(1_000)
            val s = runCatching { tv.state() }.getOrNull()
            println(s?.let { "${if (it.playing) "идёт" else "стоит"} ${it.positionMs / 1000}/${it.durationMs / 1000} с" } ?: "нет ответа")
        }
    }
    server.stop()
    0
}
