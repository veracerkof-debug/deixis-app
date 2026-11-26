package app.deixis.core.effects

import app.deixis.core.Margins
import app.deixis.core.SceneBackground

/**
 * Связь сцены с текстом слайда. Сцены рисуются с образом сбоку, поэтому текст сдвигается в другую сторону:
 * поля текста берутся из положения главного образа (как в прототипе), а затемнение под текстом ложится по его реальному прямоугольнику.
 */
object SceneText {
    /** Доля ширины, которую занимает образ с запасом, в процентах. */
    const val SIDE_PERCENT = 36f
    const val EDGE_PERCENT = 6f

    /** Главный образ сцены: первая фигура с приоритетом 5 (или первая фигура вообще). */
    fun mainShape(scene: Scene): Layer? =
        scene.layers.firstOrNull { it.on && it.type == "shape" && it.prio == 5 } ?: scene.layers.firstOrNull { it.on && it.type == "shape" }

    /**
     * Поля для текста слайда: образ справа — текст слева, образ слева — текст справа, образ по центру или нет — без полей.
     * null — оставить поля оформления слайда как есть.
     */
    fun margins(scene: Scene): Margins? {
        val shape = mainShape(scene) ?: return null
        val x = shape.num("x")
        return when {
            shape.num("copies") > 1 && x > 0.4 && x < 0.6 -> null
            x >= 0.6 -> Margins(EDGE_PERCENT, 0f, SIDE_PERCENT, 0f)
            x <= 0.4 -> Margins(SIDE_PERCENT, 0f, EDGE_PERCENT, 0f)
            else -> null
        }
    }

    /** Оформление слайда с полями под образ сцены; поля темы, заданные автором, главнее. Без живого фона — как есть. */
    fun styled(style: app.deixis.core.TextStyle): app.deixis.core.TextStyle {
        val bg = style.backgroundScene ?: return style
        if (style.margins != null) return style
        val m = sceneFor(bg)?.let { margins(it) } ?: return style
        return style.copy(margins = m)
    }

    /** Сцена для живого фона слайда с поправками из [bg] (тише); null — такой сцены нет. */
    fun sceneFor(bg: SceneBackground): Scene? =
        if (bg.scene != null) custom(bg.scene) else if (bg.look in SceneLooks.IDS) SceneLooks.load(bg.look) else null

    private val customCache = object : LinkedHashMap<String, Scene?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Scene?>?) = size > 8
    }

    /** Своя сцена (правка человека или нейросети) идёт через ту же проверку, что и файл; негодная — null, экран не меняется. */
    @Synchronized
    private fun custom(json: String): Scene? = customCache.getOrPut(json) { SceneValidator.validate(json).scene }
}
