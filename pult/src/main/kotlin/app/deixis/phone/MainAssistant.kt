package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import app.deixis.core.SceneBackground
import app.deixis.core.SongSlide

/**
 * Помощник на виду: плашка на главном под окнами показа. Нажатие — выбор, что править (фон, песню в зале, сетлист);
 * дальше обычное окно помощника с «Принять». Во время показа плашка не мешает: она тихая, без звука и вспышек.
 */
@Composable
fun AssistantBar(c: Controller, ui: Ui) {
    val p = P.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = T.gutter, vertical = T.gapS).clip(RoundedCornerShape(T.rBtn))
            .background(p.tint.copy(alpha = T.devFill)).border(T.hair, p.tint, RoundedCornerShape(T.rBtn))
            .press(label = L.assistant, onClick = { ui.sheet(L.assistant) { AssistantChooser(c, ui) } })
            .padding(horizontal = T.gap, vertical = T.gap),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.gap),
    ) {
        Row(Modifier.size(T.dev).clip(RoundedCornerShape(T.rPill)).background(p.tint), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon("media", p.onTint, T.iconBar)
        }
        Column(Modifier.weight(1f)) {
            Txt(L.assistant, T.fBody, weight = FontWeight.SemiBold, lines = 1)
            Txt(L.assistantBarSub, T.fLabel, p.l2, lines = 1)
        }
        Icon("chev", p.tintText, T.iconS)
    }
}

/** Что правит помощник: фон по умолчанию, песня, которая сейчас в зале, или сетлист на главном. */
@Composable
fun AssistantChooser(c: Controller, ui: Ui) {
    val set by c.activeSet.collectAsState()
    val live by c.live.collectAsState()
    Note(L.assistantPrivacy, Modifier.padding(bottom = T.gap))
    Group {
        Cell(L.assistantBackground, L.assistantBackgroundSub, kind = "brush", onClick = {
            val now = c.defaultStyle()
            val start = now.backgroundScene ?: SceneBackground("evening")
            ui.push(L.assistantBackground) { AssistantSheet(c, ui, start) { next -> c.setDefaultStyle(c.defaultStyle().copy(backgroundScene = next)) } }
        })
        Sep(T.gutter)
        Cell(L.assistantSetlist, set?.title ?: L.assistantNoSetlist, kind = "list", onClick = {
            val s = c.data.value.setlists.firstOrNull { it.id == set?.id }
            if (s == null) ui.toast(L.assistantNoSetlist) else ui.push(L.assistantSetlist) { SetlistAssistantSheet(c, ui, s) }
        })
        Sep(T.gutter)
        val item = c.item(live?.source?.itemId)?.takeIf { it.kind == Kind.SONG || it.kind == Kind.TEXT }
        Cell(L.assistantSong, item?.title ?: L.assistantNoSong, kind = "song", onClick = {
            if (item == null) ui.toast(L.assistantNoSong) else ui.push(L.assistantSong) {
                val slides = item.slides.mapIndexed { i, t -> SongSlide(item.labels.getOrNull(i), t) }
                SongAssistantSheet(c, ui, slides) { made ->
                    c.saveItem(item.copy(slides = made.map { it.text }, labels = made.map { it.label }))
                }
            }
        })
    }
}
