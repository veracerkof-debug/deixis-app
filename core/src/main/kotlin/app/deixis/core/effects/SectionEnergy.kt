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

    /** Кривая части: энергия в начале, в конце и за сколько секунд (как в `deixis-show`: e0, e1, d). null — подпись неизвестна. */
    class Ramp(val from: Double, val to: Double, val seconds: Double)

    fun rampForLabel(label: String?): Ramp? {
        val e = forLabel(label) ?: return null
        val l = label!!.trim().lowercase()
        return when {
            l.startsWith("припев") || l.startsWith("chorus") || l.startsWith("naqarot") -> Ramp(e - 0.1, e + 0.05, 20.0)
            l.startsWith("бридж") || l.startsWith("мост") || l.startsWith("bridge") -> Ramp(e - 0.2, e + 0.15, 25.0)
            l.startsWith("пред") || l.startsWith("pre") -> Ramp(e - 0.1, e + 0.2, 15.0)
            l.startsWith("куплет") || l.startsWith("verse") || l.startsWith("bayt") -> Ramp(e - 0.1, e + 0.05, 20.0)
            else -> Ramp(e, e + 0.1, 15.0)
        }
    }

    /** Оформление с энергией фона по подписи слайда; без живого фона или с неизвестной подписью — как есть. */
    fun styled(style: app.deixis.core.TextStyle, label: String?): app.deixis.core.TextStyle {
        val bg = style.backgroundScene ?: return style
        val r = rampForLabel(label) ?: return style
        return style.copy(backgroundScene = bg.copy(energy = r.from.coerceIn(0.0, 1.0).toFloat(), energyTo = r.to.coerceIn(0.0, 1.0).toFloat(), rampSec = r.seconds.toFloat()))
    }
}
