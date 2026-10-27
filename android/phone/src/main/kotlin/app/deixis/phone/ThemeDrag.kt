package app.deixis.phone

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.deixis.core.TextStyle
import kotlin.math.roundToInt

/**
 * Тему зажимают в панели и переносят, как в Holyrics и EasyWorship: на песню или документ в сетлисте —
 * тема всему элементу, на слайд — только ему, на окно «Сейчас» — тому, что в зале.
 */
class ThemeDrag {
    /** Что тащат и где палец (в координатах окна). */
    class Dragged(val name: String, val style: TextStyle, val at: Offset)

    var dragged by mutableStateOf<Dragged?>(null)
        private set

    /** Цель под пальцем: её подсвечивает рамка. */
    var hover by mutableStateOf<String?>(null)
        private set

    private class Target(val bounds: Rect, val drop: (TextStyle) -> Unit)
    private val targets = mutableMapOf<String, Target>()

    internal fun place(key: String, bounds: Rect, drop: (TextStyle) -> Unit) { targets[key] = Target(bounds, drop) }
    internal fun remove(key: String) { targets.remove(key) }

    internal fun start(name: String, style: TextStyle, at: Offset) { dragged = Dragged(name, style, at); hover = find(at) }
    internal fun move(at: Offset) { dragged = dragged?.let { Dragged(it.name, it.style, at) }; hover = find(at) }

    /** Отпустили: true — тему приняла цель под пальцем. */
    internal fun end(): Boolean {
        val d = dragged
        val key = d?.let { find(it.at) }
        dragged = null
        hover = null
        val target = key?.let { targets[it] } ?: return false
        target.drop(d.style)
        return true
    }

    internal fun cancel() { dragged = null; hover = null }

    // Самая маленькая из рамок под пальцем: слайд внутри песни важнее самой песни.
    private fun find(at: Offset): String? = targets.entries.filter { it.value.bounds.contains(at) }.minByOrNull { it.value.bounds.width * it.value.bounds.height }?.key
}

/** Сюда можно бросить тему: [onDrop] получает её оформление; пока тема над целью — рамка акцентом. */
fun Modifier.themeTarget(drag: ThemeDrag, key: String, onDrop: (TextStyle) -> Unit): Modifier = composed {
    DisposableEffect(key) { onDispose { drag.remove(key) } }
    val p = P.current
    this.onGloballyPositioned { drag.place(key, it.boundsInRoot(), onDrop) }
        .then(if (drag.hover == key) Modifier.border(T.outline, p.tint, RoundedCornerShape(T.rSlide)) else Modifier)
}

/**
 * Плитку темы зажимают и тащат. Отпустили над целью — тема ей; отпустили, почти не сдвинув, — [onHold]
 * (изменить тему), как обычное удержание.
 */
fun Modifier.themeSource(drag: ThemeDrag, name: String, style: TextStyle, onHold: (() -> Unit)?): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(0f) }
    this.onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(name, style) {
            detectDragGesturesAfterLongPress(
                onDragStart = { off -> moved = 0f; drag.start(name, style, origin + off) },
                onDrag = { change, amount ->
                    change.consume()
                    moved += amount.getDistance()
                    drag.move(origin + change.position)
                },
                onDragEnd = {
                    val taken = drag.end()
                    if (!taken && moved < viewConfiguration.touchSlop * 2) onHold?.invoke()
                },
                onDragCancel = { drag.cancel() },
            )
        }
}

/** Плитка темы под пальцем, поверх всего окна. */
@Composable
fun ThemeGhost(drag: ThemeDrag) {
    val d = drag.dragged ?: return
    val size = T.themeStrip
    val half = with(LocalDensity.current) { size.toPx() / 2 }
    Box(
        Modifier.offset { IntOffset((d.at.x - half).roundToInt(), (d.at.y - half * 1.4f).roundToInt()) }
            .width(size).alpha(GHOST_ALPHA),
    ) {
        SlideView(Slide.Text("Аа"), null, d.style, frame = Frame.PREVIEW_TINT)
    }
}

private const val GHOST_ALPHA = 0.85f
