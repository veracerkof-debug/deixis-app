package app.deixis.core

/** Готовый слой «Поверх показа»: чтобы посмотреть, как работает слой, не набирая ничего. */
data class OverlaySample(val name: String, val overlays: Overlays)

/**
 * Примеры слоя со своими картинками (логотипы с прозрачным фоном рисует tools/overlay-samples.py).
 * Картинка становится файлом показа, как любой логотип: пульт передаёт её экрану.
 */
object OverlaySamples {
    /** Картинки примеров: имя ресурса → байты. */
    val images: List<String> = listOf("deixis.png", "live.png", "spring.png")

    fun bytes(name: String): ByteArray =
        OverlaySamples::class.java.getResourceAsStream("/overlay-samples/$name")?.use { it.readBytes() } ?: error("Нет картинки примера $name")

    fun ref(name: String): FileRef {
        val b = bytes(name)
        return FileRef(sha256Of(b.inputStream()), name, b.size.toLong(), "image/png")
    }

    /** Примеры; [logo] даёт файл картинки по имени (на телефоне — свой файл, на приёмнике — файл приёмника). */
    fun all(logo: (String) -> FileRef = ::ref): List<OverlaySample> = listOf(
        OverlaySample("Ведущий", Overlays(lowerThird = LowerThird("Анна Иванова", "Ведущая вечера"))),
        OverlaySample(
            "Гость",
            Overlays(lowerThird = LowerThird("Пётр Смирнов", "Архитектор, автор проекта парка", background = 0xE6182338, accent = 0xFFFFD43B)),
        ),
        OverlaySample(
            "Объявление",
            Overlays(ticker = Ticker("Сбор в субботу в 18:00 у главного входа  ·  После встречи — чай и выпечка  ·  Wi-Fi: Deixis-Guest")),
        ),
        OverlaySample(
            "Эфир",
            Overlays(logo = LogoOverlay(logo("live.png"), corner = Corner.TOP_RIGHT, sizePercent = 7f), clock = ClockOverlay(Corner.TOP_LEFT, seconds = true)),
        ),
        OverlaySample(
            "Всё сразу",
            Overlays(
                logo = LogoOverlay(logo("deixis.png"), corner = Corner.TOP_RIGHT, sizePercent = 8f, opacity = 0.9f),
                lowerThird = LowerThird("Весенний вечер", "Начало в 19:00"),
                ticker = Ticker("Добро пожаловать!  ·  Программа вечера — у входа  ·  Фото и видео можно"),
                clock = ClockOverlay(Corner.TOP_LEFT),
            ),
        ),
        OverlaySample(
            "Афиша",
            Overlays(logo = LogoOverlay(logo("spring.png"), corner = Corner.BOTTOM_RIGHT, sizePercent = 22f)),
        ),
    )
}
