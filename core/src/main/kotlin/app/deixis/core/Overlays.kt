package app.deixis.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** Угол экрана для логотипа и часов. */
@Serializable
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Поверх показа: логотип, плашка с именем, бегущая строка, часы. Слой не зависит от слайдов: смена
 * слайда, видео или PDF его не трогает. Каждый элемент — один на экран; null — не показан.
 */
@Serializable
data class Overlays(
    val logo: LogoOverlay? = null,
    val lowerThird: LowerThird? = null,
    val ticker: Ticker? = null,
    val clock: ClockOverlay? = null,
) {
    val isEmpty: Boolean get() = logo == null && lowerThird == null && ticker == null && clock == null

    /** Файлы слоя, которые должны быть на экране до показа. */
    val files: List<FileRef> get() = listOfNotNull(logo?.file)
}

/** Логотип в углу: картинка с прозрачностью (PNG). Высота — доля меньшей стороны экрана в процентах. */
@Serializable
data class LogoOverlay(
    val file: FileRef,
    val corner: Corner = Corner.TOP_RIGHT,
    val sizePercent: Float = 10f,
    val opacity: Float = 1f,
)

/** Плашка в нижней трети: имя и подпись (кто говорит, тема, название песни). */
@Serializable
data class LowerThird(
    val title: String,
    val subtitle: String? = null,
    val background: Long = 0xD9101418,
    val color: Long = 0xFFFFFFFF,
    /** Полоса слева у плашки; null — без полосы. */
    val accent: Long? = 0xFF3D8BFD,
)

/** Бегущая строка внизу экрана. Скорость — доля ширины экрана за секунду, в процентах. */
@Serializable
data class Ticker(
    val text: String,
    val speedPercent: Float = 10f,
    val background: Long = 0xD9101418,
    val color: Long = 0xFFFFFFFF,
)

/** Часы в углу: «14:05» или с секундами. */
@Serializable
data class ClockOverlay(
    val corner: Corner = Corner.TOP_LEFT,
    val seconds: Boolean = false,
    val color: Long = 0xFFFFFFFF,
)

/** Слой виден поверх обычного показа и «Очистить»; «Чёрный» и «Заставка» убирают и его. */
fun overlaysVisible(mode: Mode): Boolean = mode == Mode.NORMAL || mode == Mode.CLEAR

/** Прямоугольник на экране (в тех же единицах, что ширина и высота экрана). */
data class OverlayBox(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** Отступ слоя от краёв: безопасное поле экрана, как у текста слайдов. */
fun overlayMargin(width: Float, height: Float): Float = minOf(width, height) * SAFE_MARGIN

/** Размеры слоя — доли меньшей стороны экрана: на вертикальном экране слой не раздувается. */
private fun short(width: Float, height: Float) = minOf(width, height)

/** Высота полосы бегущей строки. */
fun tickerHeight(width: Float, height: Float): Float = short(width, height) * TICKER_HEIGHT

fun tickerBox(width: Float, height: Float): OverlayBox {
    val h = tickerHeight(width, height)
    return OverlayBox(0f, height - h, width, h)
}

/** Размер шрифта бегущей строки — по высоте полосы. */
fun tickerFont(width: Float, height: Float): Float = tickerHeight(width, height) * 0.55f

/**
 * Сдвиг текста бегущей строки в миг [now]: текст входит справа и уходит влево, затем снова.
 * [textWidth] — ширина строки, измеренная платформой. Возвращает x начала текста.
 */
fun tickerX(width: Float, textWidth: Float, speedPercent: Float, now: Long): Float {
    val speed = width * speedPercent.coerceIn(1f, 100f) / 100f // точек в секунду
    val path = width + textWidth
    if (path <= 0f) return width
    // В Double: миллисекунды с 1970 года не помещаются во Float без потери плавности.
    val travelled = (now / 1000.0 * speed % path).toFloat()
    return width - travelled
}

/** Шрифты плашки: имя и подпись, доли высоты экрана. */
fun lowerThirdTitleFont(width: Float, height: Float): Float = short(width, height) * 0.052f
fun lowerThirdSubtitleFont(width: Float, height: Float): Float = short(width, height) * 0.034f

/**
 * Плашка: прижата к левому полю внизу, над бегущей строкой, если та есть. Ширина — по тексту,
 * измеренному платформой ([textWidth] — ширина самой длинной строки), но не шире экрана без полей.
 */
fun lowerThirdBox(width: Float, height: Float, textWidth: Float, hasSubtitle: Boolean, aboveTicker: Boolean): OverlayBox {
    val m = overlayMargin(width, height)
    val pad = lowerThirdPad(width, height)
    val textH = lowerThirdTitleFont(width, height) * 1.2f + if (hasSubtitle) lowerThirdSubtitleFont(width, height) * 1.3f else 0f
    val h = textH + pad * 2
    val w = (textWidth + pad * 2 + lowerThirdAccent(width, height)).coerceAtMost(width - m * 2)
    val bottom = height - m - if (aboveTicker) tickerHeight(width, height) else 0f
    return OverlayBox(m, bottom - h, w, h)
}

/** Внутренний отступ плашки и толщина полосы слева. */
fun lowerThirdPad(width: Float, height: Float): Float = short(width, height) * 0.022f
fun lowerThirdAccent(width: Float, height: Float): Float = short(width, height) * 0.008f

/** Логотип: высота — доля меньшей стороны, ширина — по пропорциям картинки; в углу с полями. */
fun logoBox(width: Float, height: Float, imageW: Float, imageH: Float, o: LogoOverlay, aboveTicker: Boolean): OverlayBox {
    val h = short(width, height) * o.sizePercent.coerceIn(2f, 40f) / 100f
    val w = if (imageH > 0f) h * imageW / imageH else h
    return cornerBox(width, height, w, h, o.corner, aboveTicker)
}

/** Размер шрифта часов. */
fun clockFont(width: Float, height: Float): Float = short(width, height) * 0.05f

fun cornerBox(width: Float, height: Float, w: Float, h: Float, corner: Corner, aboveTicker: Boolean): OverlayBox {
    val m = overlayMargin(width, height)
    val bottomEdge = height - m - if (aboveTicker) tickerHeight(width, height) else 0f
    val x = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) m else width - m - w
    val y = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) m else bottomEdge - h
    return OverlayBox(x, y, w, h)
}

/** Время для часов по поясу устройства. */
fun clockText(now: Long, seconds: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(now).atZone(zone)
    val hm = "%d:%02d".format(t.hour, t.minute)
    return if (seconds) hm + ":%02d".format(t.second) else hm
}

private const val TICKER_HEIGHT = 0.075f

/** Элемент слоя и насколько он сейчас виден: 0 — ещё (или уже) нет, 1 — на месте. */
data class Shown<T>(val value: T, val presence: Float)

/** Что рисовать в миг кадра: элементы слоя, каждый со своей долей появления. */
data class OverlayFrame(
    val logo: Shown<LogoOverlay>? = null,
    val lowerThird: Shown<LowerThird>? = null,
    val ticker: Shown<Ticker>? = null,
    val clock: Shown<ClockOverlay>? = null,
    /** Кто-то ещё появляется или уходит: кадры нужны чаще. */
    val moving: Boolean = false,
) {
    val isEmpty: Boolean get() = logo == null && lowerThird == null && ticker == null && clock == null
}

/**
 * Движение слоя: элемент появляется плавно, уходит плавно, новая плашка вместо прежней въезжает заново.
 * Каждый экран ведёт его по своим часам с мига, когда получил слой. «Чёрный» и «Заставка» убирают слой
 * сразу ([update] с instant = true): это аварийные кнопки, ждать нельзя.
 */
class OverlayMotion(private val durationMs: Long = OVERLAY_MOTION_MS) {
    private class Track<T : Any> {
        var value: T? = null
        var visible = false
        var since = 0L
        var from = 0f

        fun set(next: T?, now: Long, duration: Long, instant: Boolean) {
            val p = presence(now, duration)
            when {
                instant -> { value = next; visible = next != null; since = now - duration; from = if (visible) 1f else 0f; if (next == null) value = null }
                next == null && visible -> { visible = false; since = now; from = p }
                next != null && !visible -> { value = next; visible = true; since = now; from = p }
                next != null && next != value -> {
                    // Плашка с другим именем въезжает заново; часы и логотип при смене угла — тоже.
                    value = next; since = now; from = 0f
                }
            }
        }

        fun presence(now: Long, duration: Long): Float {
            val t = ((now - since).toFloat() / duration).coerceIn(0f, 1f)
            return if (visible) from + (1f - from) * t else from * (1f - t)
        }

        fun shown(now: Long, duration: Long): Shown<T>? {
            val v = value ?: return null
            val p = presence(now, duration)
            if (!visible && p <= 0f) { value = null; return null }
            return Shown(v, easeOutCubic(p))
        }

        fun moving(now: Long, duration: Long) = value != null && now - since < duration
    }

    private val logo = Track<LogoOverlay>()
    private val lowerThird = Track<LowerThird>()
    private val ticker = Track<Ticker>()
    private val clock = Track<ClockOverlay>()

    @Synchronized
    fun update(o: Overlays, now: Long, instant: Boolean = false) {
        logo.set(o.logo, now, durationMs, instant)
        lowerThird.set(o.lowerThird, now, durationMs, instant)
        ticker.set(o.ticker, now, durationMs, instant)
        clock.set(o.clock, now, durationMs, instant)
    }

    @Synchronized
    fun frame(now: Long): OverlayFrame = OverlayFrame(
        logo.shown(now, durationMs),
        lowerThird.shown(now, durationMs),
        ticker.shown(now, durationMs),
        clock.shown(now, durationMs),
        moving = listOf(logo, lowerThird, ticker, clock).any { it.moving(now, durationMs) },
    )
}

/** Плавное торможение в конце: элемент быстро выходит и мягко встаёт на место. */
fun easeOutCubic(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u * u }

/** Сколько длится появление и уход элемента слоя. */
const val OVERLAY_MOTION_MS = 450L
