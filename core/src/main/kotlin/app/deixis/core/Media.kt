package app.deixis.core

/** Что за файл, по расширению. Одинаково решают телефон и компьютер. */
enum class MediaKind { IMAGE, VIDEO, PDF, PRESENTATION, TEXT }

private val kinds: Map<String, Pair<MediaKind, String>> = buildMap {
    fun put(kind: MediaKind, mime: String, vararg ext: String) = ext.forEach { put(it, kind to mime) }
    put(MediaKind.IMAGE, "image/jpeg", "jpg", "jpeg")
    put(MediaKind.IMAGE, "image/png", "png")
    put(MediaKind.IMAGE, "image/webp", "webp")
    put(MediaKind.IMAGE, "image/gif", "gif")
    put(MediaKind.IMAGE, "image/bmp", "bmp")
    put(MediaKind.IMAGE, "image/heic", "heic", "heif")
    put(MediaKind.VIDEO, "video/mp4", "mp4", "m4v")
    put(MediaKind.VIDEO, "video/x-matroska", "mkv")
    put(MediaKind.VIDEO, "video/quicktime", "mov")
    put(MediaKind.VIDEO, "video/x-msvideo", "avi")
    put(MediaKind.VIDEO, "video/x-ms-wmv", "wmv")
    put(MediaKind.VIDEO, "video/webm", "webm")
    put(MediaKind.VIDEO, "video/x-flv", "flv")
    put(MediaKind.VIDEO, "video/mp2t", "ts", "m2ts", "mts")
    put(MediaKind.VIDEO, "video/mpeg", "mpg", "mpeg", "vob")
    put(MediaKind.VIDEO, "video/3gpp", "3gp")
    put(MediaKind.VIDEO, "video/ogg", "ogv")
    // Аудио тоже играет VLC; на экране при этом чёрный фон.
    put(MediaKind.VIDEO, "audio/mpeg", "mp3")
    put(MediaKind.VIDEO, "audio/aac", "aac", "m4a")
    put(MediaKind.VIDEO, "audio/flac", "flac")
    put(MediaKind.VIDEO, "audio/wav", "wav")
    put(MediaKind.VIDEO, "audio/ogg", "ogg", "opus")
    put(MediaKind.PDF, "application/pdf", "pdf")
    put(MediaKind.PRESENTATION, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx")
    put(MediaKind.PRESENTATION, "application/vnd.ms-powerpoint", "ppt")
    put(MediaKind.PRESENTATION, "application/vnd.oasis.opendocument.presentation", "odp")
    put(MediaKind.PRESENTATION, "application/x-iwork-keynote-sffkey", "key")
    put(MediaKind.TEXT, "text/plain", "txt")
}

fun mediaKindOf(fileName: String): MediaKind? = kinds[fileName.substringAfterLast('.', "").lowercase()]?.first

fun mimeOf(fileName: String): String = kinds[fileName.substringAfterLast('.', "").lowercase()]?.second ?: "application/octet-stream"
