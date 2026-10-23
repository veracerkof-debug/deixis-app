package app.deixis.phone

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.deixis.receiver.Didot

/** Цвета из DESIGN.md → «Цвет интерфейса». Других цветов в интерфейсе нет. */
data class Palette(
    val bg: Color, val cell: Color, val ink: Color, val l2: Color, val l3: Color,
    val tint: Color, val tintText: Color, val red: Color, val fill: Color,
    val live: Color = red, val gold: Color = Color(0xFFA87A00), val sep: Color = Color(0x263C372D), val fill2: Color = Color(0x3378736A),
    val dark: Boolean = false,
)

private val Light = Palette(
    Color(0xFFF3F2EE), Color(0xFFFFFFFF), Color(0xFF161514), Color(0xFF87847E), Color(0xFFC6C3BC),
    Color(0xFF9C7242), Color(0xFF765A38), Color(0xFFA75750), Color(0x1F78736A),
)
private val Dark = Palette(
    Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFFFFFFFF), Color(0xFF8D8D93), Color(0xFF48484A),
    Color(0xFFBC9361), Color(0xFFBC9361), Color(0xFFB96E65), Color(0x3D767680),
    gold = Color(0xFFFFD43B), sep = Color(0x8C545458), fill2 = Color(0x5C767680), dark = true,
)
private val Paper = Light.copy(bg = Color(0xFFF4EFE6), cell = Color(0xFFFBF8F2), ink = Color(0xFF2B2620))
private val Oled = Dark.copy(bg = Color(0xFF000000), cell = Color(0xFF0E0E10))

fun paletteOf(theme: String): Palette = when (theme) {
    "dark" -> Dark
    "paper" -> Paper
    "oled" -> Oled
    else -> Light
}

internal val P = staticCompositionLocalOf { Light }

/** Размеры, скругления и движение из DESIGN.md. */
object T {
    val gutter = 16.dp
    val gap = 10.dp
    val gapS = 6.dp
    val gapXs = 2.dp
    val rowPadV = 11.dp
    val rowMin = 44.dp
    val iconBtn = 44.dp
    val icon = 22.dp
    val iconS = 17.dp
    val iconBar = 20.dp
    val kind = 28.dp
    val pill = 36.dp
    val pillPad = 14.dp
    val btn = 50.dp
    val btnHero = 64.dp
    val barBtn = 50.dp
    val tileW = 116.dp
    val tileH = 84.dp
    val tilePad = 12.dp
    val dev = 36.dp
    val search = 40.dp
    val segMin = 32.dp
    val switchW = 51.dp
    val switchH = 31.dp
    val knob = 27.dp
    val swatch = 30.dp
    val stepperW = 44.dp
    val grabW = 36.dp
    val grabH = 5.dp
    val outline = 2.dp
    val hair = 0.5.dp
    val qr = 148.dp
    val qrBig = 220.dp
    val tabMin = 50.dp
    val topMin = 56.dp
    val numCell = 52.dp

    val rCard = 12.dp
    val rBtn = 14.dp
    val rSeg = 9.dp
    val rSegIn = 7.dp
    val rSlide = 8.dp
    val rField = 10.dp
    val rPill = 999.dp

    val fTab = 11.sp
    val fNum = 11.sp
    val fTag = 12.sp
    val fBar = 12.sp
    val fLabel = 13.sp
    val fSub = 14.sp
    val fPill = 15.sp
    val fBody = 17.sp
    val fSheet = 20.sp
    val fMark = 22.sp
    val fTitle = 28.sp
    val fCode = 28.sp

    const val fast = 140
    const val mid = 240
    const val slow = 340
    val eOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val eIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val eSpring = CubicBezierEasing(0.3f, 1.35f, 0.55f, 1f)
    const val pressScale = 0.95f
    const val disabled = 0.4f
    const val scrim = 0.35f
}

// ——— Значки: те же контуры, что в прототипе (24×24, линия 1.7) ———

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 0f) =
    if (r == 0f) "M$x,${y}h${w}v${h}h${-w}z"
    else "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

/** Часть значка: контур, заливка или линия другой толщины. */
private class Part(val d: String, val fill: Boolean = false, val width: Float = 1.7f)

private val paths: Map<String, List<Part>> = mapOf(
    "show" to listOf(Part("M3 5h18v11H3z"), Part("M8 20h8M12 16v4")),
    "lib" to listOf(Part("M4 4h4v16H4zM10 4h4v16h-4z"), Part("M16.5 4.5l3.5 1-4 15-3.5-1z")),
    "list" to listOf(Part("M8 6h12M8 12h12M8 18h12"), Part(circle(4f, 6f, 1f)), Part(circle(4f, 12f, 1f)), Part(circle(4f, 18f, 1f))),
    "devs" to listOf(Part(rect(2f, 4f, 14f, 10f, 1.5f)), Part(rect(17f, 8f, 5f, 12f, 1f)), Part("M6 18h6")),
    "gear" to listOf(
        Part(circle(12f, 12f, 3f)),
        Part("M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"),
    ),
    "chev" to listOf(Part("M9 6l6 6-6 6")),
    "down" to listOf(Part("M6 9l6 6 6-6")),
    "close" to listOf(Part("M6 6l12 12M18 6L6 18")),
    "search" to listOf(Part(circle(11f, 11f, 7f)), Part("M20 20l-3.5-3.5")),
    "plus" to listOf(Part("M12 5v14M5 12h14")),
    "more" to listOf(Part(circle(5f, 12f, 1f)), Part(circle(12f, 12f, 1f)), Part(circle(19f, 12f, 1f))),
    "black" to listOf(Part(rect(4f, 5f, 16f, 14f, 2f), fill = true)),
    "logo" to listOf(Part(circle(12f, 12f, 8f)), Part("M12 7v10M8 11h8")),
    "clear" to listOf(Part("M4 20h16"), Part("M14 4l6 6-8 8H6v-6z")),
    "text" to listOf(Part("M5 6V4h14v2M12 4v16M9 20h6")),
    "brush" to listOf(Part("M14 4l6 6-9 9H5v-6z"), Part("M12 6l6 6")),
    "song" to listOf(Part("M9 18V5l11-2v13"), Part(circle(6f, 18f, 3f)), Part(circle(17f, 16f, 3f))),
    "bible" to listOf(Part("M5 4h12a2 2 0 0 1 2 2v14H7a2 2 0 0 1-2-2z"), Part("M12 8v6M9.5 10.5h5")),
    "file" to listOf(Part("M14 3H6v18h12V7z"), Part("M14 3v4h4")),
    "media" to listOf(Part(rect(3f, 5f, 18f, 14f, 2f)), Part("M10 9l5 3-5 3z")),
    "proj" to listOf(Part(rect(2f, 8f, 20f, 9f, 2f)), Part(circle(16f, 12.5f, 2.5f)), Part("M6 12.5h4")),
    "tv" to listOf(Part(rect(3f, 4f, 18f, 12f, 1.5f)), Part("M8 20h8")),
    "cast" to listOf(Part("M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9a12 12 0 0 1 12 12"), Part("M3 6V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4")),
    "people" to listOf(Part(circle(9f, 8f, 3.5f)), Part("M2.5 20a6.5 6.5 0 0 1 13 0"), Part("M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6.5 6.5 0 0 1 3.5 6")),
    "wifi" to listOf(Part("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0"), Part(circle(12f, 19.5f, 0.8f))),
    "up" to listOf(Part("M12 19V5M6 11l6-6 6 6")),
    "pres" to listOf(Part(rect(3f, 4f, 18f, 12f, 1.5f)), Part("M12 16v4M8 20h8M7 12l3-3 2 2 4-4")),
    "photo" to listOf(Part(rect(3f, 5f, 18f, 14f, 2f)), Part(circle(9f, 10f, 2f)), Part("M21 16l-5-5-8 8")),
    "phone" to listOf(Part(rect(7f, 2f, 10f, 20f, 2f)), Part("M11 18h2")),
    "board" to listOf(Part(rect(2f, 3f, 20f, 13f, 1.5f)), Part("M7 21l2-5M17 21l-2-5M6 8h6M6 11h9")),
    "laptop" to listOf(Part(rect(4f, 5f, 16f, 10f, 1.5f)), Part("M2 19h20")),
    "prev" to listOf(Part("M15 6l-6 6 6 6")),
    "next" to listOf(Part("M9 6l6 6-6 6")),
    "play" to listOf(Part("M7 5l12 7-12 7z", fill = true)),
    "pause" to listOf(Part("M8 5v14M16 5v14", width = 3f)),
    "back10" to listOf(Part("M4 12a8 8 0 1 0 3-6.2"), Part("M4 4v4h4")),
    "fwd10" to listOf(Part("M20 12a8 8 0 1 1-3-6.2"), Part("M20 4v4h-4")),
    "loop" to listOf(Part("M17 2l3 3-3 3"), Part("M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3"), Part("M20 13v2a4 4 0 0 1-4 4H4")),
    "vol" to listOf(Part("M4 9h4l5-4v14l-5-4H4z"), Part("M16.5 8.5a5 5 0 0 1 0 7")),
    "stop" to listOf(Part(rect(6f, 6f, 12f, 12f, 1.5f), fill = true)),
    "trash" to listOf(Part("M4 7h16M10 11v6M14 11v6"), Part("M6 7l1 13h10l1-13M9 7V4h6v3")),
    "edit" to listOf(Part("M4 20h4L19 9l-4-4L4 16z"), Part("M13 7l4 4")),
)

private val vectors = mutableMapOf<String, ImageVector>()

fun icon(name: String): ImageVector = vectors.getOrPut(name) {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (part in paths[name] ?: paths.getValue("file")) {
        b.addPath(
            pathData = addPathNodes(part.d),
            fill = if (part.fill) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = part.width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    b.build()
}

@Composable
fun Icon(name: String, color: Color = P.current.ink, size: Dp = T.icon, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(icon(name)), null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

// ——— Элементы ———

/** Нажатие с лёгким отскоком (--e-spring). */
@Composable
fun Modifier.press(enabled: Boolean = true, label: String? = null, scaleTo: Float = T.pressScale, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, tween(T.fast, easing = T.eSpring), label = "press")
    return this.scale(s)
        .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

@Composable
fun Txt(
    text: String, size: androidx.compose.ui.unit.TextUnit = T.fBody, color: Color = P.current.ink, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE, align: androidx.compose.ui.text.style.TextAlign? = null,
) {
    BasicText(
        text, modifier,
        style = TextStyle(fontSize = size, color = color, fontWeight = weight, letterSpacing = (-0.01).em, textAlign = align ?: androidx.compose.ui.text.style.TextAlign.Unspecified),
        maxLines = lines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Mark(size: androidx.compose.ui.unit.TextUnit = T.fMark, color: Color = P.current.ink) {
    BasicText("ΔΕΙΞΙΣ", style = TextStyle(fontFamily = Didot, fontSize = size, letterSpacing = 0.18.em, color = color), maxLines = 1)
}

@Composable
fun IconBtn(name: String, label: String, color: Color = P.current.ink, onClick: () -> Unit) {
    Box(Modifier.size(T.iconBtn).clip(RoundedCornerShape(T.rPill)).press(label = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(name, color)
    }
}

@Composable
fun Pill(text: String, icon: String? = null, tint: Boolean = false, label: String = text, onClick: () -> Unit) {
    val p = P.current
    Row(
        Modifier.height(T.pill).clip(RoundedCornerShape(T.rPill)).background(if (tint) p.tint else p.fill).press(label = label, onClick = onClick)
            .padding(horizontal = T.pillPad),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gapS),
    ) {
        icon?.let { Icon(it, if (tint) Color.White else p.ink, T.iconBar) }
        if (text.isNotEmpty()) Txt(text, T.fPill, if (tint) Color.White else p.ink, FontWeight.SemiBold, lines = 1)
    }
}

enum class BtnKind { PLAIN, TINT, RED, DANGER }

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, icon: String? = null, kind: BtnKind = BtnKind.PLAIN, enabled: Boolean = true, height: Dp = T.btn, onClick: () -> Unit) {
    val p = P.current
    val bg = when (kind) { BtnKind.TINT -> p.tint; BtnKind.DANGER -> p.red; else -> p.fill }
    val fg = when (kind) { BtnKind.TINT, BtnKind.DANGER -> Color.White; BtnKind.RED -> p.red; else -> p.ink }
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(T.rBtn)).background(bg)
            .then(if (enabled) Modifier.press(scaleTo = 0.97f, label = text, onClick = onClick) else Modifier.alpha(T.disabled)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, fg); Spacer(Modifier.width(T.gapS)) }
        Txt(text, T.fBody, fg, FontWeight.SemiBold, lines = 1)
    }
}

@Composable
fun GHead(text: String) {
    Txt(text.uppercase(), T.fLabel, P.current.l2, modifier = Modifier.padding(start = T.gutter, bottom = T.gapS))
}

/** Группа ячеек на тёплом фоне, ячейки разделены тонкой линией. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 20.dp).clip(RoundedCornerShape(T.rCard)).background(P.current.cell), content = content)
}

@Composable
fun Sep(indent: Dp = T.numCell) {
    Box(Modifier.padding(start = indent).fillMaxWidth().height(T.hair).background(P.current.sep))
}

@Composable
fun Kind(name: String) {
    val p = P.current
    Box(Modifier.size(T.kind).clip(RoundedCornerShape(T.rSlide)).background(p.fill), contentAlignment = Alignment.Center) { Icon(name, p.tintText, T.iconS) }
}

@Composable
fun Tag(text: String, live: Boolean) {
    val p = P.current
    Box(Modifier.clip(RoundedCornerShape(T.rPill)).background(if (live) p.live else p.fill2).padding(horizontal = 8.dp, vertical = T.gapXs)) {
        Txt(text, T.fTag, if (live) Color.White else p.tintText, FontWeight.SemiBold, lines = 1)
    }
}

/** Ячейка списка: значок вида, заголовок, подпись, хвост. */
@Composable
fun Cell(
    title: String, sub: String? = null, kind: String? = null, open: Boolean = false, chevron: String? = "chev",
    onLong: (() -> Unit)? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {},
) {
    val p = P.current
    val click = onClick
    Row(
        Modifier.fillMaxWidth().heightIn(min = T.rowMin).background(if (open) p.fill else Color.Transparent)
            .then(
                if (click != null || onLong != null) Modifier.combinedClick(onClick = click ?: {}, onLong = onLong) else Modifier,
            )
            .padding(horizontal = T.gutter, vertical = T.rowPadV),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        kind?.let { Kind(it) }
        Column(Modifier.weight(1f)) {
            Txt(title, lines = 1)
            sub?.takeIf { it.isNotBlank() }?.let { Txt(it, T.fSub, p.l2, lines = 1) }
        }
        trailing()
        if (chevron != null && click != null) Icon(chevron, p.l3)
    }
}

fun Modifier.combinedClick(onClick: () -> Unit, onLong: (() -> Unit)? = null, onDouble: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onLongClick = onLong, onDoubleClick = onDouble, onClick = onClick)

/** Сегментный переключатель. */
@Composable
fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, scroll: Boolean = false, onSelect: (Int) -> Unit) {
    val p = P.current
    Row(
        modifier.clip(RoundedCornerShape(T.rSeg)).background(p.fill).padding(T.gapXs)
            .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(T.gapXs),
    ) {
        options.forEachIndexed { i, t ->
            Box(
                Modifier.then(if (scroll) Modifier else Modifier.weight(1f)).heightIn(min = T.segMin).clip(RoundedCornerShape(T.rSegIn))
                    .background(if (i == selected) p.cell else Color.Transparent).clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = T.gap),
                contentAlignment = Alignment.Center,
            ) { Txt(t, T.fSub, p.ink, FontWeight.Medium, lines = 1) }
        }
    }
}

@Composable
fun SearchField(value: String, placeholder: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val p = P.current
    Row(
        modifier.fillMaxWidth().height(T.search).clip(RoundedCornerShape(T.rField)).background(p.fill).padding(horizontal = T.gap),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon("search", p.l2, 18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Txt(placeholder, T.fBody, p.l2, lines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(fontSize = T.fBody, color = p.ink), cursorBrush = SolidColor(p.tint), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Box(Modifier.clickable { onChange("") }) { Icon("close", p.l2, 18.dp) }
    }
}

@Composable
fun Field(
    value: String, placeholder: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text,
    single: Boolean = true, code: Boolean = false, minHeight: Dp = T.btn, onChange: (String) -> Unit,
) {
    val p = P.current
    val style = TextStyle(
        fontSize = if (code) T.fCode else T.fBody, color = p.ink,
        letterSpacing = if (code) 0.2.em else (-0.01).em,
        textAlign = if (code) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
    )
    Box(
        modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(T.rCard)).background(p.cell).padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty()) BasicText(placeholder, Modifier.fillMaxWidth(), style = style.copy(color = p.l3))
        BasicTextField(
            value, onChange, singleLine = single, textStyle = style, cursorBrush = SolidColor(p.tint),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Тумблер в стиле iOS. */
@Composable
fun Switch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val p = P.current
    val x by animateFloatAsState(if (on) 1f else 0f, tween(T.mid, easing = T.eSpring), label = "switch")
    Box(
        Modifier.size(T.switchW, T.switchH).clip(RoundedCornerShape(T.rPill)).background(if (on) p.tint else p.fill2)
            .clickable(role = Role.Switch) { onChange(!on) }.semantics { contentDescription = label },
    ) {
        Box(
            Modifier.padding(T.gapXs).offset(x = (T.switchW - T.knob - T.gapXs * 2) * x).size(T.knob)
                .clip(RoundedCornerShape(T.rPill)).background(Color.White),
        )
    }
}

@Composable
fun SwitchRow(title: String, on: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Cell(title, sub, chevron = null) { Switch(on, title, onChange) }
}

@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = P.current
    Row(Modifier.clip(RoundedCornerShape(T.rSeg)).background(p.fill), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(T.stepperW, T.segMin).press(label = "Меньше", onClick = onMinus), contentAlignment = Alignment.Center) { Txt("−", T.fSheet) }
        Box(Modifier.width(T.segMin), contentAlignment = Alignment.Center) { Txt(value, T.fPill) }
        Box(Modifier.size(T.stepperW, T.segMin).press(label = "Больше", onClick = onPlus), contentAlignment = Alignment.Center) { Txt("+", T.fSheet) }
    }
}

@Composable
fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val p = P.current
    Box(
        Modifier.size(T.swatch + 8.dp).then(if (selected) Modifier.border(T.outline, p.tint, RoundedCornerShape(T.rPill)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(T.swatch).clip(RoundedCornerShape(T.rPill)).background(color).border(T.hair, p.sep, RoundedCornerShape(T.rPill)).press(label = label, onClick = onClick))
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Txt(text, T.fSub, P.current.l2, modifier = modifier.padding(start = 4.dp, end = 4.dp, top = T.gapS, bottom = T.gutter))
}
