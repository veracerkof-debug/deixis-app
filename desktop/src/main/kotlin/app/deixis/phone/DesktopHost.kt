package app.deixis.phone

import androidx.compose.runtime.Composable
import app.deixis.core.ChangelogEntry
import app.deixis.desktop.DesktopUpdates
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Пульт на компьютере для экранов интерфейса: обновления этой программы, Holyrics, отзыв. */
class DesktopHost(
    override val platform: Platform,
    holyricsOf: () -> HolyricsSource,
    private val desktopUpdates: DesktopUpdates,
    override val version: String,
    /** Сообщение оператору в пульте (тост). */
    private val say: (String) -> Unit,
) : PultHost {
    override val holyrics: HolyricsSource by lazy(holyricsOf)
    override val updates: app.deixis.core.UpdateHost get() = desktopUpdates
    override fun takeWhatsNew(): List<ChangelogEntry> = emptyList() // «Что нового» показывает окно самой программы
    override val updatesFromStore = false
    override val canInstallUpdates = true
    override var autoInstallTried: String? = null

    /** Отзыв: текст с версией и системой — в буфер обмена, его можно вставить в письмо или сообщение. */
    @Composable
    override fun FeedbackSection() {
        GHead(L.feedback)
        Group {
            Cell(L.reportProblem, L.reportProblemSub, onClick = { copy(L.problemBody) })
            Sep(T.gutter)
            Cell(L.idea, L.ideaSub, onClick = { copy(L.ideaBody) })
        }
        Note(L.feedbackNoteDesktop)
    }

    private fun copy(body: String) {
        val text = body + "— Deixis $version · ${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        say(L.copied)
    }

    override fun openCastSettings() = Unit
    override fun becomeScreen() = Unit
}
