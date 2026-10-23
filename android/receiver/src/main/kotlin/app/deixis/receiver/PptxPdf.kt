package app.deixis.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.deixis.core.Box
import app.deixis.core.Crop
import app.deixis.core.DeckLine
import app.deixis.core.DeckPaint
import app.deixis.core.DeckPainter
import app.deixis.core.DeckRender
import app.deixis.core.DocConverter
import app.deixis.core.FontSpec
import app.deixis.core.Outline
import app.deixis.core.PathCmd
import app.deixis.core.PdfResult
import app.deixis.core.Pptx
import app.deixis.core.PptxException
import app.deixis.core.StoreException
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Встроенный перевод PPTX в PDF на Android: телефону и телевизору не нужен компьютер.
 * Шрифты Carlito, Caladea и Liberation Sans по метрикам совпадают с Calibri, Cambria и Arial,
 * поэтому строки переносятся там же, где в PowerPoint.
 */
object PptxPdf {

    /** Перевод для приёмника: PPTX, PPSX, POTX — сами; старые PPT, ODP, KEY — только компьютер. */
    fun converter(context: Context) = DocConverter { input, dir ->
        val ext = input.extension.lowercase()
        if (ext !in FORMATS) throw StoreException("convert", unsupported(ext))
        val out = File(dir, "out.pdf")
        val warnings = convert(context, input, out)
        PdfResult(out, warning(warnings))
    }

    val FORMATS = setOf("pptx", "ppsx", "potx", "pptm", "ppsm")

    fun unsupported(ext: String) = when (ext) {
        "ppt", "pps", "pot" -> "Старый формат PPT телефон не переводит: сохраните файл в PowerPoint как PPTX или подключите компьютер с Deixis"
        else -> "Формат ${ext.uppercase()} переводит компьютер с Deixis: сохраните файл как PPTX или PDF"
    }

    fun warning(warnings: List<String>): String? =
        if (warnings.isEmpty()) null else "Встроенный перевод. Приблизительно: " + warnings.joinToString("; ") + ". Точнее переводит PowerPoint или LibreOffice на компьютере"

    /** Возвращает предупреждения: что перенесено приблизительно. */
    fun convert(context: Context, input: File, output: File): List<String> {
        val deck = try { Pptx.read(input) } catch (e: PptxException) { throw StoreException("convert", e.message ?: "Не удалось открыть презентацию") }
            catch (e: Exception) { throw StoreException("convert", "«${input.name}» не открывается как презентация PowerPoint") }
        val fonts = Fonts(context)
        val pdf = PdfDocument()
        try {
            deck.slides.forEachIndexed { i, s ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(deck.width.toInt().coerceAtLeast(1), deck.height.toInt().coerceAtLeast(1), i + 1).create())
                val painter = AndroidPainter(page.canvas, fonts)
                DeckRender.paint(deck, s, painter)
                pdf.finishPage(page)
                painter.recycle()
            }
            output.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return deck.warnings
    }

    /** Шрифты с метриками офисных: загружаются один раз. */
    class Fonts(private val context: Context) {
        private val cache = mutableMapOf<Int, Typeface>()
        private fun res(id: Int) = cache.getOrPut(id) { runCatching { context.resources.getFont(id) }.getOrDefault(Typeface.DEFAULT) }

        /** Шрифт и нужен ли наклон вручную (у Liberation Sans и Caladea нет курсива в комплекте). */
        fun of(f: FontSpec): Pair<Typeface, Boolean> {
            val name = f.family.lowercase()
            return when {
                name.startsWith("calibri") || name.startsWith("carlito") -> res(
                    when { f.bold && f.italic -> R.font.carlito_bold_italic; f.bold -> R.font.carlito_bold; f.italic -> R.font.carlito_italic; else -> R.font.carlito_regular },
                ) to false
                name.startsWith("cambria") || name.startsWith("caladea") -> res(if (f.bold) R.font.caladea_bold else R.font.caladea_regular) to f.italic
                name.contains("times") || name.contains("georgia") || name.contains("garamond") || name.contains("serif") && !name.contains("sans") ->
                    Typeface.create(Typeface.SERIF, style(f)) to false
                name.contains("courier") || name.contains("consolas") || name.contains("mono") -> Typeface.create(Typeface.MONOSPACE, style(f)) to false
                else -> res(if (f.bold) R.font.liberation_sans_bold else R.font.liberation_sans_regular) to f.italic
            }
        }

        private fun style(f: FontSpec) = when { f.bold && f.italic -> Typeface.BOLD_ITALIC; f.bold -> Typeface.BOLD; f.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
    }

    private class AndroidPainter(private val c: Canvas, private val fonts: Fonts) : DeckPainter {
        private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bitmaps = mutableMapOf<String, Bitmap?>()

        private fun font(f: FontSpec): Paint {
            val (tf, skew) = fonts.of(f)
            text.typeface = tf
            text.textSize = f.size
            text.textSkewX = if (skew) -0.2f else 0f
            text.isFakeBoldText = false
            return text
        }

        override fun measure(text: String, font: FontSpec) = font(font).measureText(text)
        override fun ascent(font: FontSpec) = -font(font).fontMetrics.ascent
        override fun descent(font: FontSpec) = font(font).fontMetrics.descent

        override fun save() { c.save() }
        override fun restore() { c.restore() }
        override fun transform(degrees: Float, flipH: Boolean, flipV: Boolean, cx: Float, cy: Float) {
            c.rotate(degrees, cx, cy)
            c.scale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, cx, cy)
        }
        override fun clip(box: Box) { c.clipRect(box.x, box.y, box.x + box.w, box.y + box.h) }

        private fun path(o: Outline): Path = Path().apply {
            when (o) {
                is Outline.Rect -> addRect(o.box.x, o.box.y, o.box.x + o.box.w, o.box.y + o.box.h, Path.Direction.CW)
                is Outline.RoundRect -> addRoundRect(RectF(o.box.x, o.box.y, o.box.x + o.box.w, o.box.y + o.box.h), o.radius, o.radius, Path.Direction.CW)
                is Outline.Oval -> addOval(RectF(o.box.x, o.box.y, o.box.x + o.box.w, o.box.y + o.box.h), Path.Direction.CW)
                is Outline.Path -> for (cmd in o.cmds) when (cmd) {
                    is PathCmd.Move -> moveTo(cmd.x, cmd.y)
                    is PathCmd.Line -> lineTo(cmd.x, cmd.y)
                    is PathCmd.Cubic -> cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x, cmd.y)
                    PathCmd.Close -> close()
                }
            }
        }

        override fun fill(outline: Outline, paint: DeckPaint, box: Box) {
            fill.shader = null
            when (paint) {
                is DeckPaint.Solid -> fill.color = paint.argb
                is DeckPaint.Linear -> {
                    val a = Math.toRadians(paint.angle.toDouble())
                    val dx = cos(a).toFloat() * box.w / 2
                    val dy = sin(a).toFloat() * box.h / 2
                    fill.color = paint.stops.first().second
                    fill.shader = LinearGradient(
                        box.cx - dx, box.cy - dy, box.cx + dx, box.cy + dy,
                        paint.stops.map { it.second }.toIntArray(), paint.stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP,
                    )
                }
                is DeckPaint.Picture -> return
            }
            c.drawPath(path(outline), fill)
            fill.shader = null
        }

        override fun stroke(outline: Outline, line: DeckLine) {
            stroke.color = line.argb
            stroke.strokeWidth = max(line.width, 0.25f)
            stroke.pathEffect = if (line.dash) DashPathEffect(floatArrayOf(line.width * 3, line.width * 2), 0f) else null
            c.drawPath(path(outline), stroke)
        }

        override fun image(bytes: ByteArray, key: String, dst: Box, crop: Crop, clip: Outline?) {
            val bmp = bitmaps.getOrPut(key) { decode(bytes, dst) } ?: return
            val src = Rect(
                (crop.l * bmp.width).toInt(), (crop.t * bmp.height).toInt(),
                (bmp.width - crop.r * bmp.width).toInt().coerceAtLeast(1), (bmp.height - crop.b * bmp.height).toInt().coerceAtLeast(1),
            )
            c.save()
            clip?.let { c.clipPath(path(it)) }
            c.drawBitmap(bmp, src, RectF(dst.x, dst.y, dst.x + dst.w, dst.y + dst.h), fill.apply { shader = null; color = -1 })
            c.restore()
        }

        /** Картинка не крупнее, чем нужно для экрана: 2 точки на пункт, до 2560 по стороне. */
        private fun decode(bytes: ByteArray, dst: Box): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val want = (max(dst.w, dst.h) * 2).coerceIn(256f, 2560f)
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= want) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()

        override fun text(text: String, x: Float, baseline: Float, font: FontSpec, argb: Int, underline: Boolean, strike: Boolean) {
            val p = font(font)
            p.color = argb
            p.isUnderlineText = underline
            p.isStrikeThruText = strike
            c.drawText(text, x, baseline, p)
            p.isUnderlineText = false
            p.isStrikeThruText = false
        }

        fun recycle() { bitmaps.values.forEach { it?.recycle() }; bitmaps.clear() }
    }
}
