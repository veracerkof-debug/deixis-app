package app.deixis.desktop

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Deixis для компьютера

  deixis [receive] [--name ИМЯ] [--port ПОРТ] [--screen N] [--windowed]
      Принимать показ на этом компьютере (по умолчанию — на последнем мониторе, во весь экран).
      Выход — Ctrl+Q.

  deixis list [--seconds N]
      Найти приёмники Deixis в сети.

  deixis send --to АДРЕС[:ПОРТ] [--pin PIN] ФАЙЛ [--page N] [--loop]
  deixis send --to АДРЕС[:ПОРТ] --text "ТЕКСТ" [--slide N]
  deixis send --to АДРЕС[:ПОРТ] --bible RST.SQLite3[,KJV.SQLite3] --ref "Ин 3:16-18"
               [--verses 1|2|3] [--ref-each] [--ref-top] [--ref-full] [--no-ref] [--side] [--slide N]
      Писание из модулей MyBible: один или два перевода.
  deixis send --to АДРЕС[:ПОРТ] --mode black|logo|clear|normal
  deixis send --to АДРЕС[:ПОРТ] --video play|pause|seek=МС|volume=0..200
      Показать файл, текст или переключить режим. Ключ --watch N печатает состояние N секунд.
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
            ),
        ).run()
        "send" -> exitProcess(SendCommand(opts, File(home, "sender-tokens.json")).run())
        "list" -> exitProcess(listReceivers(opts["seconds"]?.toLongOrNull() ?: 5))
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
