package app.deixis.phone

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import app.deixis.core.DeixisJson
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** Для чего профиль: от этого — начальный вид и есть ли церковный раздел. */
@Serializable
enum class ProfileKind(val title: String, val icon: String) {
    CHURCH("Церковь", "song"),
    STUDY("Учёба", "board"),
    WORK("Работа", "pres"),
    HOME("Дом", "tv"),
    OTHER("Другое", "layers"),
}

/** Цвет акцента пульта в профиле: с первого взгляда видно, в каком профиле работаете. */
@Serializable
enum class Accent { BRONZE, BLUE, GREEN, PLUM, SLATE }

@Serializable
data class Profile(val id: String, val name: String, val kind: ProfileKind = ProfileKind.OTHER, val accent: Accent = Accent.BRONZE)

@Serializable
data class ProfileBook(val list: List<Profile>, val active: String) {
    val current: Profile get() = list.firstOrNull { it.id == active } ?: list.first()
}

/**
 * Профили — отдельные рабочие места на одном телефоне: «Церковь», «Университет», «Работа». У каждого своя
 * библиотека, сетлисты, темы, оформление, логотип, настройки и резервные копии; данные не смешиваются.
 * Первый профиль — прежние данные на прежнем месте (`library.json`, `backups/`), остальные — в `profiles/<id>/`.
 * Экраны, их PIN и подключение к Holyrics — общие: это устройства, а не данные.
 */
class Profiles(private val root: File) {
    private val file = File(root, "profiles.json")

    fun load(): ProfileBook = runCatching { DeixisJson.decodeFromString(ProfileBook.serializer(), file.readText()) }.getOrNull()
        ?.takeIf { b -> b.list.isNotEmpty() && b.list.any { it.id == MAIN } }
        ?: ProfileBook(listOf(Profile(MAIN, "Основной")), MAIN)

    fun save(b: ProfileBook): ProfileBook {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(DeixisJson.encodeToString(ProfileBook.serializer(), b))
            tmp.renameTo(file)
        }
        return b
    }

    /** Папка данных профиля. */
    fun dirOf(id: String): File = if (id == MAIN) root else File(root, "profiles/$id").apply { mkdirs() }

    fun backupsOf(context: Context, id: String) = Backups(context, dirOf(id), if (id == MAIN) "deixis-" else "deixis.$id-", if (id == MAIN) "last" else "last-$id")

    /** Новый профиль: пустая библиотека с сетлистом на сегодня; церковный раздел — только у «Церкви». */
    fun create(b: ProfileBook, name: String, kind: ProfileKind, accent: Accent): Pair<ProfileBook, Profile> {
        val p = Profile(Store.newId(), name.trim().ifBlank { kind.title }, kind, accent)
        val set = Setlist(Store.newId(), "Сегодня", LocalDate.now().toString())
        val start = StoreData(
            setlists = listOf(set), active = set.id, demoSeeded = true,
            settings = AppSettings(church = kind == ProfileKind.CHURCH),
        )
        File(dirOf(p.id), "library.json").writeText(DeixisJson.encodeToString(StoreData.serializer(), start))
        return save(b.copy(list = b.list + p)) to p
    }

    /** Удалить профиль вместе с его данными. Первый и открытый сейчас не удаляются. */
    fun delete(b: ProfileBook, id: String): ProfileBook {
        if (id == MAIN || id == b.active) return b
        runCatching { File(root, "profiles/$id").deleteRecursively() }
        return save(b.copy(list = b.list.filter { it.id != id }))
    }

    companion object {
        const val MAIN = "main"
    }
}

// ——— Окно «Профили» ———

/** Профили: открыть, создать, переименовать, удалить. Открывается из настроек и с главного. */
@androidx.compose.runtime.Composable
fun ProfilesSheet(c: Controller, ui: Ui) {
    val book by c.profiles.collectAsState()
    Note("У каждого профиля своя библиотека, сетлисты, темы, логотип и вид пульта. Данные профилей не смешиваются. Экраны и Holyrics — общие.")
    GHead("Профили")
    Group {
        book.list.forEachIndexed { i, p ->
            if (i > 0) Sep()
            val on = p.id == book.active
            Cell(
                p.name, if (on) "открыт" else p.kind.title, p.kind.icon, chevron = null,
                mark = if (on) accentColors(p.accent, P.current.dark).first else null,
                onLong = { ui.push(p.name) { ProfileEditor(c, ui, p) } },
                onClick = { if (on) ui.push(p.name) { ProfileEditor(c, ui, p) } else { c.switchProfile(p.id); ui.closeAll() } },
            ) { AccentDot(p.accent) }
        }
    }
    Btn("Новый профиль", icon = "plus", kind = BtnKind.TINT) { ui.push("Новый профиль") { ProfileEditor(c, ui, null) } }
    Note("Касание — открыть профиль. Удержание — переименовать, сменить цвет или удалить.", androidx.compose.ui.Modifier.padding(top = T.gapS))
}

/** Новый профиль ([p] = null) или изменение существующего. */
@androidx.compose.runtime.Composable
private fun ProfileEditor(c: Controller, ui: Ui, p: Profile?) {
    val book by c.profiles.collectAsState()
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(p?.name.orEmpty()) }
    var kind by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(p?.kind ?: ProfileKind.STUDY) }
    var accent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(p?.accent ?: Accent.BLUE) }
    GHead("Название")
    Field(name, kind.title) { name = it }
    GHead("Для чего")
    Seg(ProfileKind.entries.map { it.title }, ProfileKind.entries.indexOf(kind), androidx.compose.ui.Modifier.fillMaxWidth(), scroll = true) { kind = ProfileKind.entries[it] }
    if (p == null) Note(
        if (kind == ProfileKind.CHURCH) "С церковным разделом: Библия, песни, пульт Holyrics." else "Без церковного раздела — его можно включить в настройках профиля.",
        androidx.compose.ui.Modifier.padding(top = T.gapS),
    )
    GHead("Цвет")
    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(T.gap)) {
        Accent.entries.forEach { a -> Swatch(accentColors(a, P.current.dark).first, a == accent, ACCENT_NAMES.getValue(a)) { accent = a } }
    }
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(T.gutter))
    if (p == null) {
        Btn("Создать и открыть", kind = BtnKind.TINT) { c.createProfile(name, kind, accent); ui.closeAll() }
    } else {
        Btn("Сохранить", kind = BtnKind.TINT) { c.editProfile(p.copy(name = name.trim().ifBlank { p.name }, kind = kind, accent = accent)); ui.close() }
        if (p.id != Profiles.MAIN && p.id != book.active) {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(T.gap))
            Btn("Удалить профиль и его данные", kind = BtnKind.DANGER) {
                ui.push("Удалить «${p.name}»?") {
                    Note("Библиотека, сетлисты, темы и копии этого профиля удалятся с телефона. Другие профили не изменятся.")
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(T.gap))
                    Btn("Удалить", kind = BtnKind.DANGER) { c.deleteProfile(p.id); ui.closeAll() }
                }
            }
        } else if (p.id == book.active && book.list.size > 1) {
            Note("Открытый профиль не удаляется: сначала откройте другой.", androidx.compose.ui.Modifier.padding(top = T.gapS))
        }
    }
}

@androidx.compose.runtime.Composable
private fun AccentDot(a: Accent) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.size(T.gap).clip(androidx.compose.foundation.shape.CircleShape)
            .background(accentColors(a, P.current.dark).first),
    )
}

private val ACCENT_NAMES = mapOf(
    Accent.BRONZE to "Бронза", Accent.BLUE to "Синий", Accent.GREEN to "Зелёный", Accent.PLUM to "Сливовый", Accent.SLATE to "Графит",
)
