package app.deixis.core.effects

/**
 * Энергия живого фона по подписи слайда песни («Куплет 1», «Припев», «Бридж»…): припев ярче куплета, бридж нарастает,
 * вступление и окончание тише. Подпись неизвестна — энергия, выбранная в настройках фона.
 */
object SectionEnergy {
    fun forLabel(label: String?): Double? {
        val l = label?.trim()?.lowercase() ?: return null
        return when {
            l.startsWith("припев") || l.startsWith("chorus") || l.startsWith("naqarot") -> 0.85
            l.startsWith("пред") || l.startsWith("pre") -> 0.6
            l.startsWith("бридж") || l.startsWith("мост") || l.startsWith("bridge") -> 0.7
            l.startsWith("куплет") || l.startsWith("verse") || l.startsWith("bayt") -> 0.45
            l.startsWith("вступ") || l.startsWith("intro") || l.startsWith("кода") || l.startsWith("оконч") || l.startsWith("outro") -> 0.3
            else -> null
        }
    }

    /** Оформление с энергией фона по подписи слайда; без живого фона или с неизвестной подписью — как есть. */
    fun styled(style: app.deixis.core.TextStyle, label: String?): app.deixis.core.TextStyle {
        val bg = style.backgroundScene ?: return style
        val e = forLabel(label) ?: return style
        return style.copy(backgroundScene = bg.copy(energy = e.toFloat()))
    }
}
