package app.deixis.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Язык интерфейса пульта: русский (основной), узбекский латиницей, английский. */
enum class Lang(val code: String) { RU("ru"), UZ("uz"), EN("en") }

/**
 * Текущий язык пульта. Выбирается в настройках (AppSettings.lang); null там — как в системе.
 * Корень интерфейса пересоздаётся по [flow], поэтому строки [L] читаются заново при смене языка.
 */
object I18n {
    @Volatile var lang: Lang = fromSystem()
        private set
    private val state = MutableStateFlow(lang)
    val flow: StateFlow<Lang> get() = state

    /** Язык системы: русский для русского, украинского, белорусского и казахского; узбекский; иначе английский. */
    fun fromSystem(): Lang = when (Locale.getDefault().language) {
        "ru", "uk", "be", "kk" -> Lang.RU
        "uz" -> Lang.UZ
        else -> Lang.EN
    }

    /** Код из настроек: «ru», «uz», «en»; null или незнакомый — как в системе. */
    fun of(code: String?): Lang = Lang.entries.firstOrNull { it.code == code } ?: fromSystem()

    fun apply(code: String?) {
        val l = of(code)
        lang = l
        state.value = l
    }

    /** Локаль для дат и месяцев на языке пульта (узбекский — латиница). */
    val locale: Locale
        get() = when (lang) {
            Lang.RU -> Locale.forLanguageTag("ru")
            Lang.UZ -> Locale.forLanguageTag("uz-Latn")
            Lang.EN -> Locale.ENGLISH
        }
}

/**
 * Все строки интерфейса пульта на трёх языках. Свойства — готовые строки на текущем языке,
 * функции — шаблоны с числами и именами. Без обращения на «ты» и «вы»: инфинитив и безличные формы.
 */
object L {
    private fun t(ru: String, uz: String, en: String): String = when (I18n.lang) {
        Lang.RU -> ru
        Lang.UZ -> uz
        Lang.EN -> en
    }

    /** Русское множественное: 1 слайд, 2 слайда, 5 слайдов. */
    fun ruPlural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    // ——— Общие действия и состояния ———

    val connect: String get() = t("Подключить", "Ulash", "Connect")
    val newConnect: String get() = t("Новое подключение", "Yangi ulanish", "New connection")
    val newConnectAsk: String get() = t(
        "Это устройство ввело верный PIN и просит управлять экраном. PIN виден всем в зале — пустите, только если знаете, кто это.",
        "Bu qurilma to‘g‘ri PIN kiritdi va ekranni boshqarishni so‘ramoqda. PIN zaldagi hammaga ko‘rinadi — kimligini bilsangizgina ruxsat bering.",
        "This device entered the correct PIN and asks to control the screen. The PIN is visible to everyone in the room — allow only if you know who it is.",
    )
    val allow: String get() = t("Разрешить", "Ruxsat berish", "Allow")
    val reject: String get() = t("Отклонить", "Rad etish", "Reject")
    val browserDevice: String get() = t("Браузер", "Brauzer", "Browser")
    val disconnect: String get() = t("Отключить", "Uzish", "Disconnect")
    val retry: String get() = t("Повторить", "Qayta urinish", "Retry")
    val close: String get() = t("Закрыть", "Yopish", "Close")
    val save: String get() = t("Сохранить", "Saqlash", "Save")
    val saved: String get() = t("Сохранено", "Saqlandi", "Saved")
    val delete: String get() = t("Удалить", "O‘chirish", "Delete")
    val deleted: String get() = t("Удалено", "O‘chirildi", "Deleted")
    val cancel: String get() = t("Отменить", "Bekor qilish", "Cancel")
    val cancelShort: String get() = t("Отмена", "Bekor qilish", "Cancel")
    val remove: String get() = t("Убрать", "Olib tashlash", "Remove")
    val removeAll: String get() = t("Убрать всё", "Hammasini olib tashlash", "Remove all")
    val create: String get() = t("Создать", "Yaratish", "Create")
    val duplicate: String get() = t("Дублировать", "Nusxalash", "Duplicate")
    val rename: String get() = t("Переименовать", "Nomini o‘zgartirish", "Rename")
    val update: String get() = t("Обновить", "Yangilash", "Update")
    val show: String get() = t("Показать", "Ko‘rsatish", "Show")
    val find: String get() = t("Найти", "Topish", "Find")
    val search: String get() = t("Поиск", "Qidirish", "Search")
    val done: String get() = t("Готово", "Tayyor", "Done")
    val gotIt: String get() = t("Понятно", "Tushunarli", "Got it")
    val restore: String get() = t("Восстановить", "Tiklash", "Restore")
    val add: String get() = t("Добавить", "Qo‘shish", "Add")
    val actions: String get() = t("Действия", "Amallar", "Actions")
    val settings: String get() = t("Настройки", "Sozlamalar", "Settings")
    val nothingFound: String get() = t("Ничего не найдено", "Hech narsa topilmadi", "Nothing found")
    val nothingFoundDot: String get() = t("Ничего не найдено.", "Hech narsa topilmadi.", "Nothing found.")
    val empty: String get() = t("Пусто", "Bo‘sh", "Empty")
    val emptyHere: String get() = t("Здесь пусто", "Bu yerda bo‘sh", "Nothing here")
    val untitled: String get() = t("Без названия", "Nomsiz", "Untitled")
    val title: String get() = t("Название", "Nomi", "Title")
    val date: String get() = t("Дата", "Sana", "Date")
    val up: String get() = t("Выше", "Yuqoriga", "Up")
    val down: String get() = t("Ниже", "Pastga", "Down")
    val prev: String get() = t("Назад", "Orqaga", "Back")
    val next: String get() = t("Дальше", "Oldinga", "Next")
    val smaller: String get() = t("Меньше", "Kichikroq", "Smaller")
    val bigger: String get() = t("Больше", "Kattaroq", "Bigger")
    val preparing: String get() = t("Подготовка…", "Tayyorlanmoqda…", "Preparing…")
    val checking: String get() = t("Проверка…", "Tekshirilmoqda…", "Checking…")
    val searchingShort: String get() = t("Ищем…", "Qidirilmoqda…", "Searching…")
    val continueAction: String get() = t("Продолжить", "Davom etish", "Continue")
    val start: String get() = t("Старт", "Boshlash", "Start")
    val stop: String get() = t("Стоп", "To‘xtatish", "Stop")
    val launch: String get() = t("Запустить", "Ishga tushirish", "Start")
    val again: String get() = t("Заново", "Qaytadan", "Restart")
    val today: String get() = t("Сегодня", "Bugun", "Today")
    val own: String get() = t("своя", "o‘ziniki", "custom")
    val custom: String get() = t("Своё", "Boshqa", "Custom")
    val pictureNotOpened: String get() = t("Картинка не открылась", "Rasm ochilmadi", "The picture did not open")
    val photoNotOpened: String get() = t("Фото не открылось", "Rasm ochilmadi", "The photo did not open")
    val videoNotOpened: String get() = t("Видео не открылось", "Video ochilmadi", "The video did not open")
    val fileLower: String get() = t("файл", "fayl", "file")

    // ——— Вкладки ———

    val tabShow: String get() = t("Показ", "Ko‘rsatish", "Show")
    val tabBible: String get() = t("Библия", "Bibliya", "Bible")
    val tabLibrary: String get() = t("Библиотека", "Kutubxona", "Library")
    val tabSets: String get() = t("Сетлисты", "Ro‘yxatlar", "Setlists")
    val tabDevices: String get() = t("Устройства", "Qurilmalar", "Devices")

    // ——— Виды элементов ———

    val tPres: String get() = t("Презентация", "Taqdimot", "Presentation")
    val tVideo: String get() = t("Видео", "Video", "Video")
    val tPhoto: String get() = t("Фото", "Rasm", "Photo")
    val tFile: String get() = t("Файл", "Fayl", "File")
    val tSong: String get() = t("Песня", "Qo‘shiq", "Song")
    val tText: String get() = t("Текст", "Matn", "Text")
    val tCountdown: String get() = t("Отсчёт", "Sanoq", "Countdown")
    val pPres: String get() = t("Презентации", "Taqdimotlar", "Presentations")
    val pFile: String get() = t("Файлы", "Fayllar", "Files")
    val pSong: String get() = t("Песни", "Qo‘shiqlar", "Songs")
    val pText: String get() = t("Тексты", "Matnlar", "Texts")
    val pCountdown: String get() = t("Отсчёты", "Sanoqlar", "Countdowns")
    val all: String get() = t("Все", "Hammasi", "All")
    val audio: String get() = t("Аудио", "Audio", "Audio")
    val announcements: String get() = t("Объявления", "E’lonlar", "Notices")
    val autoShort: String get() = t("Авто", "Avto", "Auto")
    val playlist: String get() = t("Плейлист", "Pleylist", "Playlist")
    val themes: String get() = t("Темы", "Mavzular", "Themes")
    val theme: String get() = t("Тема", "Mavzu", "Theme")
    val scripture: String get() = t("Писание", "Muqaddas Yozuv", "Scripture")

    // ——— Главный экран: «Сейчас» и «Предпросмотр» ———

    val now: String get() = t("Сейчас", "Hozir", "Now")
    val preview: String get() = t("Предпросмотр", "Oldindan ko‘rish", "Preview")
    val toHall: String get() = t("В зал", "Zalga", "To hall")
    val toPreview: String get() = t("В предпросмотр", "Oldindan ko‘rishga", "To preview")
    val inHall: String get() = t("В зале", "Zalda", "Live")
    val blackScreen: String get() = t("Чёрный экран", "Qora ekran", "Black screen")
    val black: String get() = t("Чёрный", "Qora", "Black")
    val logo: String get() = t("Логотип", "Logotip", "Logo")
    val cleared: String get() = t("Очищено", "Tozalandi", "Cleared")
    val clear: String get() = t("Очистить", "Tozalash", "Clear")
    val awayNow: String get() = t("Deixis ушёл с экранов", "Deixis ekranlardan chiqdi", "Deixis left the screens")
    val away: String get() = t("Уйти", "Chiqish", "Leave")
    val awayLong: String get() = t("Уйти с экранов", "Ekranlardan chiqish", "Leave the screens")
    val awayBack: String get() = t("Deixis снова на экранах", "Deixis yana ekranlarda", "Deixis is back on the screens")
    val awayToast: String get() = t("Deixis ушёл с экранов: любой показ вернёт его", "Deixis ekranlardan chiqdi: istalgan ko‘rsatish uni qaytaradi", "Deixis left the screens: anything shown brings it back")
    val pausedSuffix: String get() = t(" · пауза", " · pauza", " · paused")
    val showingSuffix: String get() = t(", идёт показ", ", ko‘rsatilmoqda", ", showing")
    val newStyle: String get() = t("Новое оформление", "Yangi bezak", "New look")
    val newStyleSuffix: String get() = t(" · новое оформление", " · yangi bezak", " · new look")
    val noSlide: String get() = t("Слайд не выбран", "Slayd tanlanmagan", "No slide selected")
    val emptySlide: String get() = t("Пустой слайд", "Bo‘sh slayd", "Empty slide")
    val look: String get() = t("Вид", "Ko‘rinish", "Look")
    val play: String get() = t("Пуск", "Boshlash", "Play")
    val pause: String get() = t("Пауза", "Pauza", "Pause")
    val back10: String get() = t("−10 с", "−10 s", "−10 s")
    val fwd10: String get() = t("+10 с", "+10 s", "+10 s")
    val back1m: String get() = t("−1 мин", "−1 daq", "−1 min")
    val fwd1m: String get() = t("+1 мин", "+1 daq", "+1 min")
    val loop: String get() = t("Повтор", "Takror", "Loop")
    val seek: String get() = t("Перемотка", "O‘tkazish", "Seek")
    val layers: String get() = t("Слои", "Qatlamlar", "Layers")
    val stopShare: String get() = t("Остановить трансляцию", "Translyatsiyani to‘xtatish", "Stop sharing")
    val setlist: String get() = t("Сетлист", "Ro‘yxat", "Setlist")
    val recent: String get() = t("Недавнее", "So‘nggilar", "Recent")
    val recentPlural: String get() = t("Недавние", "So‘nggilar", "Recent")
    val pickSet: String get() = t("Выбрать сетлист", "Ro‘yxatni tanlash", "Pick a setlist")
    val setEmptyHint: String get() = t("Пусто. Элементы добавляются через «Найти» или плитки «Показать».", "Bo‘sh. Elementlar «Topish» yoki «Ko‘rsatish» plitkalari orqali qo‘shiladi.", "Empty. Items are added with “Find” or the “Show” tiles.")
    val recentEmpty: String get() = t("Здесь появится то, что показывали.", "Bu yerda ko‘rsatilganlar chiqadi.", "Things shown appear here.")
    val countdown: String get() = t("Обратный отсчёт", "Teskari sanoq", "Countdown")
    val overlay: String get() = t("Поверх", "Ustidan", "Overlay")
    val overlayTitle: String get() = t("Поверх показа", "Namoyish ustidan", "Over the show")
    val screenShare: String get() = t("Экран телефона", "Telefon ekrani", "Phone screen")
    val stopScreen: String get() = t("Остановить экран", "Ekranni to‘xtatish", "Stop the screen")
    val sendSetAhead: String get() = t("Файлы сетлиста — на экраны заранее", "Ro‘yxat fayllari — ekranlarga oldindan", "Setlist files to the screens in advance")
    val noScreens: String get() = t("Нет подключённых экранов", "Ulangan ekranlar yo‘q", "No connected screens")
    val noHeavyFiles: String get() = t("В сетлисте нет фото, видео и PDF", "Ro‘yxatda rasm, video va PDF yo‘q", "The setlist has no photos, videos or PDFs")
    val becomeScreen: String get() = t("Стать экраном", "Ekranga aylanish", "Become a screen")
    val convertOnShow: String get() = t("Переведёт компьютер при показе", "Ko‘rsatishda kompyuter o‘giradi", "A computer converts it when shown")
    val heicNote: String get() = t("HEIC → JPEG при отправке", "Yuborishda HEIC → JPEG", "HEIC → JPEG when sent")
    val fileOver1Gb: String get() = t("Файл больше 1 ГБ", "Fayl 1 GB dan katta", "File over 1 GB")
    val convertsOnShow: String get() = t("Переведётся при показе", "Ko‘rsatishda o‘giriladi", "Converted when shown")

    // ——— Библиотека ———

    val libSearch: String get() = t("Поиск по названию и тексту", "Nomi va matni bo‘yicha qidirish", "Search titles and text")
    val libEmpty: String get() = t("Библиотека пуста. «+» добавляет песни, тексты, места из Библии и файлы телефона: презентации, видео, фото.", "Kutubxona bo‘sh. «+» qo‘shiqlar, matnlar, Bibliyadan joylar va telefon fayllarini qo‘shadi: taqdimotlar, video, rasmlar.", "The library is empty. “+” adds songs, texts, Bible passages and phone files: presentations, videos, photos.")
    val libNote: String get() = t("Удержание элемента — править, удалить, в сетлист. Значок экрана со стрелкой — отправить файл на экраны заранее: в зале ничего не меняется.", "Elementni bosib turish — tahrirlash, o‘chirish, ro‘yxatga. Strelkali ekran belgisi — faylni ekranlarga oldindan yuborish: zalda hech narsa o‘zgarmaydi.", "Hold an item to edit, delete or add it to a setlist. The screen icon with an arrow sends the file to the screens in advance: nothing changes in the hall.")
    val queued: String get() = t("в очереди", "navbatda", "queued")
    val preparingLower: String get() = t("готовится", "tayyorlanmoqda", "preparing")
    val onScreen: String get() = t("на экране", "ekranda", "on screen")
    val onAll: String get() = t("на всех", "hammasida", "on all")
    val resend: String get() = t("дослать", "qayta yuborish", "resend")
    val sendingBackground: String get() = t("Передаётся в фоне: в зале ничего не меняется", "Fonda yuborilmoqda: zalda hech narsa o‘zgarmaydi", "Sending in the background: nothing changes in the hall")
    val newSong: String get() = t("Новая песня", "Yangi qo‘shiq", "New song")
    val newText: String get() = t("Новый текст", "Yangi matn", "New text")
    val biblePlace: String get() = t("Место из Библии", "Bibliyadan joy", "Bible passage")
    val biblePlaceSub: String get() = t("Вкладка «Библия» → «В сетлист»", "«Bibliya» bo‘limi → «Ro‘yxatga»", "Bible tab → “To setlist”")
    val phoneFiles: String get() = t("Файлы телефона", "Telefon fayllari", "Phone files")
    val phoneFilesDots: String get() = t("Файлы телефона…", "Telefon fayllari…", "Phone files…")
    val phoneFilesSub: String get() = t("PDF, PPTX, видео, фото, TXT, DOCX", "PDF, PPTX, video, rasm, TXT, DOCX", "PDF, PPTX, video, photo, TXT, DOCX")
    val fullScreen: String get() = t("Во весь экран", "To‘liq ekranda", "Full screen")
    val editSlides: String get() = t("Править слайды", "Slaydlarni tahrirlash", "Edit slides")
    val edit: String get() = t("Править", "Tahrirlash", "Edit")
    val style: String get() = t("Оформление", "Bezak", "Look")
    val sendAhead: String get() = t("Отправить на экраны заранее", "Ekranlarga oldindan yuborish", "Send to the screens in advance")
    val otherPlace: String get() = t("Другое место", "Boshqa joy", "Another passage")
    val removeFromSet: String get() = t("Убрать из сетлиста", "Ro‘yxatdan olib tashlash", "Remove from setlist")
    val removedFromSet: String get() = t("Убрано из сетлиста", "Ro‘yxatdan olib tashlandi", "Removed from setlist")
    val toSet: String get() = t("В сетлист", "Ro‘yxatga", "To setlist")
    val inSet: String get() = t("В сетлисте", "Ro‘yxatda", "In setlist")
    val addedToSet: String get() = t("Добавлено в сетлист", "Ro‘yxatga qo‘shildi", "Added to setlist")
    val deleteFromLib: String get() = t("Удалить из библиотеки", "Kutubxonadan o‘chirish", "Delete from library")
    val deleteItemNote: String get() = t("Элемент уберётся из библиотеки и всех сетлистов. Файл на телефоне останется.", "Element kutubxonadan va barcha ro‘yxatlardan olib tashlanadi. Telefondagi fayl qoladi.", "The item is removed from the library and all setlists. The file stays on the phone.")
    val findHint: String get() = t("Песня, стих, файл", "Qo‘shiq, oyat, fayl", "Song, verse, file")
    val findNote: String get() = t("Найденное открывается в предпросмотре — его можно показать, даже если в сетлисте этого нет.", "Topilgani oldindan ko‘rishda ochiladi — ro‘yxatda bo‘lmasa ham ko‘rsatish mumkin.", "Found items open in preview and can be shown even if they are not in the setlist.")
    val cantOpenPicker: String get() = t("Не удалось открыть выбор файлов", "Fayl tanlashni ochib bo‘lmadi", "Could not open the file picker")
    val needScreenFirst: String get() = t("Сначала нужен подключённый экран", "Avval ulangan ekran kerak", "A connected screen is needed first")
    val noSoundPermission: String get() = t("Трансляция пойдёт без звука: нет разрешения на звук", "Translyatsiya ovozsiz bo‘ladi: ovozga ruxsat yo‘q", "Sharing goes without sound: no sound permission")
    val fmtPres: String get() = t("PDF, PPTX и PPT — прямо на телефоне. ODP и KEY переводит компьютер с Deixis", "PDF, PPTX va PPT — to‘g‘ridan-to‘g‘ri telefonda. ODP va KEY ni Deixis o‘rnatilgan kompyuter o‘giradi", "PDF, PPTX and PPT right on the phone. ODP and KEY are converted by a computer with Deixis")
    val fmtVideo: String get() = t("Любой формат: MP4, MKV, MOV, AVI, WMV, WebM…", "Istalgan format: MP4, MKV, MOV, AVI, WMV, WebM…", "Any format: MP4, MKV, MOV, AVI, WMV, WebM…")
    val fmtFile: String get() = t("PDF, DOCX, TXT, песни ChordPro и OpenLyrics и любые файлы: формат определяется сам", "PDF, DOCX, TXT, ChordPro va OpenLyrics qo‘shiqlari va istalgan fayllar: format o‘zi aniqlanadi", "PDF, DOCX, TXT, ChordPro and OpenLyrics songs and any files: the format is detected automatically")
    val mirrorNote: String get() = t("Всё, что на экране телефона, уйдёт в зал. Подходит для презентаций с анимацией, Google Slides и любого приложения. Android спросит разрешение.", "Telefon ekranidagi hamma narsa zalga chiqadi. Animatsiyali taqdimotlar, Google Slides va istalgan ilova uchun mos. Android ruxsat so‘raydi.", "Everything on the phone screen goes to the hall. Suits presentations with animation, Google Slides and any app. Android asks for permission.")
    val withSound: String get() = t("Со звуком", "Ovoz bilan", "With sound")
    val withSoundSub: String get() = t("Музыка и видео из приложений телефона — в колонки зала. Микрофон не включается", "Telefon ilovalaridagi musiqa va video — zal karnaylariga. Mikrofon yoqilmaydi", "Music and video from phone apps go to the hall speakers. The microphone stays off")
    val noSound: String get() = t("Без звука", "Ovozsiz", "No sound")
    val noSoundSub: String get() = t("Звук приложений передаётся с Android 10", "Ilovalar ovozi Android 10 dan boshlab uzatiladi", "App sound is shared from Android 10")
    val startShare: String get() = t("Начать трансляцию", "Translyatsiyani boshlash", "Start sharing")
    val showViaPhone: String get() = t("Показать через экран телефона", "Telefon ekrani orqali ko‘rsatish", "Show via the phone screen")

    // ——— Сетлисты ———

    val newSet: String get() = t("Новый сетлист", "Yangi ro‘yxat", "New setlist")
    val upcoming: String get() = t("Ближайшие", "Yaqinlari", "Upcoming")
    val past: String get() = t("Прошедшие", "O‘tganlari", "Past")
    val dupHint: String get() = t("Прошлое служение удобно дублировать: оно становится основой следующего.", "O‘tgan ibodatni nusxalash qulay: u keyingisiga asos bo‘ladi.", "Duplicating a past service makes it the base for the next one.")
    val onMain: String get() = t("На главном", "Bosh sahifada", "On main")
    val toMain: String get() = t("На главный", "Bosh sahifaga", "To main")
    val deleteSet: String get() = t("Удалить сетлист", "Ro‘yxatni o‘chirish", "Delete setlist")
    val setDeleted: String get() = t("Сетлист удалён", "Ro‘yxat o‘chirildi", "Setlist deleted")
    val setCreated: String get() = t("Сетлист создан", "Ro‘yxat yaratildi", "Setlist created")
    val chosen: String get() = t("Выбран", "Tanlangan", "Selected")
    val pastSuffix: String get() = t(" · прошёл", " · o‘tdi", " · past")
    val setTitleHint: String get() = t("Название: пара, лекция, служение", "Nomi: dars, ma’ruza, ibodat", "Title: class, lecture, service")

    // ——— Устройства и подключение ———

    val screens: String get() = t("Экраны", "Ekranlar", "Screens")
    val viewers: String get() = t("Зрители", "Tomoshabinlar", "Viewers")
    val nearby: String get() = t("Рядом в сети", "Tarmoqda yaqin", "Nearby on the network")
    val loading: String get() = t("Загрузка…", "Yuklanmoqda…", "Loading…")
    val inputs: String get() = t("Входы", "Kirishlar", "Inputs")
    val inputsNote: String get() = t("Откуда ещё картинка приходит на экран: монитор или окно программы компьютера, виртуальный экран, AirPlay.", "Ekranga rasm yana qayerdan keladi: kompyuter monitori yoki dastur oynasi, virtual ekran, AirPlay.", "Other sources the screen can show: a computer monitor or program window, the virtual screen, AirPlay.")
    val outputs: String get() = t("Выходы", "Chiqishlar", "Outputs")
    val outputsNote: String get() = t("Куда экран выводит показ: мониторы компьютера и их раскладка.", "Ekran ko‘rsatuvni qayerga chiqaradi: kompyuter monitorlari va ularning joylashuvi.", "Where the screen sends the show: the computer’s monitors and their layout.")
    val monitors: String get() = t("Мониторы", "Monitorlar", "Monitors")
    val primaryMonitor: String get() = t("основной", "asosiy", "main")
    val layoutsNote: String get() = t("Зал — слайд целиком. Сцена — текущий и следующий слайд с часами. Титры — текст нижней третью. Хромакей — текст на зелёном для трансляции.", "Zal — slayd to‘liq. Sahna — joriy va keyingi slayd soat bilan. Titrlar — matn pastki uchdan birida. Xromakey — translyatsiya uchun yashil fonda matn.", "Hall — the whole slide. Stage — current and next slide with a clock. Captions — text in the lower third. Chroma key — text on green for streaming.")
    fun layoutName(l: app.deixis.core.OutputLayout): String = when (l) {
        app.deixis.core.OutputLayout.HALL -> t("Зал", "Zal", "Hall")
        app.deixis.core.OutputLayout.STAGE -> t("Сцена", "Sahna", "Stage")
        app.deixis.core.OutputLayout.LOWER_THIRD -> t("Титры", "Titrlar", "Captions")
        app.deixis.core.OutputLayout.CHROMA -> t("Хромакей", "Xromakey", "Chroma")
    }
    val chooseFiles: String get() = t("Выбрать файлы", "Fayllarni tanlash", "Choose files")
    val chooseFolder: String get() = t("Папка для копий", "Nusxalar uchun jild", "Folder for backups")
    val desktopShareHint: String get() = t(
        "На компьютере экран показывается плиткой «Экран компьютера»: монитор, окно программы или виртуальный экран.",
        "Kompyuterda ekran «Kompyuter ekrani» plitkasi orqali ko‘rsatiladi: monitor, dastur oynasi yoki virtual ekran.",
        "On the computer, the screen goes out with the “Computer screen” tile: a monitor, a program window or the virtual screen.",
    )
    val thisComputerHead: String get() = t("Этот компьютер", "Bu kompyuter", "This computer")
    val thisPhone: String get() = t("Этот телефон", "Bu telefon", "This phone")
    val connectScreen: String get() = t("Подключить экран", "Ekranni ulash", "Connect a screen")
    val preflight: String get() = t("Проверка перед началом", "Boshlashdan oldin tekshirish", "Check before starting")
    val becomeScreenSub: String get() = t("Телефон или планшет показывает сам: для проектора, доски, запасного экрана", "Telefon yoki planshet o‘zi ko‘rsatadi: proyektor, doska, zaxira ekran uchun", "The phone or tablet shows by itself: for a projector, a board, a spare screen")
    val connectedState: String get() = t("Подключено", "Ulangan", "Connected")
    val hlAbout: String get() = t("Песни, темы и показ из Holyrics на компьютере", "Kompyuterdagi Holyrics qo‘shiqlari, mavzulari va ko‘rsatish", "Songs, themes and shows from Holyrics on the computer")
    val modules: String get() = t("Переводы Библии", "Bibliya tarjimalari", "Bible translations")
    val modulesAbout: String get() = t("Синодальный, King James и модули MyBible", "Sinodal, King James va MyBible modullari", "Synodal, King James and MyBible modules")
    val cableNote: String get() = t("Кабель HDMI и беспроводной дисплей (Миракаст) появляются в «Экранах» сами, как только телефон их видит.", "HDMI kabel va simsiz displey (Miracast) telefon ularni ko‘rishi bilan «Ekranlar»da o‘zi paydo bo‘ladi.", "An HDMI cable and a wireless display (Miracast) appear under “Screens” as soon as the phone sees them.")
    val foundOnNet: String get() = t("Найдено в сети", "Tarmoqda topildi", "Found on the network")
    val noScreenYet: String get() = t("Пока ни одного экрана", "Hozircha birorta ekran yo‘q", "No screens yet")
    val searchingScreens: String get() = t("Ищем экраны Deixis…", "Deixis ekranlari qidirilmoqda…", "Looking for Deixis screens…")
    val sameWifi: String get() = t("Приёмник должен быть в этой же сети Wi-Fi", "Qabul qiluvchi shu Wi-Fi tarmog‘ida bo‘lishi kerak", "The receiver must be on the same Wi-Fi")
    val manualAddress: String get() = t("Ввести адрес вручную", "Manzilni qo‘lda kiritish", "Enter the address manually")
    val receiverAddress: String get() = t("Адрес приёмника", "Qabul qiluvchi manzili", "Receiver address")
    val miracastCell: String get() = t("Беспроводной дисплей (Миракаст)", "Simsiz displey (Miracast)", "Wireless display (Miracast)")
    val miracastSub: String get() = t("Включается в настройках Android → Трансляция", "Android sozlamalari → Translyatsiya bo‘limida yoqiladi", "Turned on in Android settings → Cast")
    val connectNote: String get() = t("Не видно экрана? В гостевом Wi-Fi устройства часто изолированы. Выход — адрес с экрана приёмника вручную или точка доступа на телефоне, к которой подключён приёмник. Кабель HDMI и Миракаст появляются здесь сами.", "Ekran ko‘rinmayaptimi? Mehmon Wi-Fi tarmog‘ida qurilmalar ko‘pincha ajratilgan. Yechim — qabul qiluvchi ekranidagi manzilni qo‘lda kiritish yoki qabul qiluvchi ulangan telefondagi kirish nuqtasi. HDMI kabel va Miracast bu yerda o‘zi paydo bo‘ladi.", "No screen in sight? Guest Wi-Fi often isolates devices. The way out is the address from the receiver screen entered manually, or a hotspot on the phone with the receiver connected to it. An HDMI cable and Miracast appear here by themselves.")
    val manualNote: String get() = t("Адрес показан внизу экрана приёмника. Можно вставить и ссылку из его QR-кода.", "Manzil qabul qiluvchi ekranining pastida ko‘rsatilgan. Uning QR-kodidagi havolani ham qo‘yish mumkin.", "The address is shown at the bottom of the receiver screen. The link from its QR code works too.")
    val pinFromScreen: String get() = t("PIN с экрана", "Ekrandagi PIN", "PIN from the screen")
    val cableHdmi: String get() = t("Кабель HDMI", "HDMI kabel", "HDMI cable")
    val miracast: String get() = t("Миракаст", "Miracast", "Miracast")
    val deixisDesktop: String get() = t("Deixis для компьютера", "Kompyuter uchun Deixis", "Deixis for computer")
    val deixisPhoneBoard: String get() = t("Deixis на телефоне или доске", "Telefon yoki doskadagi Deixis", "Deixis on a phone or board")
    val wirelessDisplay: String get() = t("Беспроводной дисплей", "Simsiz displey", "Wireless display")
    val stOnline: String get() = t("на связи", "aloqada", "online")
    val stSync: String get() = t("доставляются файлы", "fayllar yetkazilmoqda", "delivering files")
    val stConnecting: String get() = t("подключение…", "ulanmoqda…", "connecting…")
    val stOffline: String get() = t("нет связи", "aloqa yo‘q", "offline")
    val stPin: String get() = t("нужен PIN", "PIN kerak", "PIN needed")
    val noneConnected: String get() = t("Ни один экран не подключён", "Birorta ham ekran ulanmagan", "No screen is connected")
    val allReady: String get() = t("Все экраны готовы", "Barcha ekranlar tayyor", "All screens are ready")
    val needsAttention: String get() = t("Требует внимания: ", "E’tibor talab qiladi: ", "Needs attention:")
    val viewersInBrowser: String get() = t("Зрители смотрят показ в браузере", "Tomoshabinlar ko‘rsatishni brauzerda ko‘radi", "Viewers watch the show in a browser")
    val viewersInBrowserSub: String get() = t("С экраном Deixis по Wi-Fi здесь появится QR-код", "Wi-Fi orqali Deixis ekrani ulanganda bu yerda QR-kod chiqadi", "With a Deixis screen over Wi-Fi a QR code appears here")
    val watchingNow: String get() = t("Смотрят сейчас", "Hozir tomosha qilmoqda", "Watching now")
    val viewersHint: String get() = t("Камерой телефона по QR-коду. Приложение не нужно, телефон зрителя — в этой же сети Wi-Fi.", "Telefon kamerasi bilan QR-kod orqali. Ilova kerak emas, tomoshabin telefoni shu Wi-Fi tarmog‘ida bo‘ladi.", "With a phone camera via the QR code. No app is needed; the viewer’s phone is on the same Wi-Fi.")
    val netAddress: String get() = t("Адрес в сети", "Tarmoqdagi manzil", "Network address")
    val byName: String get() = t("По имени: iPhone, компьютер", "Nomi bo‘yicha: iPhone, kompyuter", "By name: iPhone, computer")

    // ——— Настройки ———

    val profile: String get() = t("Профиль", "Profil", "Profile")
    val profiles: String get() = t("Профили", "Profillar", "Profiles")
    val uiLight: String get() = t("Светлая", "Yorug‘", "Light")
    val uiDark: String get() = t("Тёмная", "Qorong‘i", "Dark")
    val paper: String get() = t("Бумага", "Qog‘oz", "Paper")
    val alwaysPre: String get() = t("Всегда через предпросмотр", "Doim oldindan ko‘rish orqali", "Always via preview")
    val alwaysPreSub: String get() = t("Любой слайд сначала идёт в предпросмотр", "Har qanday slayd avval oldindan ko‘rishga tushadi", "Every slide goes to preview first")
    val darkDuringShow: String get() = t("Тёмная тема во время показа", "Ko‘rsatish paytida qorong‘i mavzu", "Dark theme during the show")
    val darkDuringShowSub: String get() = t("Включается сама, пока идёт показ", "Ko‘rsatish davomida o‘zi yoqiladi", "Turns on by itself while showing")
    val clicker: String get() = t("Bluetooth-кликер и педаль", "Bluetooth-kliker va pedal", "Bluetooth clicker and pedal")
    val volumeKeys: String get() = t("Кнопки громкости листают слайды", "Ovoz tugmalari slaydlarni varaqlaydi", "Volume keys step through slides")
    val caps: String get() = t("Текст заглавными буквами", "Matn bosh harflarda", "Text in capitals")
    val capsSub: String get() = t("Песни, тексты и Писание в зале", "Zalda qo‘shiqlar, matnlar va Muqaddas Yozuv", "Songs, texts and Scripture in the hall")
    val defaultStyle: String get() = t("Оформление по умолчанию", "Standart bezak", "Default look")
    val defaultStyleSub: String get() = t("Фон, шрифт, размер — для всех слайдов без своего", "Fon, shrift, o‘lcham — o‘z bezagi bo‘lmagan barcha slaydlar uchun", "Background, font, size for every slide without its own")
    val logoScreen: String get() = t("Заставка «Логотип»", "«Logotip» ekrani", "“Logo” screen")
    val ownPicture: String get() = t("Своя картинка", "O‘z rasmi", "Own picture")
    val ownPictureSub: String get() = t("Логотип церкви, школы или афиша вместо знака", "Belgi o‘rniga cherkov, maktab logotipi yoki afisha", "A church or school logo or a poster instead of the mark")
    val logoNoteImage: String get() = t("Так выглядит экран в зале по кнопке «Логотип»: картинка целиком на чёрном.", "«Logotip» tugmasi bosilganda zal ekrani shunday ko‘rinadi: rasm qora fonda to‘liq.", "This is the hall screen after “Logo”: the whole picture on black.")
    val logoNote: String get() = t("Так выглядит экран в зале по кнопке «Логотип».", "«Logotip» tugmasi bosilganda zal ekrani shunday ko‘rinadi.", "This is the hall screen after “Logo”.")
    val sections: String get() = t("Разделы", "Bo‘limlar", "Sections")
    val church: String get() = t("Церковный раздел", "Cherkov bo‘limi", "Church section")
    val churchSub: String get() = t("Библия и Holyrics на главном", "Bosh sahifada Bibliya va Holyrics", "Bible and Holyrics on the main screen")
    val demo: String get() = t("Демо-материалы", "Namuna materiallari", "Demo content")
    val removeDemo: String get() = t("Убрать демо", "Namunani olib tashlash", "Remove demo")
    val removeDemoSub: String get() = t("Песни, тексты, лекция, фото, видео, темы и сетлист «Демо»", "Qo‘shiqlar, matnlar, ma’ruza, rasm, video, mavzular va «Demo» ro‘yxati", "Songs, texts, a lecture, photos, videos, themes and the “Demo” setlist")
    val removeDemoNote: String get() = t("Уйдут демо-песни, тексты, места из Библии, лекция, фото, видео, демо-темы и сетлист «Демо». Своё остаётся. Вернуть их можно здесь же.", "Namuna qo‘shiqlar, matnlar, Bibliyadan joylar, ma’ruza, rasm, video, namuna mavzular va «Demo» ro‘yxati olib tashlanadi. Boshqa materiallar qoladi. Ularni shu yerda qaytarish mumkin.", "Demo songs, texts, Bible passages, the lecture, photos, videos, demo themes and the “Demo” setlist go away. Own content stays. They can be brought back here.")
    val demoRemoved: String get() = t("Демо-материалы убраны", "Namuna materiallari olib tashlandi", "Demo content removed")
    val updateDemo: String get() = t("Обновить демо", "Namunani yangilash", "Update demo")
    val restoreDemo: String get() = t("Вернуть демо", "Namunani qaytarish", "Bring back demo")
    val restoreDemoSub: String get() = t("Сетлист «Демо»: песни в своих темах, Писание, лекция, фото и видеофон", "«Demo» ro‘yxati: o‘z mavzularidagi qo‘shiqlar, Muqaddas Yozuv, ma’ruza, rasm va video fon", "The “Demo” setlist: songs in their own themes, Scripture, a lecture, photos and a video background")
    val demoOnMain: String get() = t("Демо-материалы на главном", "Namuna materiallari bosh sahifada", "Demo content is on the main screen")
    val ethics: String get() = t("без рекламы, аналитики и трекеров", "reklama, analitika va trekerlarsiz", "no ads, analytics or trackers")
    val language: String get() = t("Язык", "Til", "Language")
    val langSystem: String get() = t("Как в системе", "Tizimdagidek", "System default")

    // ——— Оформление и темы ———

    val themesHint: String get() = t("Удержание темы — изменить её или сделать свою на её основе.", "Mavzuni bosib turish — uni o‘zgartirish yoki uning asosida o‘zinikini yaratish.", "Hold a theme to edit it or base a new one on it.")
    val quickEdit: String get() = t("Быстрая правка", "Tez tahrir", "Quick edit")
    val background: String get() = t("Фон", "Fon", "Background")
    val textColor: String get() = t("Цвет текста", "Matn rangi", "Text colour")
    val textSize: String get() = t("Размер текста", "Matn o‘lchami", "Text size")
    val shadow: String get() = t("Тень", "Soya", "Shadow")
    val outline: String get() = t("Обводка", "Chegara", "Outline")
    val box: String get() = t("Плашка под текстом", "Matn ostidagi plashka", "Box behind text")
    val boxSub: String get() = t("Тёмная полоса: текст читается на любом фото", "To‘q chiziq: matn har qanday rasmda o‘qiladi", "A dark band keeps text readable on any photo")
    val capsTheme: String get() = t("Заглавные буквы", "Bosh harflar", "Capital letters")
    val alignment: String get() = t("Выравнивание", "Tekislash", "Alignment")
    val position: String get() = t("Положение", "Joylashuv", "Position")
    val left: String get() = t("Слева", "Chapda", "Left")
    val center: String get() = t("По центру", "Markazda", "Centre")
    val right: String get() = t("Справа", "O‘ngda", "Right")
    val top: String get() = t("Сверху", "Tepada", "Top")
    val bottom: String get() = t("Снизу", "Pastda", "Bottom")
    val allSettings: String get() = t("Все настройки", "Barcha sozlamalar", "All settings")
    val allSettingsSub: String get() = t("Фото и видеофон, плашка, выравнивание, положение", "Rasm va video fon, plashka, tekislash, joylashuv", "Photo and video background, box, alignment, position")
    val allStyleSettings: String get() = t("Все настройки оформления", "Barcha bezak sozlamalari", "All look settings")
    val applyTo: String get() = t("Применить к", "Qo‘llash", "Apply to")
    val wholeSong: String get() = t("Вся песня", "Butun qo‘shiq", "Whole song")
    val wholeDoc: String get() = t("Весь документ", "Butun hujjat", "Whole document")
    val thisSlide: String get() = t("Этот слайд", "Shu slayd", "This slide")
    val byDefault: String get() = t("По умолчанию", "Standart", "Default")
    val scopeSlide: String get() = t("Только этот слайд; остальные слайды — как были.", "Faqat shu slayd; qolgan slaydlar avvalgidek.", "This slide only; the others stay as they were.")
    val scopeItem: String get() = t("Все слайды этого элемента; прежнее оформление отдельных слайдов снимается.", "Shu elementning barcha slaydlari; alohida slaydlarning avvalgi bezagi olib tashlanadi.", "Every slide of this item; earlier looks of single slides are removed.")
    val scopeSet: String get() = t("Всё в этом сетлисте, у чего нет своего оформления.", "Bu ro‘yxatdagi o‘z bezagi bo‘lmagan hamma narsa.", "Everything in this setlist without its own look.")
    val scopeAll: String get() = t("Везде, где нет своего оформления у элемента или сетлиста.", "Element yoki ro‘yxatning o‘z bezagi bo‘lmagan hamma joyda.", "Everywhere an item or setlist has no look of its own.")
    val styleNoteSuffix: String get() = t(" Правка видна в «Предпросмотре»; в зал — по «Применить в зал».", " Tahrir «Oldindan ko‘rish»da ko‘rinadi; zalga — «Zalda qo‘llash» bilan.", " Changes show in Preview; “Apply to hall” puts them live.")
    val applyHall: String get() = t("Применить в зал", "Zalda qo‘llash", "Apply to hall")
    val styleLive: String get() = t("Оформление в зале", "Bezak zalda", "Look is live")
    val sample: String get() = t("Господь — Пастырь мой;\nя ни в чём не буду нуждаться", "Egam — Cho‘ponim,\nHech narsaga muhtoj bo‘lmayman", "The Lord is my shepherd;\nI shall not want")
    val sampleShort: String get() = t("Аа Бб", "Aa Bb", "Aa Bb")
    val sampleAa: String get() = t("Аа", "Aa", "Aa")
    val newTheme: String get() = t("Новая тема", "Yangi mavzu", "New theme")
    val newThemeShort: String get() = t("Новая", "Yangi", "New")
    val myTheme: String get() = t("Моя тема", "Mening mavzuim", "My theme")
    val themeName: String get() = t("Название темы", "Mavzu nomi", "Theme name")
    val themeDeleted: String get() = t("Тема удалена", "Mavzu o‘chirildi", "Theme deleted")
    val moreColors: String get() = t("Ещё цвета", "Boshqa ranglar", "More colours")
    val photoBg: String get() = t("Фото на фон", "Fon uchun rasm", "Photo background")
    val videoBg: String get() = t("Видеофон", "Video fon", "Video background")
    val videoBgSub: String get() = t("Играет по кругу без звука, текст меняется поверх", "Ovozsiz takrorlanadi, matn ustida almashadi", "Loops without sound; text changes on top")
    val defaultStyleNote: String get() = t("Своё оформление у песни, документа или сетлиста сильнее этого: «Вид» во время показа.", "Qo‘shiq, hujjat yoki ro‘yxatning o‘z bezagi bundan ustun: ko‘rsatish paytida «Ko‘rinish».", "A song, document or setlist look of its own wins over this one: “Look” during the show.")
    val asBefore: String get() = t("Как было", "Avvalgidek", "As before")
    val defaultStyleSaved: String get() = t("Оформление по умолчанию сохранено", "Standart bezak saqlandi", "Default look saved")
    val hideThemes: String get() = t("Спрятать темы", "Mavzularni yashirish", "Hide themes")
    val themesStrip: String get() = t("Темы узкой полосой", "Mavzular tor chiziqda", "Themes as a strip")
    val themesFull: String get() = t("Темы на всё место", "Mavzular butun joyda", "Themes full size")
    val themePanelNote: String get() = t("Касание — тема сразу в зал и остаётся у песни или документа. Зажать и перенести на песню или слайд в сетлисте — тема им. Зажать и отпустить — изменить.", "Bosish — mavzu darhol zalga va qo‘shiq yoki hujjatda qoladi. Bosib turib ro‘yxatdagi qo‘shiq yoki slaydga olib o‘tish — mavzu ularga. Bosib turib qo‘yib yuborish — o‘zgartirish.", "Tap to put a theme live; it stays with the song or document. Hold and drag onto a song or slide in the setlist to give it the theme. Hold and release to edit.")
    val cNavy: String get() = t("Синий", "Ko‘k", "Blue")
    val cWarm: String get() = t("Тёплый", "Iliq", "Warm")
    val cGreen: String get() = t("Зелёный", "Yashil", "Green")
    val cPlum: String get() = t("Сливовый", "Olxo‘ri rang", "Plum")
    val cWhite: String get() = t("Белый", "Oq", "White")
    val cCream: String get() = t("Кремовый", "Krem rang", "Cream")
    val cGold: String get() = t("Золотой", "Oltin", "Gold")
    val cDark: String get() = t("Тёмный", "To‘q", "Dark")
    val cRed: String get() = t("Красный", "Qizil", "Red")
    val cBronze: String get() = t("Бронза", "Bronza", "Bronze")
    val cGraphite: String get() = t("Графит", "Grafit", "Graphite")
    val thClassic: String get() = t("Классика", "Klassika", "Classic")
    val thNight: String get() = t("Ночь", "Tun", "Night")
    val thWarm: String get() = t("Тёплая", "Iliq", "Warm")
    val thForest: String get() = t("Лес", "O‘rmon", "Forest")
    val thGold: String get() = t("Золото", "Oltin", "Gold")
    val thLower: String get() = t("Нижняя треть", "Pastki uchdan bir", "Lower third")

    // ——— Писание ———

    val pickBookChapter: String get() = t("Выбрать книгу и главу", "Kitob va bobni tanlash", "Pick book and chapter")
    val bookChapter: String get() = t("Книга и глава", "Kitob va bob", "Book and chapter")
    val searchText: String get() = t("Поиск по тексту", "Matn bo‘yicha qidirish", "Search the text")
    val themeLook: String get() = t("Тема оформления", "Bezak mavzusi", "Theme")
    val holyricsTranslations: String get() = t("Переводы Holyrics", "Holyrics tarjimalari", "Holyrics translations")
    val translationsInHolyrics: String get() = t("Переводы в Holyrics", "Holyrics dagi tarjimalar", "Translations in Holyrics")
    val scriptureLook: String get() = t("Оформление Писания", "Muqaddas Yozuv bezagi", "Scripture layout")
    val swapTr: String get() = t("Поменять переводы местами", "Tarjimalar o‘rnini almashtirish", "Swap translations")
    val addTranslation: String get() = t("Добавить перевод", "Tarjima qo‘shish", "Add translation")
    val searchHint: String get() = t("Слова из стиха или «Ин 3:16»", "Oyatdagi so‘zlar yoki «Yuh 3:16»", "Words from a verse or “John 3:16”")
    val removedHistory: String get() = t("Убрано из истории", "Tarixdan olib tashlandi", "Removed from history")
    val clearHistory: String get() = t("Очистить историю", "Tarixni tozalash", "Clear history")
    val first200: String get() = t("Показаны первые 200 стихов: точнее запрос — меньше лишнего.", "Dastlabki 200 oyat ko‘rsatildi: so‘rov aniqroq bo‘lsa, ortiqchasi kamroq.", "The first 200 verses are shown: a narrower query gives fewer extras.")
    val translationsLoading: String get() = t("Переводы загружаются…", "Tarjimalar yuklanmoqda…", "Loading translations…")
    val bookChapterAbove: String get() = t("Книга и глава — выше", "Kitob va bob — yuqorida", "Book and chapter are above")
    val nextBook: String get() = t("Следующая книга", "Keyingi kitob", "Next book")
    val tapHolyrics: String get() = t("Касание — выбрать, двойное — сразу в Holyrics", "Bosish — tanlash, ikki marta — darhol Holyrics ga", "Tap to select, double tap straight to Holyrics")
    val blackInHall: String get() = t("В зале чёрный экран", "Zalda qora ekran", "Black screen in the hall")
    val verseHint: String get() = t("Касание — предпросмотр, двойное — в зал, удержание — до этого стиха", "Bosish — oldindan ko‘rish, ikki marta — zalga, bosib turish — shu oyatgacha", "Tap for preview, double tap for the hall, hold to extend up to this verse")
    val saveToSet: String get() = t("Сохранить в сетлист", "Ro‘yxatga saqlash", "Save to setlist")
    val verse: String get() = t("Стих", "Oyat", "Verse")
    val toHolyrics: String get() = t("В Holyrics", "Holyrics ga", "To Holyrics")
    val bookName: String get() = t("Название книги", "Kitob nomi", "Book name")
    val oldT: String get() = t("Ветхий Завет", "Eski Ahd", "Old Testament")
    val newT: String get() = t("Новый Завет", "Yangi Ahd", "New Testament")
    val versesPerSlide: String get() = t("Стихов на слайде", "Slayddagi oyatlar", "Verses per slide")
    val refLbl: String get() = t("Адрес", "Manzil", "Reference")
    val refNone: String get() = t("Без адреса", "Manzilsiz", "None")
    val sideBySide: String get() = t("Два перевода рядом", "Ikki tarjima yonma-yon", "Two translations side by side")
    val sideBySideSub: String get() = t("В две колонки, а не друг под другом", "Ustma-ust emas, ikki ustunda", "In two columns rather than stacked")
    val fullBook: String get() = t("Полное название книги", "Kitobning to‘liq nomi", "Full book name")
    val fullBookSub: String get() = t("«От Иоанна» вместо «Ин»", "«Yuh» o‘rniga «Yuhanno»", "“John” instead of “Jn”")
    val verseNumbers: String get() = t("Номера стихов", "Oyat raqamlari", "Verse numbers")
    val scriptureNote: String get() = t("Цвет, фон и размер текста — тема оформления: кнопка «Вид» на главном.", "Rang, fon va matn o‘lchami — bezak mavzusi: bosh sahifadagi «Ko‘rinish» tugmasi.", "Colour, background and text size come from the theme: the “Look” button on the main screen.")
    val refEach: String get() = t("Адрес у каждого перевода", "Har bir tarjimada manzil", "Reference for each translation")
    val pickerLoading: String get() = t("Переводы загружаются… Если их нет — модуль MyBible добавляется в «Библиотека → + → Переводы Библии».", "Tarjimalar yuklanmoqda… Agar ular bo‘lmasa, MyBible moduli «Kutubxona → + → Bibliya tarjimalari» orqali qo‘shiladi.", "Loading translations… If there are none, a MyBible module is added in “Library → + → Bible translations”.")
    val quickRef: String get() = t("Быстро: Ин 3:16 или Пс 22", "Tez: Yuh 3:16 yoki Zab 22", "Quick: John 3:16 or Ps 22")
    val book: String get() = t("Книга", "Kitob", "Book")
    val rangeHint: String get() = t("Стих или диапазон: первое касание — начало, второе — конец", "Oyat yoki oraliq: birinchi bosish — boshi, ikkinchisi — oxiri", "A verse or a range: the first tap is the start, the second the end")
    val versesNotFound: String get() = t("Стихи не найдены в выбранных переводах", "Tanlangan tarjimalarda oyatlar topilmadi", "No verses found in the selected translations")
    val refNoneLower: String get() = t("без адреса", "manzilsiz", "no reference")
    val refTopLower: String get() = t("адрес сверху", "manzil tepada", "reference on top")
    val refBottomLower: String get() = t("адрес снизу", "manzil pastda", "reference at the bottom")
    val sideBySideLower: String get() = t("переводы рядом", "tarjimalar yonma-yon", "side by side")
    val modulesSub: String get() = t("Модули MyBible: .SQLite3", "MyBible modullari: .SQLite3", "MyBible modules: .SQLite3")
    val addModule: String get() = t("Добавить модуль…", "Modul qo‘shish…", "Add module…")
    val modulesNote: String get() = t("Синодальный и King James встроены. Другие переводы — файлы MyBible (.SQLite3), например с сайта mybible.zone.", "Sinodal va King James o‘rnatilgan. Boshqa tarjimalar — MyBible fayllari (.SQLite3), masalan mybible.zone saytidan.", "Synodal and King James are built in. Other translations are MyBible files (.SQLite3), for example from mybible.zone.")

    // ——— Песни и тексты ———

    val songTitle: String get() = t("Название песни", "Qo‘shiq nomi", "Song title")
    val slidesMode: String get() = t("Слайды", "Slaydlar", "Slides")
    val wholeText: String get() = t("Одним текстом", "Bitta matn bilan", "As one text")
    val songHint: String get() = t("Куплеты и припев.\nПустая строка — новый слайд, [Припев] — подпись слайда", "Bandlar va naqarot.\nBo‘sh qator — yangi slayd, [Naqarot] — slayd yorlig‘i", "Verses and chorus.\nAn empty line starts a new slide, [Chorus] labels a slide")
    val songNote: String get() = t("Пустая строка между строками — новый слайд. Строка в квадратных скобках над слайдом — его подпись: [Куплет 1], [Припев].", "Qatorlar orasidagi bo‘sh qator — yangi slayd. Slayd ustidagi kvadrat qavsdagi qator — uning yorlig‘i: [1-band], [Naqarot].", "An empty line between lines starts a new slide. A line in square brackets above a slide is its label: [Verse 1], [Chorus].")
    val slide: String get() = t("Слайд", "Slayd", "Slide")
    val by2: String get() = t("По 2 строки", "2 qatordan", "2 lines each")
    val by4: String get() = t("По 4 строки", "4 qatordan", "4 lines each")
    val splitNote: String get() = t("Курсор в тексте и «Разделить» — новый слайд с этого места. Подпись — касанием по ней.", "Matndagi kursor va «Bo‘lish» — shu joydan yangi slayd. Yorliq — ustiga bosib.", "The cursor in the text and “Split” start a new slide from there. Tap a label to change it.")
    val emptyYet: String get() = t("Пока пусто", "Hozircha bo‘sh", "Empty so far")
    val saveToLib: String get() = t("Сохранить в библиотеку", "Kutubxonaga saqlash", "Save to library")
    val label: String get() = t("Подпись", "Yorliq", "Label")
    val deleteSlide: String get() = t("Удалить слайд", "Slaydni o‘chirish", "Delete slide")
    val slideLines: String get() = t("Строки слайда", "Slayd qatorlari", "Slide lines")
    val splitAtCursor: String get() = t("Разделить по курсору", "Kursor bo‘yicha bo‘lish", "Split at cursor")
    val withPrevious: String get() = t("С предыдущим", "Oldingisi bilan", "With previous")
    val noLabel: String get() = t("Без подписи", "Yorliqsiz", "No label")
    val ownLabel: String get() = t("Своя подпись", "O‘z yorlig‘i", "Own label")
    val setOwnLabel: String get() = t("Поставить свою", "O‘zinikini qo‘yish", "Use own")

    // ——— Обратный отсчёт ———

    val duration: String get() = t("Длительность", "Davomiylik", "Duration")
    val untilTimeSeg: String get() = t("До времени", "Vaqtgacha", "Until a time")
    val minutes: String get() = t("Минут", "Daqiqa", "Minutes")
    val untilNote: String get() = t("Отсчёт до ближайшего такого времени: сегодня, а если оно прошло, — завтра.", "Eng yaqin shu vaqtgacha sanoq: bugun, agar o‘tib ketgan bo‘lsa — ertaga.", "Counts to the nearest such time: today, or tomorrow if it has passed.")
    val countdownLabelHint: String get() = t("Подпись: «До начала», «Перерыв»", "Yorliq: «Boshlanishgacha», «Tanaffus»", "Label: “Starting soon”, “Break”")
    val labelTop: String get() = t("Подпись сверху", "Yorliq tepada", "Label on top")
    val afterZero: String get() = t("После нуля", "Noldan keyin", "After zero")
    val redAfter: String get() = t("Дальше красным", "Qizil bilan davom", "Continue in red")
    val nextItem: String get() = t("Следующий", "Keyingisi", "Next")
    val hmmss: String get() = t("Ч:ММ:СС", "S:DD:SS", "H:MM:SS")
    val mmss: String get() = t("ММ:СС", "MM:SS", "MM:SS")
    val hhmm: String get() = t("ЧЧ:ММ", "SS:DD", "HH:MM")
    val seconds: String get() = t("Секунды", "Soniyalar", "Seconds")
    val noFractions: String get() = t("Без долей", "Ulushsiz", "No fractions")
    val frac1: String get() = t("0,1 с", "0,1 s", "0.1 s")
    val frac2: String get() = t("0,01 с", "0,01 s", "0.01 s")
    val frac3: String get() = t("0,001 с", "0,001 s", "0.001 s")
    val fontSans: String get() = t("Обычный шрифт", "Oddiy shrift", "Regular font")
    val fontSerif: String get() = t("С засечками", "Serifli", "Serif")
    val size: String get() = t("Размер", "O‘lcham", "Size")
    val digitColor: String get() = t("Цвет цифр", "Raqamlar rangi", "Digit colour")
    val themeColor: String get() = t("Цвет темы", "Mavzu rangi", "Theme colour")
    val nextNote: String get() = t("В ноль в зал сам уйдёт следующий элемент сетлиста.", "Nolda zalga ro‘yxatning keyingi elementi o‘zi chiqadi.", "At zero the next setlist item goes live by itself.")
    val countdownNote: String get() = t("Фон, тень и обводка — от темы оформления. Время на всех экранах идёт вместе: каждый экран считает от своих часов.", "Fon, soya va chegara — bezak mavzusidan. Barcha ekranlarda vaqt birga yuradi: har bir ekran o‘z soatidan sanaydi.", "Background, shadow and outline come from the theme. Time runs together on all screens: each screen counts by its own clock.")
    val countdownLive: String get() = t("Отсчёт в зале", "Sanoq zalda", "Countdown is live")
    val untilStart: String get() = t("До начала", "Boshlanishgacha", "Starting soon")

    // ——— Слой поверх ———

    val cornerTL: String get() = t("Слева вверху", "Chap tepada", "Top left")
    val cornerTR: String get() = t("Справа вверху", "O‘ng tepada", "Top right")
    val cornerBL: String get() = t("Слева внизу", "Chap pastda", "Bottom left")
    val cornerBR: String get() = t("Справа внизу", "O‘ng pastda", "Bottom right")
    val examples: String get() = t("Примеры", "Namunalar", "Examples")
    val examplesNote: String get() = t("Готовые слои, чтобы посмотреть, как они появляются и уходят. Касание — сразу в зал.", "Ular qanday paydo bo‘lishi va ketishini ko‘rish uchun tayyor qatlamlar. Bosish — darhol zalga.", "Ready layers to see how they appear and leave. Tap to put one live.")
    val lowerThird: String get() = t("Плашка с именем", "Ism plashkasi", "Name banner")
    val nameHint: String get() = t("Имя: «Анна Иванова»", "Ism: «Aziza Karimova»", "Name: “Anna Smith”")
    val subtitleHint: String get() = t("Подпись: «Тема: весна»", "Yorliq: «Mavzu: bahor»", "Caption: “Topic: spring”")
    val needName: String get() = t("Нужно имя", "Ism kerak", "A name is needed")
    val draftsNote: String get() = t("Касание — сразу в зал. Долгое нажатие — убрать из списка.", "Bosish — darhol zalga. Uzoq bosish — ro‘yxatdan olib tashlash.", "Tap to put it live. Long press removes it from the list.")
    val ticker: String get() = t("Бегущая строка", "Yuguruvchi satr", "Ticker")
    val tickerHint: String get() = t("Объявление: «Сбор в 18:00 у входа»", "E’lon: «18:00 da kirishda yig‘ilish»", "Notice: “Meet at 18:00 at the entrance”")
    val slow: String get() = t("Медленно", "Sekin", "Slow")
    val normal: String get() = t("Обычно", "Odatiy", "Normal")
    val fast: String get() = t("Быстро", "Tez", "Fast")
    val needText: String get() = t("Нужен текст", "Matn kerak", "Text is needed")
    val clock: String get() = t("Часы", "Soat", "Clock")
    val clockCorner: String get() = t("Часы в углу", "Burchakda soat", "Clock in the corner")
    val withSeconds: String get() = t("С секундами", "Soniyalar bilan", "With seconds")
    val logoCorner: String get() = t("Логотип в углу", "Burchakda logotip", "Logo in the corner")
    val logoCornerSub: String get() = t("Картинка PNG с прозрачным фоном", "Shaffof fonli PNG rasm", "A PNG picture with a transparent background")
    val pickPicture: String get() = t("Выбрать картинку", "Rasm tanlash", "Pick a picture")
    val overlayNote: String get() = t("Слой виден поверх слайдов, фото и видео; «Чёрный» и «Логотип» убирают и его. Зрители в браузере видят его тоже.", "Qatlam slaydlar, rasm va video ustida ko‘rinadi; «Qora» va «Logotip» uni ham olib tashlaydi. Brauzerdagi tomoshabinlar ham uni ko‘radi.", "The layer shows over slides, photos and video; “Black” and “Logo” remove it too. Viewers in the browser see it as well.")
    val tickerLower: String get() = t("строка", "satr", "ticker")
    val clockLower: String get() = t("часы", "soat", "clock")
    val logoLower: String get() = t("логотип", "logotip", "logo")

    // ——— Видео и полный экран ———

    val fileUnavailableMoved: String get() = t("Файл недоступен: возможно, его переместили или удалили", "Fayl mavjud emas: ehtimol, u ko‘chirilgan yoki o‘chirilgan", "The file is unavailable: it may have been moved or deleted")
    val fit: String get() = t("Вписать", "Sig‘dirish", "Fit")
    val fill: String get() = t("Заполнить", "To‘ldirish", "Fill")
    val stretch: String get() = t("Растянуть", "Cho‘zish", "Stretch")
    val original: String get() = t("Исходный", "Asl", "Original")
    val barsBlack: String get() = t("Поля чёрные", "Qora chetlar", "Black bars")
    val blurred: String get() = t("Размытые", "Xira", "Blurred")
    val fromStart: String get() = t("С начала в зал", "Boshidan zalga", "From start to hall")
    val fromHere: String get() = t("В зал отсюда", "Shu yerdan zalga", "To hall from here")
    val videoInHall: String get() = t("Видео в зале", "Video zalda", "Video is live")

    // ——— Резервные копии ———

    val backupFolderSaved: String get() = t("Копия сохранена и в выбранную папку", "Nusxa tanlangan jildga ham saqlandi", "The copy is saved to the chosen folder too")
    val folderUnavailable: String get() = t("Папка недоступна", "Jild mavjud emas", "The folder is unavailable")
    val restoreFromFile: String get() = t("Восстановить из файла", "Fayldan tiklash", "Restore from file")
    val notBackupOrBroken: String get() = t("Это не копия Deixis или файл испорчен", "Bu Deixis nusxasi emas yoki fayl buzilgan", "This is not a Deixis copy, or the file is damaged")
    val backups: String get() = t("Резервные копии", "Zaxira nusxalar", "Backups")
    val noBackupsYet: String get() = t("Копий ещё нет", "Hali nusxalar yo‘q", "No copies yet")
    val lastBackup: String get() = t("Последняя: ", "Oxirgisi: ", "Last:")
    val offPhone: String get() = t("Копия вне телефона", "Telefondan tashqari nusxa", "Copy off the phone")
    val offPhoneOn: String get() = t("Копия вне телефона: включена", "Telefondan tashqari nusxa: yoqilgan", "Copy off the phone: on")
    val offPhoneSub: String get() = t("Папка на флешке, в Google Диске или «Документах»: переживёт потерю телефона", "Fleshka, Google Disk yoki «Hujjatlar»dagi jild: telefon yo‘qolsa ham saqlanadi", "A folder on a flash drive, Google Drive or “Documents”: survives losing the phone")
    val pickOtherFolder: String get() = t("Касание — выбрать другую папку", "Bosish — boshqa jildni tanlash", "Tap to choose another folder")
    val restoreFromBackup: String get() = t("Восстановить из копии", "Nusxadan tiklash", "Restore from a copy")
    val restoreFromFileDots: String get() = t("Восстановить из файла…", "Fayldan tiklash…", "Restore from file…")
    val restoreFromFileSub: String get() = t("С другого телефона или из папки", "Boshqa telefondan yoki jilddan", "From another phone or a folder")
    val backupNow: String get() = t("Сделать копию сейчас", "Hozir nusxa olish", "Back up now")
    val backupSaved: String get() = t("Копия сохранена", "Nusxa saqlandi", "Copy saved")
    val backupNotSaved: String get() = t("Копия не сохранилась", "Nusxa saqlanmadi", "The copy was not saved")
    val backupNote: String get() = t("В копии — библиотека, сетлисты, тексты песен, темы и настройки. Видео и фото не копируются: после восстановления на новом телефоне их нужно выбрать заново.", "Nusxada — kutubxona, ro‘yxatlar, qo‘shiq matnlari, mavzular va sozlamalar. Video va rasmlar nusxalanmaydi: yangi telefonda tiklangandan keyin ularni qaytadan tanlash kerak.", "The copy holds the library, setlists, song lyrics, themes and settings. Videos and photos are not copied: after restoring on a new phone they need to be picked again.")
    val noBackups: String get() = t("Копий пока нет.", "Hozircha nusxalar yo‘q.", "No copies yet.")
    val inFolderSuffix: String get() = t(" · в папке", " · jildda", " · in folder")
    val backupUnreadable: String get() = t("Копия не читается", "Nusxa o‘qilmaydi", "The copy cannot be read")
    val libraryRestored: String get() = t("Библиотека восстановлена", "Kutubxona tiklandi", "Library restored")
    val bkDaily: String get() = t("ежедневная", "kundalik", "daily")
    val bkManual: String get() = t("вручную", "qo‘lda", "manual")
    val bkBeforeRestore: String get() = t("перед восстановлением", "tiklashdan oldin", "before restore")
    val bkBroken: String get() = t("испорченный файл", "buzilgan fayl", "damaged file")
    val backupNoSpace: String get() = t("Копия не записалась: мало места на телефоне", "Nusxa yozilmadi: telefonda joy kam", "The copy was not written: the phone is low on space")
    val notBackupTooBig: String get() = t("Это не копия Deixis: файл слишком большой", "Bu Deixis nusxasi emas: fayl juda katta", "This is not a Deixis copy: the file is too big")
    val notBackup: String get() = t("Это не копия Deixis", "Bu Deixis nusxasi emas", "This is not a Deixis copy")

    // ——— Обновления ———

    val uptodate: String get() = t("Установлена последняя версия", "Oxirgi versiya o‘rnatilgan", "The latest version is installed")
    val checkFailed: String get() = t("Не удалось проверить", "Tekshirib bo‘lmadi", "Could not check")
    val autoChecked: String get() = t("Проверяется автоматически", "Avtomatik tekshiriladi", "Checked automatically")
    val manualCheck: String get() = t("Проверка по кнопке", "Tugma bo‘yicha tekshirish", "Checked on demand")
    val checkUpdates: String get() = t("Проверить обновления", "Yangilanishlarni tekshirish", "Check for updates")
    val updates: String get() = t("Обновления", "Yangilanishlar", "Updates")
    val playVersion: String get() = t("Эта версия из Google Play: обновления приходят через магазин.", "Bu versiya Google Play dan: yangilanishlar do‘kon orqali keladi.", "This version is from Google Play: updates come through the store.")
    val byButton: String get() = t("По кнопке", "Tugma bo‘yicha", "On demand")
    val automatically: String get() = t("Автоматически", "Avtomatik", "Automatically")
    val installPermission: String get() = t("Перед первой установкой Android попросит разрешить Deixis устанавливать приложения.", "Birinchi o‘rnatishdan oldin Android Deixis ga ilovalar o‘rnatishga ruxsat so‘raydi.", "Before the first install Android asks to allow Deixis to install apps.")
    val autoNote: String get() = t("Автоматически: проверка на GitHub при запуске и раз в сутки, установка — когда ничего не показывается.", "Avtomatik: ishga tushganda va kuniga bir marta GitHub da tekshirish, o‘rnatish — hech narsa ko‘rsatilmayotganda.", "Automatically: a check on GitHub at launch and once a day, install when nothing is shown.")
    val manualNoteUpd: String get() = t("По кнопке: Deixis выходит в сеть только когда нажата «Проверить обновления».", "Tugma bo‘yicha: Deixis faqat «Yangilanishlarni tekshirish» bosilganda tarmoqqa chiqadi.", "On demand: Deixis goes online only after “Check for updates”.")
    val whatsNew: String get() = t("Что нового", "Nima yangi", "What’s new")
    val screenNoUpdate: String get() = t("Этот экран не обновляется с пульта: старая версия Deixis или версия из Google Play. Новую версию можно поставить на нём вручную.", "Bu ekran pultdan yangilanmaydi: Deixis ning eski versiyasi yoki Google Play versiyasi. Yangi versiyani unda qo‘lda o‘rnatish mumkin.", "This screen does not update from the remote: an old Deixis version or one from Google Play. A new version can be installed on it manually.")
    val linkToScreen: String get() = t("Связь с экраном…", "Ekran bilan aloqa…", "Connecting to the screen…")
    val capTitle: String get() = t("Экран компьютера", "Kompyuter ekrani", "Computer screen")
    val capStart: String get() = t("Транслировать", "Translyatsiya", "Broadcast")
    val capStop: String get() = t("Остановить", "To‘xtatish", "Stop")
    val capCreate: String get() = t("Создать", "Yaratish", "Create")
    val capLive: String get() = t("в эфире", "efirda", "on air")
    val capHall: String get() = t("здесь зал Deixis", "bu yerda Deixis zali", "Deixis hall is here")
    val capVirtualRemove: String get() = t("Убрать виртуальный экран", "Virtual ekranni olib tashlash", "Remove the virtual screen")
    val capNote: String get() = t("Монитор, окно программы или виртуальный экран компьютера идёт в зал, на экраны в сети и зрителям. Holyrics, PowerPoint и другие программы выводят туда свой «проектор». Показ другого с пульта останавливает трансляцию.", "Kompyuterning monitori, dastur oynasi yoki virtual ekrani zalga, tarmoqdagi ekranlarga va tomoshabinlarga boradi. Holyrics, PowerPoint va boshqa dasturlar u yerga o‘z «proyektor»ini chiqaradi. Pultdan boshqasini ko‘rsatish translyatsiyani to‘xtatadi.", "A monitor, an app window or the computer’s virtual screen goes to the hall, to screens on the network and to viewers. Holyrics, PowerPoint and other apps send their “projector” there. Showing something else from the remote stops the broadcast.")
    val airPlayReceive: String get() = t("Принимать AirPlay", "AirPlay qabul qilish", "Receive AirPlay")
    val airPlayNote: String get() = t("iPhone, iPad и Mac покажут свой экран здесь через «Повтор экрана», без отдельных программ. Пока приём включён, показать экран может любое устройство Apple в этой сети; показ с пульта заменяет его сразу. Звук AirPlay пока не играется.", "iPhone, iPad va Mac o‘z ekranini bu yerda «Ekran takrori» orqali ko‘rsatadi, alohida dasturlarsiz. Qabul yoqilgan paytda bu tarmoqdagi istalgan Apple qurilmasi ekranni ko‘rsata oladi; pultdan ko‘rsatish uni darhol almashtiradi. AirPlay ovozi hozircha chalinmaydi.", "iPhone, iPad and Mac show their screen here through Screen Mirroring, with no extra apps. While this is on, any Apple device on this network can show its screen; showing from the remote replaces it at once. AirPlay sound does not play yet.")
    val screenUpdates: String get() = t("Обновления экрана", "Ekran yangilanishlari", "Screen updates")
    val screenUpdateNote: String get() = t("Установка идёт, только когда на экране ничего не показывается: окно системы появится на экране зала. На телевизоре его подтверждают пультом телевизора. После обновления экран откроется сам.", "O‘rnatish faqat ekranda hech narsa ko‘rsatilmayotganda boradi: tizim oynasi zal ekranida chiqadi. Televizorda u televizor pulti bilan tasdiqlanadi. Yangilangandan keyin ekran o‘zi ochiladi.", "Installing runs only when nothing is shown: the system window appears on the hall screen. On a TV it is confirmed with the TV remote. After the update the screen opens by itself.")

    // ——— Профили ———

    val profKindChurch: String get() = t("Церковь", "Cherkov", "Church")
    val profKindStudy: String get() = t("Учёба", "O‘qish", "Study")
    val profKindWork: String get() = t("Работа", "Ish", "Work")
    val profKindHome: String get() = t("Дом", "Uy", "Home")
    val profKindOther: String get() = t("Другое", "Boshqa", "Other")
    val mainProfile: String get() = t("Основной", "Asosiy", "Main")
    val profileNoSpace: String get() = t("Не удалось создать профиль: мало места на телефоне", "Profil yaratib bo‘lmadi: telefonda joy kam", "Could not create a profile: the phone is low on space")
    val profileCreateFailed: String get() = t("Не удалось создать профиль", "Profil yaratib bo‘lmadi", "Could not create a profile")
    val profilesNote: String get() = t("У каждого профиля своя библиотека, сетлисты, темы, логотип и вид пульта. Данные профилей не смешиваются. Экраны и Holyrics — общие.", "Har bir profilning o‘z kutubxonasi, ro‘yxatlari, mavzulari, logotipi va pult ko‘rinishi bor. Profillar ma’lumotlari aralashmaydi. Ekranlar va Holyrics — umumiy.", "Each profile has its own library, setlists, themes, logo and remote look. Profile data never mixes. Screens and Holyrics are shared.")
    val openLower: String get() = t("открыт", "ochiq", "open")
    val newProfile: String get() = t("Новый профиль", "Yangi profil", "New profile")
    val profilesHint: String get() = t("Касание — открыть профиль. Удержание — переименовать, сменить цвет или удалить.", "Bosish — profilni ochish. Bosib turish — nomini o‘zgartirish, rangini almashtirish yoki o‘chirish.", "Tap to open a profile. Hold to rename, recolour or delete it.")
    val purpose: String get() = t("Для чего", "Nima uchun", "Purpose")
    val churchProfileNote: String get() = t("С церковным разделом: Библия, песни, пульт Holyrics.", "Cherkov bo‘limi bilan: Bibliya, qo‘shiqlar, Holyrics pulti.", "With the church section: Bible, songs, Holyrics remote.")
    val nonChurchProfileNote: String get() = t("Без церковного раздела — его можно включить в настройках профиля.", "Cherkov bo‘limisiz — uni profil sozlamalarida yoqish mumkin.", "Without the church section; it can be turned on in the profile settings.")
    val color: String get() = t("Цвет", "Rang", "Colour")
    val createOpen: String get() = t("Создать и открыть", "Yaratish va ochish", "Create and open")
    val deleteProfile: String get() = t("Удалить профиль и его данные", "Profil va uning ma’lumotlarini o‘chirish", "Delete the profile and its data")
    val deleteProfileNote: String get() = t("Библиотека, сетлисты, темы и копии этого профиля удалятся с телефона. Другие профили не изменятся.", "Bu profilning kutubxonasi, ro‘yxatlari, mavzulari va nusxalari telefondan o‘chiriladi. Boshqa profillar o‘zgarmaydi.", "This profile’s library, setlists, themes and copies are deleted from the phone. Other profiles do not change.")
    val openProfileNote: String get() = t("Открытый профиль не удаляется: сначала — открыть другой.", "Ochiq profil o‘chirilmaydi: avval boshqasini ochish kerak.", "The open profile cannot be deleted: open another one first.")

    // ——— Отзыв и сбой ———

    val feedback: String get() = t("Отзыв", "Fikr-mulohaza", "Feedback")
    val reportProblem: String get() = t("Сообщить о проблеме", "Muammo haqida xabar berish", "Report a problem")
    val reportProblemSub: String get() = t("Текст с версией и устройством уже заполнен", "Versiya va qurilma bilan matn allaqachon to‘ldirilgan", "The text with the version and device is already filled in")
    val idea: String get() = t("Идея", "G‘oya", "Idea")
    val ideaSub: String get() = t("Что добавить или сделать удобнее", "Nimani qo‘shish yoki qulayroq qilish", "What to add or make easier")
    val copied: String get() = t("Скопировано", "Nusxalandi", "Copied")
    val feedbackNoteDesktop: String get() = t(
        "Текст с версией программы копируется: вставьте его в письмо, Telegram или любое сообщение автору.",
        "Dastur versiyasi bilan matn nusxalanadi: uni xatga, Telegramga yoki muallifga istalgan xabarga qo‘ying.",
        "The text with the app version is copied: paste it into an email, Telegram or any message to the author.",
    )
    val feedbackNote: String get() = t("Отправка через «Поделиться»: в Telegram, почту или любое приложение. Deixis сам ничего не отправляет.", "«Ulashish» orqali yuborish: Telegram, pochta yoki istalgan ilovaga. Deixis o‘zi hech narsa yubormaydi.", "Sent via “Share”: to Telegram, mail or any app. Deixis sends nothing by itself.")
    val problemSubject: String get() = t("Проблема в Deixis", "Deixis dagi muammo", "A problem in Deixis")
    val problemBody: String get() = t("Что случилось:\n\n\nЧто ожидалось:\n\n\nКак повторить:\n\n\n", "Nima bo‘ldi:\n\n\nNima kutilgan edi:\n\n\nQanday takrorlash mumkin:\n\n\n", "What happened:\n\n\nWhat was expected:\n\n\nHow to reproduce:\n\n\n")
    val ideaSubject: String get() = t("Идея для Deixis", "Deixis uchun g‘oya", "An idea for Deixis")
    val ideaBody: String get() = t("Идея:\n\n\nЗачем она нужна:\n\n\n", "G‘oya:\n\n\nNima uchun kerak:\n\n\n", "Idea:\n\n\nWhy it is needed:\n\n\n")
    val crashed: String get() = t("Deixis закрылся из-за ошибки", "Deixis xato tufayli yopildi", "Deixis closed because of an error")
    val crashBody: String get() = t("Что делалось перед этим:\n\n\n— Отчёт:\n", "Bundan oldin nima qilingan edi:\n\n\n— Hisobot:\n", "What was being done before this:\n\n\n— Report:\n")
    val crashNote: String get() = t("Отчёт уже заполнен: его можно отправить одним касанием.", "Hisobot allaqachon to‘ldirilgan: uni bir bosishda yuborish mumkin.", "The report is already filled in: it can be sent with one tap.")
    val sendReport: String get() = t("Отправить отчёт", "Hisobotni yuborish", "Send the report")

    // ——— Сообщения пульта ———

    val noSpace: String get() = t("Изменения не сохраняются: мало места на телефоне", "O‘zgarishlar saqlanmayapti: telefonda joy kam", "Changes are not being saved: the phone is low on space")
    val restoreAborted: String get() = t("Не удалось сохранить копию текущей библиотеки — восстановление отменено. Мало места на телефоне?", "Joriy kutubxona nusxasini saqlab bo‘lmadi — tiklash bekor qilindi. Telefonda joy kammi?", "Could not save a copy of the current library, so the restore was cancelled. Is the phone low on space?")
    val libRecovered: String get() = t("Файл библиотеки был испорчен: она восстановлена из последней резервной копии", "Kutubxona fayli buzilgan edi: u oxirgi zaxira nusxadan tiklandi", "The library file was damaged: it was restored from the latest backup")
    val wrongPin: String get() = t("Неверный PIN", "PIN noto‘g‘ri", "Wrong PIN")
    val tooMany: String get() = t("Слишком много попыток. Через полминуты — новый PIN с экрана", "Urinishlar juda ko‘p. Yarim daqiqadan keyin — ekrandagi yangi PIN", "Too many attempts. In half a minute, use the new PIN on the screen")
    val oldVersion: String get() = t("Версии Deixis не совпадают: нужно обновить приложения", "Deixis versiyalari mos emas: ilovalarni yangilash kerak", "Deixis versions differ: the apps need updating")
    /** Сертификат экрана не совпал с запомненным (шифрование пульт ↔ экран). */
    fun untrustedScreen(name: String): String = t(
        "«$name»: по этому адресу отвечает другое устройство. Если экран переустановлен — подключение заново по QR-коду с экрана",
        "«$name»: bu manzilda boshqa qurilma javob bermoqda. Ekran qayta o‘rnatilgan bo‘lsa — ekrandagi QR-kod orqali qayta ulanish",
        "“$name”: another device answers at this address. If the screen was reinstalled, connect again with the QR code on the screen",
    )
    val unknownToken: String get() = t("Приёмник не узнал телефон", "Qabul qiluvchi telefonni tanimadi", "The receiver did not recognise the phone")
    val tvNoAnswer: String get() = t("Телевизор не ответил", "Televizor javob bermadi", "The TV did not answer")
    val showFailedLower: String get() = t("не удалось показать", "ko‘rsatib bo‘lmadi", "could not show")
    val themeBgUnavailable: String get() = t("Фон темы недоступен", "Mavzu foni mavjud emas", "The theme background is unavailable")
    val bgFileUnavailable: String get() = t("Файл фона недоступен", "Fon fayli mavjud emas", "The background file is unavailable")
    val computerLoc: String get() = t("компьютере", "kompyuter", "computer")
    val docsNeedPdf: String get() = t("Документы переводит компьютер с Microsoft Office или LibreOffice: нужен файл PDF", "Hujjatlarni Microsoft Office yoki LibreOffice o‘rnatilgan kompyuter o‘giradi: PDF fayl kerak", "Documents are converted by a computer with Microsoft Office or LibreOffice: a PDF file is needed")
    val pptxNeedPdf: String get() = t("Этот экран не переводит презентации: нужен файл PDF или подключённый компьютер", "Bu ekran taqdimotlarni o‘girmaydi: PDF fayl yoki ulangan kompyuter kerak", "This screen does not convert presentations: a PDF file or a connected computer is needed")
    val unsupported: String get() = t("Этот формат не поддерживается", "Bu format qo‘llab-quvvatlanmaydi", "This format is not supported")
    val cantOpenFile: String get() = t("Не удалось открыть файл", "Faylni ochib bo‘lmadi", "Could not open the file")
    val cantAddModule: String get() = t("Не удалось добавить модуль", "Modul qo‘shib bo‘lmadi", "Could not add the module")
    val screenNoAudio: String get() = t("Экран не принимает звук (нужен новый Deixis на экране): картинка идёт без звука", "Ekran ovozni qabul qilmaydi (ekranda yangi Deixis kerak): tasvir ovozsiz boradi", "The screen does not accept sound (it needs a newer Deixis): the picture goes without sound")
    val phoneSoundNA: String get() = t("звук телефона недоступен", "telefon ovozi mavjud emas", "phone sound unavailable")
    val tvNotPicked: String get() = t("Телевизор не выбран", "Televizor tanlanmagan", "No TV selected")
    val phoneNoWifi: String get() = t("Телефон не в сети Wi-Fi", "Telefon Wi-Fi tarmog‘ida emas", "The phone is not on Wi-Fi")
    val dlnaNoScreen: String get() = t("Демонстрация экрана на DLNA-телевизор не идёт: нужен экран Deixis", "DLNA televizorga ekranni ko‘rsatib bo‘lmaydi: Deixis ekrani kerak", "Screen sharing does not work on a DLNA TV: a Deixis screen is needed")

    // ——— Трансляция экрана и служба связи ———

    val noLinkScreen: String get() = t("Нет связи с экраном", "Ekran bilan aloqa yo‘q", "No connection to the screen")
    val noCapturePermission: String get() = t("Нет разрешения на захват экрана", "Ekranni yozib olishga ruxsat yo‘q", "No permission to capture the screen")
    val shareFailed: String get() = t("Не удалось начать трансляцию экрана", "Ekran translyatsiyasini boshlab bo‘lmadi", "Could not start screen sharing")
    val shareChannel: String get() = t("Трансляция экрана", "Ekran translyatsiyasi", "Screen sharing")
    val sharing: String get() = t("Экран транслируется", "Ekran translyatsiya qilinmoqda", "The screen is being shared")
    val sharingSound: String get() = t("Экран и звук телефона — в зале", "Telefon ekrani va ovozi — zalda", "The phone screen and sound are in the hall")
    val sharingNoSound: String get() = t("Всё, что на экране телефона, видно в зале", "Telefon ekranidagi hamma narsa zalda ko‘rinadi", "Everything on the phone screen is visible in the hall")
    val stopAction: String get() = t("Остановить", "To‘xtatish", "Stop")
    val encoderStopped: String get() = t("Трансляция остановилась: кодировщик видео телефона занят или дал сбой", "Translyatsiya to‘xtadi: telefon video kodlovchisi band yoki ishdan chiqdi", "Sharing stopped: the phone video encoder is busy or failed")
    val soundNA: String get() = t("Звук телефона недоступен для трансляции", "Telefon ovozini translyatsiya qilib bo‘lmaydi", "Phone sound is unavailable for sharing")
    val linkChannel: String get() = t("Связь с экранами", "Ekranlar bilan aloqa", "Connection to screens")
    val linkInBackground: String get() = t("Связь держится и в фоне. Отключить — в «Устройствах»", "Aloqa fonda ham saqlanadi. Uzish — «Qurilmalar»da", "The connection stays on in the background. Disconnect under “Devices”")

    // ——— Holyrics ———

    val workMode: String get() = t("Режим работы", "Ish rejimi", "Work mode")
    val holyricsRemote: String get() = t("Пульт Holyrics", "Holyrics pulti", "Holyrics remote")
    val holyricsModeNote: String get() = t("Пульт Holyrics: показ, плейлист и база — в Holyrics на компьютере. Своя библиотека Deixis ждёт в режиме «Deixis».", "Holyrics pulti: ko‘rsatish, pleylist va baza — kompyuterdagi Holyrics da. Deixis ning o‘z kutubxonasi «Deixis» rejimida kutadi.", "Holyrics remote: the show, playlist and database live in Holyrics on the computer. The Deixis library waits in “Deixis” mode.")
    val deixisModeNote: String get() = t("Deixis: своя библиотека, сетлисты и экраны. Песни Holyrics можно перенести в библиотеку в окне Holyrics.", "Deixis: o‘z kutubxonasi, ro‘yxatlari va ekranlari. Holyrics qo‘shiqlarini Holyrics oynasida kutubxonaga ko‘chirish mumkin.", "Deixis: its own library, setlists and screens. Holyrics songs can be moved to the library in the Holyrics window.")
    val holyricsConnectNote: String get() = t("Пульт Holyrics: связь с Holyrics на компьютере — поиском в сети или по QR-коду в Deixis для компьютера.", "Holyrics pulti: kompyuterdagi Holyrics bilan aloqa — tarmoqda qidirish yoki kompyuter uchun Deixis dagi QR-kod orqali.", "Holyrics remote: connect to Holyrics on the computer by searching the network or with the QR code in Deixis for computer.")
    val connectHolyrics: String get() = t("Подключить Holyrics", "Holyrics ni ulash", "Connect Holyrics")
    val holyricsPlaylist: String get() = t("Плейлист Holyrics", "Holyrics pleylisti", "Holyrics playlist")
    val songToPlaylist: String get() = t("Песню в плейлист", "Qo‘shiqni pleylistga", "Song to the playlist")
    val toHolyricsPlaylist: String get() = t("В плейлист Holyrics", "Holyrics pleylistiga", "To the Holyrics playlist")
    val playlistEmpty: String get() = t("Плейлист Holyrics пуст. Плюс — добавить песню из базы Holyrics.", "Holyrics pleylisti bo‘sh. Plyus — Holyrics bazasidan qo‘shiq qo‘shish.", "The Holyrics playlist is empty. Plus adds a song from the Holyrics database.")
    val slidesLoading: String get() = t("Слайды загружаются…", "Slaydlar yuklanmoqda…", "Loading slides…")
    val showInHolyrics: String get() = t("Показать в Holyrics", "Holyrics da ko‘rsatish", "Show in Holyrics")
    val removeFromPlaylist: String get() = t("Убрать из плейлиста", "Pleylistdan olib tashlash", "Remove from playlist")
    val removeFromPlaylistNote: String get() = t("Песня останется в базе Holyrics: убирается только из плейлиста.", "Qo‘shiq Holyrics bazasida qoladi: faqat pleylistdan olib tashlanadi.", "The song stays in the Holyrics database: it leaves the playlist only.")
    val songInHolyricsDb: String get() = t("Песня в базе Holyrics", "Holyrics bazasidagi qo‘shiq", "Song in the Holyrics database")
    val toPlaylist: String get() = t("В плейлист", "Pleylistga", "To playlist")
    val noNewSongs: String get() = t("Новые песни в саму базу Holyrics с телефона не записать: Holyrics разрешает это только с подпиской Holyrics Plan.", "Telefondan Holyrics bazasiga yangi qo‘shiq yozib bo‘lmaydi: Holyrics buni faqat Holyrics Plan obunasi bilan ruxsat beradi.", "New songs cannot be written to the Holyrics database from the phone: Holyrics allows it only with a Holyrics Plan subscription.")
    val f10Black: String get() = t("F10 — чёрный экран", "F10 — qora ekran", "F10 — black screen")
    val f8Wallpaper: String get() = t("F8 — обои", "F8 — fon rasmi", "F8 — wallpaper")
    val f9Empty: String get() = t("F9 — пустой экран", "F9 — bo‘sh ekran", "F9 — empty screen")
    val holyricsMore: String get() = t("Ещё: текст, отсчёт, объявления", "Yana: matn, sanoq, e’lonlar", "More: text, countdown, notices")
    val moreInHolyrics: String get() = t("Ещё в Holyrics", "Holyrics da yana", "More in Holyrics")
    val holyricsDb: String get() = t("База Holyrics", "Holyrics bazasi", "Holyrics database")
    val searchHolyricsDb: String get() = t("Поиск в базе Holyrics", "Holyrics bazasida qidirish", "Search the Holyrics database")
    val allAnnouncements: String get() = t("Все объявления по кругу", "Barcha e’lonlar navbat bilan", "All notices in a loop")
    val noAutoPres: String get() = t("Автопрезентаций нет", "Avtotaqdimotlar yo‘q", "No auto presentations")
    val autoPresSub: String get() = t("с музыкой и сменой слайдов по времени", "musiqa va vaqt bo‘yicha slayd almashinuvi bilan", "with music and timed slide changes")
    val holyricsDbNote: String get() = t("Касание — сразу в Holyrics, с темой из ленты на главном, если она выбрана. Плюс у песни — в плейлист Holyrics. База Holyrics здесь только читается.", "Bosish — darhol Holyrics ga, agar bosh sahifadagi tasmada mavzu tanlangan bo‘lsa, o‘sha mavzu bilan. Qo‘shiqdagi plyus — Holyrics pleylistiga. Holyrics bazasi bu yerda faqat o‘qiladi.", "Tap to send straight to Holyrics, with the theme from the strip on the main screen if one is picked. Plus on a song adds it to the Holyrics playlist. The Holyrics database is read-only here.")
    val holyricsPlaylists: String get() = t("Плейлисты Holyrics", "Holyrics pleylistlari", "Holyrics playlists")
    val noSavedPlaylists: String get() = t("Сохранённых плейлистов нет", "Saqlangan pleylistlar yo‘q", "No saved playlists")
    val openInHolyrics: String get() = t("Открыть в Holyrics", "Holyrics da ochish", "Open in Holyrics")
    val appendToPlaylist: String get() = t("Дописать в текущий плейлист", "Joriy pleylistga qo‘shish", "Append to the current playlist")
    val openReplacesNote: String get() = t("«Открыть» заменит плейлист, который сейчас открыт в Holyrics.", "«Ochish» hozir Holyrics da ochiq pleylistni almashtiradi.", "“Open” replaces the playlist open in Holyrics now.")
    val playlistsNote: String get() = t("Плейлисты сохраняются в самом Holyrics на компьютере.", "Pleylistlar kompyuterdagi Holyrics ning o‘zida saqlanadi.", "Playlists are saved in Holyrics itself on the computer.")
    val eachOwnTheme: String get() = t("у каждой песни и текста своя", "har bir qo‘shiq va matnning o‘zi", "each song and text has its own")
    val themeAsHolyrics: String get() = t("Тема — как задано в Holyrics. Другую выбирают в ленте тем на главном.", "Mavzu — Holyrics da berilganidek. Boshqasi bosh sahifadagi mavzular tasmasida tanlanadi.", "The theme is as set in Holyrics. Another one is picked in the theme strip on the main screen.")
    val quickText: String get() = t("Быстрый текст", "Tez matn", "Quick text")
    val whatToShow: String get() = t("Что показать", "Nimani ko‘rsatish", "What to show")
    val toScreen: String get() = t("На экран", "Ekranga", "To screen")
    val howLong: String get() = t("На сколько", "Qancha vaqtga", "For how long")
    val aboveCountdown: String get() = t("Текст над отсчётом", "Sanoq ustidagi matn", "Text above the countdown")
    val startCountdown: String get() = t("Запустить отсчёт", "Sanoqni boshlash", "Start the countdown")
    val allInLoop: String get() = t("Все по кругу", "Hammasi navbat bilan", "All in a loop")
    val alert: String get() = t("Тревога", "Ogohlantirish", "Alert")
    val alertHint: String get() = t("Строка поверх всего: «Машина у входа»", "Hamma narsa ustidan satr: «Kirishda mashina»", "A line over everything: “Car at the entrance”")
    val stageTimer: String get() = t("Таймер для сцены", "Sahna uchun taymer", "Stage timer")
    val stageTimerNote: String get() = t("Таймер виден на панели для сцены Holyrics, в зале его нет.", "Taymer Holyrics sahna panelida ko‘rinadi, zalda u yo‘q.", "The timer shows on the Holyrics stage panel, not in the hall.")
    val holyricsFavorites: String get() = t("Избранное Holyrics", "Holyrics sevimlilari", "Holyrics favourites")
    val bibleMapNote: String get() = t("Стихи выбираются в Deixis, а показываются в Holyrics — его переводом. Здесь видно, какой перевод Holyrics отвечает каждому модулю Deixis.", "Oyatlar Deixis da tanlanadi, Holyrics da esa uning tarjimasi bilan ko‘rsatiladi. Bu yerda har bir Deixis moduliga Holyrics ning qaysi tarjimasi mos kelishi ko‘rinadi.", "Verses are picked in Deixis and shown in Holyrics with its translation. Here each Deixis module shows its matching Holyrics translation.")
    val modulesToHolyrics: String get() = t("Модули Deixis → Holyrics", "Deixis modullari → Holyrics", "Deixis modules → Holyrics")
    val autoMatchedSuffix: String get() = t(" · подобран", " · tanlangan", " · matched")
    val noSuchInHolyrics: String get() = t("в Holyrics такого перевода нет", "Holyrics da bunday tarjima yo‘q", "Holyrics has no such translation")
    val noModulesInDeixis: String get() = t("В Deixis пока нет модулей Библии", "Deixis da hozircha Bibliya modullari yo‘q", "Deixis has no Bible modules yet")
    val likeHolyrics: String get() = t("Как в Holyrics", "Holyrics dagidek", "As in Holyrics")
    val likeDeixis: String get() = t("Как в Deixis", "Deixis dagidek", "As in Deixis")
    val likeNote: String get() = t("«Как в Holyrics» — переводы из вкладок Библии Holyrics становятся выбранными здесь. «Как в Deixis» — выбранные здесь переводы ставятся во вкладки Holyrics.", "«Holyrics dagidek» — Holyrics Bibliya yorliqlaridagi tarjimalar bu yerda tanlanadi. «Deixis dagidek» — bu yerda tanlangan tarjimalar Holyrics yorliqlariga qo‘yiladi.", "“As in Holyrics” picks the translations from the Holyrics Bible tabs here. “As in Deixis” puts the translations picked here into the Holyrics tabs.")
    val inHolyricsNotDeixis: String get() = t("Есть в Holyrics, нет в Deixis", "Holyrics da bor, Deixis da yo‘q", "In Holyrics, not in Deixis")
    val holyricsModulesNote: String get() = t("Чтобы листать и искать их в Deixis, нужен MyBible-модуль того же перевода: «+» в строке переводов на вкладке «Библия». Holyrics не отдаёт текст своих модулей через API.", "Ularni Deixis da varaqlash va qidirish uchun shu tarjimaning MyBible moduli kerak: «Bibliya» bo‘limidagi tarjimalar qatoridagi «+». Holyrics o‘z modullari matnini API orqali bermaydi.", "To browse and search them in Deixis, a MyBible module of the same translation is needed: “+” in the translation row on the Bible tab. Holyrics does not share its module text through the API.")
    val addModuleToDeixis: String get() = t("Добавить модуль в Deixis", "Deixis ga modul qo‘shish", "Add a module to Deixis")
    val holyricsTranslation: String get() = t("Перевод Holyrics", "Holyrics tarjimasi", "Holyrics translation")
    val autoMatch: String get() = t("Подбирать самому", "Avtomatik tanlash", "Match automatically")
    val autoMatchSub: String get() = t("по сокращению, названию и языку", "qisqartma, nom va til bo‘yicha", "by abbreviation, name and language")
    val connectedLower: String get() = t("подключён", "ulangan", "connected")
    val missingRights: String get() = t("Не хватает прав у токена", "Tokenga ruxsatlar yetishmaydi", "The token lacks permissions")
    val rightsPending: String get() = t("На компьютере Holyrics показал уведомление — там нужно «Разрешить». ", "Kompyuterda Holyrics bildirishnoma ko‘rsatdi — u yerda «Ruxsat berish» kerak. ", "Holyrics showed a notification on the computer: “Allow” is needed there.")
    val rightsManual: String get() = t("В Holyrics: Файл → Настройки → API Server → Управление правами — для этого токена отметить всё. ", "Holyrics da: File → Settings → API Server → Ruxsatlarni boshqarish — bu token uchun hammasini belgilash. ", "In Holyrics: File → Settings → API Server → Manage permissions, and tick everything for this token.")
    val checkAgain: String get() = t("Проверить снова", "Qayta tekshirish", "Check again")
    val qrHowto: String get() = t("Проще всего — QR-код: в Deixis для компьютера рядом с Holyrics — «Holyrics → Телефон по QR», и камеру телефона на код. ", "Eng osoni — QR-kod: Holyrics yonidagi kompyuter uchun Deixis da «Holyrics → Telefon QR orqali», va telefon kamerasini kodga qaratish. ", "The simplest way is the QR code: in Deixis for computer next to Holyrics, “Holyrics → Phone by QR”, then point the phone camera at the code.")
    val manualHowto: String get() = t("Или вручную: в Holyrics «Файл → Настройки → API Server» — доступ по локальной сети, новый токен и права для него.", "Yoki qo‘lda: Holyrics da «File → Settings → API Server» — mahalliy tarmoq orqali kirish, yangi token va unga ruxsatlar.", "Or manually: in Holyrics “File → Settings → API Server”, enable local network access, a new token and its permissions.")
    val searchingHolyrics: String get() = t("Ищем Holyrics в сети…", "Tarmoqda Holyrics qidirilmoqda…", "Looking for Holyrics on the network…")
    val findHolyrics: String get() = t("Найти Holyrics в сети", "Tarmoqda Holyrics ni topish", "Find Holyrics on the network")
    val holyricsNotFound: String get() = t("Holyrics не найден. Нужны API Server и доступ по локальной сети в Holyrics; телефон и компьютер — в одной сети Wi-Fi.", "Holyrics topilmadi. Holyrics da API Server va mahalliy tarmoq orqali kirish kerak; telefon va kompyuter — bitta Wi-Fi tarmog‘ida.", "Holyrics not found. Holyrics needs API Server and local network access; the phone and the computer must be on the same Wi-Fi.")
    val holyricsAddress: String get() = t("Адрес компьютера или ссылка deixis-holyrics://", "Kompyuter manzili yoki deixis-holyrics:// havolasi", "Computer address or a deixis-holyrics:// link")
    val port: String get() = t("Порт", "Port", "Port")
    val token: String get() = t("Токен", "Token", "Token")
    val likeInSong: String get() = t("Как в песне или тексте", "Qo‘shiq yoki matndagidek", "As in the song or text")
    val importAll: String get() = t("Перенести всё в Deixis", "Hammasini Deixis ga ko‘chirish", "Move everything to Deixis")
    val importNote: String get() = t("Песни, тексты, темы и плейлист станут своими в библиотеке Deixis и останутся без Holyrics. Повторный перенос обновит их, а не добавит копии.", "Qo‘shiqlar, matnlar, mavzular va pleylist Deixis kutubxonasiga o‘tadi va Holyrics bo‘lmasa ham qoladi. Qayta ko‘chirish nusxa qo‘shmaydi, ularni yangilaydi.", "Songs, texts, themes and the playlist become part of the Deixis library and stay without Holyrics. Moving again updates them rather than adding copies.")
    val qrForPhone: String get() = t("QR для телефона", "Telefon uchun QR", "QR for a phone")
    val qrNote: String get() = t("Камера второго телефона с Deixis — на этот код. Он даёт управление Holyrics: только для своих.", "Deixis o‘rnatilgan ikkinchi telefon kamerasini shu kodga qaratish. U Holyrics ni boshqarishga imkon beradi: faqat o‘zimizdagilar uchun.", "Point the camera of a second phone with Deixis at this code. It gives control of Holyrics: for trusted people only.")
    val bgPhotoLower: String get() = t("фон-фото", "fon-rasm", "photo bg")
    val nothingInHolyrics: String get() = t("В Holyrics ничего не показано", "Holyrics da hech narsa ko‘rsatilmagan", "Nothing shown in Holyrics")
    val noLinkHolyrics: String get() = t("Нет связи с Holyrics", "Holyrics bilan aloqa yo‘q", "No connection to Holyrics")
    val rightsAsk: String get() = t("Holyrics просит подтвердить права для Deixis: «Разрешить» в уведомлении на компьютере", "Holyrics Deixis uchun ruxsatlarni tasdiqlashni so‘rayapti: kompyuterdagi bildirishnomada «Ruxsat berish»", "Holyrics asks to confirm permissions for Deixis: “Allow” in the notification on the computer")
    val themesPlaylist: String get() = t("Темы и плейлист…", "Mavzular va pleylist…", "Themes and playlist…")
    val importPlaylistSuffix: String get() = t(", плейлист — сетлист «Плейлист Holyrics»", ", pleylist — «Holyrics pleylisti» ro‘yxati", ", the playlist as the “Holyrics playlist” setlist")
    val phoneNoWifiHolyrics: String get() = t("Телефон не в сети Wi-Fi: Holyrics ищется в той же сети", "Telefon Wi-Fi tarmog‘ida emas: Holyrics shu tarmoqda qidiriladi", "The phone is not on Wi-Fi: Holyrics is searched for on the same network")
    val songNotFoundHolyrics: String get() = t("Песня не найдена в Holyrics", "Qo‘shiq Holyrics da topilmadi", "Song not found in Holyrics")
    val textNotFoundHolyrics: String get() = t("Текст не найден в Holyrics", "Matn Holyrics da topilmadi", "Text not found in Holyrics")
    val cantTakeFile: String get() = t("Не удалось взять файл из Holyrics", "Holyrics dan faylni olib bo‘lmadi", "Could not take the file from Holyrics")
    val shownInHolyrics: String get() = t("Показано в Holyrics. На экран Deixis фото и видео Holyrics попадают через Deixis для компьютера рядом с Holyrics", "Holyrics da ko‘rsatildi. Holyrics rasm va videolari Deixis ekraniga Holyrics yonidagi kompyuter uchun Deixis orqali tushadi", "Shown in Holyrics. Holyrics photos and videos reach a Deixis screen through Deixis for computer next to Holyrics")
    val noBookInHolyrics: String get() = t("Этой книги нет в Библии Holyrics", "Bu kitob Holyrics Bibliyasida yo‘q", "This book is not in the Holyrics Bible")
    val noTabsForModules: String get() = t("Для этих модулей в Holyrics нет переводов", "Bu modullar uchun Holyrics da tarjimalar yo‘q", "Holyrics has no translations for these modules")
    val needHolyricsLink: String get() = t("Сначала нужна связь с Holyrics", "Avval Holyrics bilan aloqa kerak", "A connection to Holyrics is needed first")
    val errorLower: String get() = t("ошибка", "xato", "error")

    // ——— Виды элементов ———

    fun kindTitle(k: Kind): String = when (k) {
        Kind.PRESENTATION -> tPres
        Kind.VIDEO -> tVideo
        Kind.PHOTO -> tPhoto
        Kind.FILE -> tFile
        Kind.SONG -> tSong
        Kind.TEXT -> tText
        Kind.BIBLE -> tabBible
        Kind.COUNTDOWN -> tCountdown
    }

    fun kindPlural(k: Kind): String = when (k) {
        Kind.PRESENTATION -> pPres
        Kind.VIDEO -> t("Видео", "Videolar", "Videos")
        Kind.PHOTO -> t("Фото", "Rasmlar", "Photos")
        Kind.FILE -> pFile
        Kind.SONG -> pSong
        Kind.TEXT -> pText
        Kind.BIBLE -> tabBible
        Kind.COUNTDOWN -> pCountdown
    }

    // ——— Единицы ———

    val gb: String get() = t("ГБ", "GB", "GB")
    val mb: String get() = t("МБ", "MB", "MB")

    // ——— Язык: названия языков пишутся на своём языке ———

    /** Варианты в настройках: код (null — как в системе) и подпись. */
    val languages: List<Pair<String?, String>> get() = listOf(null to langSystem, "ru" to "Русский", "uz" to "O‘zbekcha", "en" to "English")

    // ——— Шаблоны: главный экран и библиотека ———

    fun slidesN(n: Int): String = t("$n " + ruPlural(n, "слайд", "слайда", "слайдов"), "$n ta slayd", if (n == 1) "1 slide" else "$n slides")
    fun pagesN(n: Any?): String = t("$n стр.", "$n bet", "$n p.")
    fun slideN(n: Any?): String = t("Слайд $n", "Slayd $n", "Slide $n")
    fun nOfM(n: Any?, m: Any?): String = t("$n из $m", "$n / $m", "$n of $m")
    fun slideNofM(n: Any?, m: Any?): String = t("слайд $n из $m", "slayd $n / $m", "slide $n of $m")
    fun viewersN(n: Any?): String = t("Зрители: $n", "Tomoshabinlar: $n", "Viewers: $n")
    fun themeLive(name: Any?): String = t("Тема «$name» — в зале", "«$name» mavzusi — zalda", "Theme “$name” is live")
    fun liveNow(name: Any?): String = t("Сейчас в зале: $name", "Hozir zalda: $name", "Live now: $name")
    fun previewOf(name: Any?): String = t("Предпросмотр: $name", "Oldindan ko‘rish: $name", "Preview: $name")
    fun sendingAheadN(n: Any?): String = t("Передаются в фоне: $n, в зале ничего не меняется", "Fonda yuborilmoqda: $n, zalda hech narsa o‘zgarmaydi", "Sending in the background: $n; nothing changes in the hall")
    fun themeToItem(name: Any?, title: Any?): String = t("Тема «$name» — «$title»", "«$name» mavzusi — «$title»", "Theme “$name” for “$title”")
    fun themeToSlide(name: Any?, n: Any?): String = t("Тема «$name» — слайду $n", "«$name» mavzusi — $n-slaydga", "Theme “$name” for slide $n")
    fun sendAheadOf(title: Any?): String = t("Отправить «$title» на экраны заранее", "«$title» ni ekranlarga oldindan yuborish", "Send “$title” to the screens in advance")
    fun itemsShort(n: Any?): String = t("$n элем.", "$n ta element", "$n items")
    fun addedItem(name: Any?): String = t("Добавлено: $name", "Qo‘shildi: $name", "Added: $name")
    fun deleteQ(name: Any?): String = t("Удалить «$name»?", "«$name» o‘chirilsinmi?", "Delete “$name”?")
    fun deleteNamed(name: Any?): String = t("Удалить $name", "$name ni o‘chirish", "Delete $name")
    fun largeNote(name: Any?): String = t(
        "«$name» больше 1 ГБ: такой файл долго передавать. Его можно показать через трансляцию экрана: файл откроется в проигрывателе телефона, а в зал пойдёт экран.",
        "«$name» 1 GB dan katta: bunday faylni uzatish uzoq davom etadi. Uni ekran translyatsiyasi orqali ko‘rsatish mumkin: fayl telefon pleyerida ochiladi, zalga esa ekran chiqadi.",
        "“$name” is over 1 GB: such a file takes long to send. It can be shown via screen sharing: the file opens in the phone player and the screen goes to the hall.",
    )
    fun firstNofM(n: Any?, m: Any?): String = t("Показаны первые $n из $m: точнее запрос — меньше лишнего.", "$m tadan dastlabki $n tasi ko‘rsatildi: so‘rov aniqroq bo‘lsa, ortiqchasi kamroq.", "The first $n of $m are shown: a narrower query gives fewer extras.")

    // ——— Шаблоны: сетлисты ———

    fun onMainSet(name: Any?): String = t("На главном: $name", "Bosh sahifada: $name", "On main: $name")
    fun deleteSetNamed(name: Any?): String = t("Удалить сетлист «$name»", "«$name» ro‘yxatini o‘chirish", "Delete setlist “$name”")
    fun setDeleting(name: Any?): String = t("«$name» удаляется. Песни, тексты и файлы из него остаются в библиотеке.", "«$name» o‘chiriladi. Undagi qo‘shiqlar, matnlar va fayllar kutubxonada qoladi.", "“$name” is deleted. Its songs, texts and files stay in the library.")
    fun copyCreatedFor(date: Any?): String = t("Копия создана на $date", "$date uchun nusxa yaratildi", "Copy created for $date")

    // ——— Шаблоны: устройства, профили, служба связи ———

    fun screenNamed(name: Any?): String = t("Экран «$name»", "«$name» ekrani", "Screen “$name”")
    fun pinFor(name: Any?): String = t("PIN — тот, что на экране «$name». Нужен только при первом подключении.", "PIN — «$name» ekranidagisi. U faqat birinchi ulanishda kerak.", "The PIN is the one on “$name”. It is needed only the first time.")
    fun profileNamed(name: Any?): String = t("Профиль «$name»", "«$name» profili", "Profile “$name”")
    fun profileN(n: Any?): String = t("Профиль $n", "Profil $n", "Profile $n")
    fun profilesSub(n: Any?): String = t("Своя библиотека и вид · профилей: $n", "O‘z kutubxonasi va ko‘rinishi · profillar: $n", "Own library and look · profiles: $n")
    fun waitApproval(name: Any?): String = t("PIN верный. Ждём «Разрешить» на экране «$name» или на пульте оператора", "PIN to‘g‘ri. «$name» ekranida yoki operator pultida «Ruxsat berish» kutilmoqda", "PIN accepted. Waiting for “Allow” on “$name” or on the operator’s remote")
    fun wantsScreen(name: Any?, address: Any?): String = t("Просит экран «$name» · $address", "«$name» ekranini so‘ramoqda · $address", "Asks for “$name” · $address")
    fun notApproved(name: Any?): String = t("Экран «$name» не разрешил подключение", "«$name» ekrani ulanishga ruxsat bermadi", "Screen “$name” did not allow the connection")
    fun screenConnected(name: Any?): String = t("Подключён экран «$name»", "«$name» ekrani ulangan", "Screen “$name” connected")
    fun screensConnected(n: Any?): String = t("Подключено экранов: $n", "Ulangan ekranlar: $n", "Screens connected: $n")
    fun connectedTo(name: Any?): String = t("$name: подключено", "$name: ulandi", "$name: connected")
    fun noLinkWith(name: Any?): String = t("Нет связи с «$name»", "«$name» bilan aloqa yo‘q", "No connection to “$name”")
    fun alreadyConnected(name: Any?): String = t("«$name» уже подключён", "«$name» allaqachon ulangan", "“$name” is already connected")

    // ——— Шаблоны: оформление ———

    fun themeNamed(name: Any?): String = t("Тема «$name»", "«$name» mavzusi", "Theme “$name”")
    fun themeSaved(name: Any?): String = t("Тема «$name» сохранена", "«$name» mavzusi saqlandi", "Theme “$name” saved")
    fun copyOf(name: Any?): String = t("$name — копия", "$name — nusxa", "$name — copy")
    fun bgNamed(name: Any?): String = t("Фон: $name", "Fon: $name", "Background: $name")
    fun videoBgNamed(name: Any?): String = t("Видеофон: $name", "Video fon: $name", "Video background: $name")
    fun colorNamed(name: Any?): String = t("Цвет: $name", "Rang: $name", "Colour: $name")
    fun lowerThirdColon(x: Any?): String = t("плашка: $x", "plashka: $x", "banner: $x")

    // ——— Шаблоны: Писание ———

    fun openRef(x: Any?): String = t("Открыть: $x", "Ochish: $x", "Open: $x")
    fun chapterN(n: Any?): String = t("Глава $n", "$n-bob", "Chapter $n")
    fun inHallColon(x: Any?): String = t("В зале: $x", "Zalda: $x", "Live: $x")
    fun selectedColon(x: Any?): String = t("Выбрано: $x", "Tanlandi: $x", "Selected: $x")
    fun inSetColon(x: Any?): String = t("В сетлисте: $x", "Ro‘yxatda: $x", "In setlist: $x")
    fun andMore(n: Any?): String = t("…и ещё $n", "…va yana $n", "…and $n more")
    fun verseLower(n: Any?): String = t("стих $n", "$n-oyat", "verse $n")
    fun versesLower(a: Any?, b: Any?): String = t("стихи $a–$b", "$a–$b-oyatlar", "verses $a–$b")
    fun versesPerSlideN(n: Any?): String = t("по $n ст. на слайде", "slaydda $n oyatdan", "$n verses per slide")
    fun notBible(name: Any?): String = t("«$name» — не перевод Библии. Нужен основной файл модуля, например RST.SQLite3", "«$name» — Bibliya tarjimasi emas. Modulning asosiy fayli kerak, masalan RST.SQLite3", "“$name” is not a Bible translation. The main module file is needed, for example RST.SQLite3")
    fun noBooks(name: Any?): String = t("В «$name» нет книг: это не модуль Библии MyBible", "«$name» da kitoblar yo‘q: bu MyBible Bibliya moduli emas", "“$name” has no books: it is not a MyBible Bible module")
    fun notMyBible(name: Any?): String = t("«$name» не открывается как модуль MyBible", "«$name» MyBible moduli sifatida ochilmaydi", "“$name” does not open as a MyBible module")
    fun translationAdded(name: Any?): String = t("Добавлен перевод «$name»", "«$name» tarjimasi qo‘shildi", "Translation “$name” added")

    // ——— Шаблоны: песни, отсчёт, видео ———

    fun slideLabelN(n: Any?): String = t("Подпись слайда $n", "$n-slayd yorlig‘i", "Label of slide $n")
    fun splitSlideN(n: Any?): String = t("Разделить слайд $n по курсору", "$n-slaydni kursor bo‘yicha bo‘lish", "Split slide $n at the cursor")
    fun mergeSlideN(n: Any?): String = t("Объединить слайд $n с предыдущим", "$n-slaydni oldingisi bilan birlashtirish", "Merge slide $n with the previous one")
    fun minN(n: Any?): String = t("$n мин", "$n daq", "$n min")
    fun untilTime(x: Any?): String = t("До $x", "$x gacha", "Until $x")
    fun videoLiveFrom(x: Any?): String = t("Видео в зале с $x", "Video zalda $x dan", "Video live from $x")

    // ——— Шаблоны: обновления и копии ———

    fun versionAvailable(v: Any?): String = t("Есть версия $v", "$v versiyasi bor", "Version $v is available")
    fun downloadingPct(v: Any?, p: Any?): String = t("Загрузка $v: $p %", "$v yuklanmoqda: $p %", "Downloading $v: $p %")
    fun versionDownloaded(v: Any?): String = t("Версия $v скачана", "$v versiyasi yuklab olindi", "Version $v downloaded")
    fun installingV(v: Any?): String = t("Установка $v…", "$v o‘rnatilmoqda…", "Installing $v…")
    fun downloadInstall(v: Any?): String = t("Скачать и установить $v", "$v ni yuklab olish va o‘rnatish", "Download and install $v")
    fun installV(v: Any?): String = t("Установить $v", "$v ni o‘rnatish", "Install $v")
    fun versionN(v: Any?): String = t("Версия $v", "$v versiyasi", "Version $v")
    fun backupsAuto(n: Any?): String = t("Сами раз в сутки и перед каждым обновлением, хранятся последние $n", "Kuniga bir marta va har bir yangilanishdan oldin o‘zi, oxirgi $n tasi saqlanadi", "Automatically once a day and before every update; the last $n are kept")
    fun itemsSetlists(i: Any?, s: Any?): String = t("материалов $i, сетлистов $s", "materiallar $i, ro‘yxatlar $s", "$i items, $s setlists")
    fun restoreNote(from: Any?, i: Any?, s: Any?): String = t(
        "Библиотека заменится копией «$from»: материалов $i, сетлистов $s. Нынешняя библиотека сначала сохранится копией — её можно будет вернуть.",
        "Kutubxona «$from» nusxasi bilan almashtiriladi: materiallar $i, ro‘yxatlar $s. Hozirgi kutubxona avval nusxa sifatida saqlanadi — uni qaytarish mumkin bo‘ladi.",
        "The library is replaced with the copy “$from”: $i items, $s setlists. The current library is saved as a copy first, so it can be brought back.",
    )
    fun beforeUpdate(v: Any?): String = t("перед обновлением до $v", "$v ga yangilashdan oldin", "before updating to $v")

    // ——— Шаблоны: сообщения пульта ———

    fun libNewer(n: Any?): String = t(
        "Библиотека записана более новой версией Deixis: $n непонятных записей пропущено, всё остальное открыто. Исходный файл сохранён рядом без изменений.",
        "Kutubxona Deixis ning yangiroq versiyasida yozilgan: $n ta tushunarsiz yozuv o‘tkazib yuborildi, qolgani ochildi. Asl fayl yonida o‘zgarishsiz saqlandi.",
        "The library was written by a newer Deixis: $n unknown records were skipped, everything else is open. The original file is kept next to it unchanged.",
    )
    fun themeBgNotSent(msg: Any?): String = t("Фон темы не передан: $msg", "Mavzu foni uzatilmadi: $msg", "The theme background was not sent: $msg")
    fun fileNotSent(name: Any?, msg: Any?): String = t("Файл «$name» не передан: $msg", "«$name» fayli uzatilmadi: $msg", "File “$name” was not sent: $msg")
    fun convertedOn(title: Any?, screen: Any?): String = t("«$title» показывается на «$screen»: другие экраны получают её как PDF", "«$title» «$screen» da ko‘rsatiladi: boshqa ekranlar uni PDF sifatida oladi", "“$title” is shown on “$screen”: other screens get it as a PDF")
    fun convertingPdf(name: Any?): String = t("«$name»: перевод в PDF…", "«$name»: PDF ga o‘girilmoqda…", "“$name”: converting to PDF…")
    fun fileNotFound(name: Any?): String = t("Файл «$name» не найден", "«$name» fayli topilmadi", "File “$name” not found")
    fun fileNotOnPhone(name: Any?): String = t("Файл «$name» не найден на телефоне", "«$name» fayli telefonda topilmadi", "File “$name” is not on the phone")
    fun fileUnavailable(name: Any?): String = t("Файл «$name» недоступен", "«$name» fayli mavjud emas", "File “$name” is unavailable")
    fun transferQueued(title: Any?, screen: Any?): String = t("«$title» → $screen: в очереди", "«$title» → $screen: navbatda", "“$title” → $screen: queued")
    fun transferPreparing(title: Any?, p: Any?): String = t("«$title»: подготовка $p %", "«$title»: tayyorlanmoqda $p %", "“$title”: preparing $p %")
    fun transferSending(title: Any?, screen: Any?, p: Any?, sent: Any?, total: Any?): String =
        t("«$title» → $screen: $p % · $sent из $total", "«$title» → $screen: $p % · $sent / $total", "“$title” → $screen: $p % · $sent of $total")
    fun fromHolyrics(name: Any?): String = t("«$name»: из Holyrics", "«$name»: Holyrics dan", "“$name”: from Holyrics")
    fun noSoundBecause(reason: Any?): String = t("Трансляция без звука: $reason", "Ovozsiz translyatsiya: $reason", "Sharing without sound: $reason")
    fun cantRead(name: Any?): String = t("Не удалось прочитать «$name»", "«$name» ni o‘qib bo‘lmadi", "Could not read “$name”")

    // ——— Шаблоны: Holyrics ———

    fun inHolyricsSlide(n: Any?, m: Any?): String = t("В Holyrics · слайд $n из $m", "Holyrics da · slayd $n / $m", "In Holyrics · slide $n of $m")
    fun toHolyricsNamed(name: Any?): String = t("«$name» — в Holyrics", "«$name» — Holyrics da", "“$name” is in Holyrics")
    fun playsInHolyrics(name: Any?): String = t("«$name» играет в Holyrics", "«$name» Holyrics da ijro etilmoqda", "“$name” is playing in Holyrics")
    fun everyNSec(n: Any?): String = t("каждые $n с", "har $n soniyada", "every $n s")
    fun songsN(n: Any?): String = t("песен $n", "qo‘shiqlar: $n", "songs: $n")
    fun themeForAll(name: Any?): String = t("«$name» — на всё, что показывается", "«$name» — ko‘rsatiladigan hamma narsaga", "“$name” for everything shown")
    fun quickThemeNote(name: Any?): String = t("Текст и отсчёт пойдут с темой «$name» — её выбирают в ленте тем на главном.", "Matn va sanoq «$name» mavzusi bilan chiqadi — u bosh sahifadagi mavzular tasmasida tanlanadi.", "Text and countdown go with the theme “$name”, picked in the theme strip on the main screen.")
    fun inHolyricsLower(name: Any?): String = t("в Holyrics: $name", "Holyrics da: $name", "in Holyrics: $name")
    fun inHolyricsColon(x: Any?): String = t("В Holyrics: $x", "Holyrics da: $x", "In Holyrics: $x")
    fun notAllowedNow(list: Any?): String = t("Сейчас не разрешено: $list.", "Hozir ruxsat yo‘q: $list.", "Not allowed now: $list.")
    fun tokenMissing(n: Any?): String = t(
        "У токена Holyrics не хватает прав ($n). Права добавляются в Holyrics: Файл → Настройки → API Server → Управление правами",
        "Holyrics tokeniga ruxsatlar yetishmaydi ($n). Ruxsatlar Holyrics da qo‘shiladi: File → Settings → API Server → Ruxsatlarni boshqarish",
        "The Holyrics token lacks permissions ($n). Permissions are added in Holyrics: File → Settings → API Server → Manage permissions",
    )
    fun importSongs(n: Any?, m: Any?): String = t("Песни: $n из $m", "Qo‘shiqlar: $n / $m", "Songs: $n of $m")
    fun importTexts(n: Any?, m: Any?): String = t("Тексты: $n из $m", "Matnlar: $n / $m", "Texts: $n of $m")
    fun importDone(songs: Any?, texts: Any?, themes: Any?): String = t("Из Holyrics в Deixis: песен $songs, текстов $texts, тем $themes", "Holyrics dan Deixis ga: qo‘shiqlar $songs, matnlar $texts, mavzular $themes", "From Holyrics to Deixis: $songs songs, $texts texts, $themes themes")
    fun openedInHolyrics(name: Any?): String = t("«$name» открыт в Holyrics", "«$name» Holyrics da ochildi", "“$name” is open in Holyrics")
    fun inHolyricsPlaylist(name: Any?): String = t("«$name» — в плейлисте Holyrics", "«$name» — Holyrics pleylistida", "“$name” is in the Holyrics playlist")
    fun playlistMerged(name: Any?): String = t("«$name» добавлен в плейлист Holyrics", "«$name» Holyrics pleylistiga qo‘shildi", "“$name” added to the Holyrics playlist")
    fun playlistOpened(name: Any?): String = t("В Holyrics открыт плейлист «$name»", "Holyrics da «$name» pleylisti ochildi", "Playlist “$name” is open in Holyrics")
    fun noModulesFor(list: Any?): String = t("Для переводов Holyrics ($list) в Deixis нет модулей: они добавляются в «Переводы Библии»", "Holyrics tarjimalari ($list) uchun Deixis da modullar yo‘q: ular «Bibliya tarjimalari»da qo‘shiladi", "Deixis has no modules for the Holyrics translations ($list): they are added in “Bible translations”")
    fun translationsLikeHolyrics(list: Any?): String = t("Переводы как в Holyrics: $list", "Tarjimalar Holyrics dagidek: $list", "Translations as in Holyrics: $list")
}
