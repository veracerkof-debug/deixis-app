package app.deixis.core.effects

/**
 * Готовые сцены («Looks»), которые идут с приложением: файлы `effects/looks/<id>.deixis-scene.json` в ресурсах ядра.
 * Каждая проходит ту же проверку, что и файл от человека или нейросети (тест `SceneLooksTest`), и считается
 * образцом вкуса: тёмная подложка, гармоничные палитры, один главный образ, движение без мигания, текст читается.
 */
object SceneLooks {
    /** Порядок показа в интерфейсе: от универсальной к тематическим. */
    val IDS: List<String> = listOf("evening", "dawn", "deep", "joy", "glass", "bethlehem", "easter", "fire", "water", "word", "plain")

    private val cache = HashMap<String, Scene>()

    /** Прочитать сцену. Ошибка здесь — ошибка сборки (файл в ресурсах нарушает формат), а не рабочая ситуация. */
    @Synchronized
    fun load(id: String): Scene = cache.getOrPut(id) {
        val text = SceneLooks::class.java.getResourceAsStream("/effects/looks/$id.deixis-scene.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Нет готовой сцены «$id» в ресурсах")
        val report = SceneValidator.validate(text)
        report.scene ?: error("Готовая сцена «$id» не прошла проверку: ${report.errors.joinToString { "${it.path}: ${it.message}" }}")
    }

    fun all(): List<Scene> = IDS.map(::load)
}
