package app.deixis.desktop

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Deixis для компьютера

  deixis [receive] [--name ИМЯ] [--port ПОРТ] [--screen N] [--windowed] [--operator | --no-operator]
                  [--role remote|screen|both]
      Принимать показ на этом компьютере. При первом запуске — выбор: пульт, экран или и то и другое.
      Один монитор — зал на нём во весь экран; несколько — зал на всех, кроме монитора пульта, а пульт
      открывается окном браузера. Мониторы, раскладки (зал, сцена, титры, хромакей) и пульт — в значке
      Deixis и по правой кнопке мыши на экране. --screen N — зал только на мониторе N.
      Пульт — Ctrl+O (⌘O). Esc — зал в окно, F11 — снова во весь экран.
      Выход — Ctrl+Q, ⌘Q, Alt+F4 или «Выйти» в меню.

  deixis list [--seconds N]
      Найти приёмники Deixis в сети.

  deixis dlna [--to ИМЯ] [ФАЙЛ] [--watch N]
      Обычный телевизор с DLNA (без Deixis): без файла — список, с файлом — показать фото или видео.

  deixis send --to АДРЕС[:ПОРТ] [--pin PIN] ФАЙЛ [--page N] [--loop]
  deixis send --to АДРЕС[:ПОРТ] --text "ТЕКСТ" [--slide N] [--caps]
  deixis send --to АДРЕС[:ПОРТ] --bible RST.SQLite3[,KJV.SQLite3] --ref "Ин 3:16-18"
               [--verses 1|2|3] [--ref-each] [--ref-top] [--ref-full] [--no-ref] [--side] [--slide N] [--caps]
      Писание из модулей MyBible: один или два перевода.
  deixis send --to АДРЕС[:ПОРТ] --stream ЗАПИСЬ.h264 [--fps 30] [--seconds N]
      Трансляция записи H.264 так же, как телефон показывает свой экран (для проверки).
  deixis send --to АДРЕС[:ПОРТ] --mode black|logo|clear|normal
  deixis send --to АДРЕС[:ПОРТ] --video play|pause|seek=МС|volume=0..200
      Показать файл, текст или переключить режим. --caps — текст заглавными буквами.
      --theme тема.json — оформление из темы Holyrics, --background фон.jpg — картинка фона. Ключ --watch N печатает состояние N секунд.
"""

fun main(args: Array<String>) {
    val list = args.toMutableList()
    val command = if (list.isNotEmpty() && !list[0].startsWith("--")) list.removeAt(0) else "receive"
    val opts = parseOptions(list)
    val home = File(System.getProperty("user.home"), ".deixis")
    when (command) {
        "receive" -> ReceiverApp(
            ReceiverOptions(
                name = opts["name"],
                port = opts["port"]?.toIntOrNull() ?: app.deixis.core.ReceiverServer.DEFAULT_PORT,
                screen = opts["screen"]?.toIntOrNull(),
                windowed = "windowed" in opts,
                dataDir = opts["data"]?.let(::File) ?: File(home, "receiver"),
                snapshotDir = opts["snapshots"]?.let(::File),
                operator = when {
                    "no-operator" in opts -> false
                    "operator" in opts -> true
                    else -> null
                },
                role = when (opts["role"]) {
                    "remote" -> Role.REMOTE
                    "screen" -> Role.SCREEN
                    "both" -> Role.BOTH
                    else -> null
                },
                askRole = listOf("screen", "windowed", "operator", "no-operator").none { it in opts },
            ),
        ).run()
        "send" -> exitProcess(SendCommand(opts, File(home, "sender-tokens.json")).run())
        "list" -> exitProcess(listReceivers(opts["seconds"]?.toLongOrNull() ?: 5))
        "dlna" -> exitProcess(dlnaCommand(opts))
        "help", "--help", "-h" -> println(USAGE)
        else -> { System.err.println(USAGE); exitProcess(2) }
    }
}

/** `--key value`, `--flag` и позиционные аргументы (под ключом "file"). */
internal fun parseOptions(args: List<String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val next = args.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) { out[key] = next; i += 2 } else { out[key] = ""; i++ }
        } else {
            out["file"] = a
            i++
        }
    }
    return out
}
