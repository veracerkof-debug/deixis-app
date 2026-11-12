# Пульт: общий код телефона и компьютера

Пульт Deixis написан один раз (Compose) и собирается в два приложения:

| Где | Как подключается | Что добавляет платформа |
|---|---|---|
| `android/phone` | `sourceSets["main"].java.srcDir("../../pult/src/main/kotlin")` в `android/phone/build.gradle.kts` | `AndroidPlatform`, `PhoneHost`, `PlatformUi.kt`, `VideoPreview.kt`, `Feedback.kt` |
| `desktop` | `kotlin.sourceSets["main"].kotlin.srcDir("../pult/src/main/kotlin")` в `desktop/build.gradle.kts` | `DesktopPlatform`, `DesktopHost`, `PlatformUi.kt`, `DesktopVideo.kt`, `PultWindow.kt` |

`pult/` — не модуль Gradle, а папка исходников: своего `build.gradle.kts` у неё нет, она компилируется заново в каждом приложении. Весь код лежит в одном пакете `app.deixis.phone` (и общий, и платформенный), поэтому платформенные функции видны общему коду без `expect/actual`.

Правила проекта (CLAUDE.md, DESIGN.md) для пульта:
- цвета, размеры и движение — только из `Theme.kt` (`P`, `T`, `V`);
- каждая строка — через `L` (`Strings.kt`) на трёх языках, без «ты/вы»;
- пульт не трогает вывод: сбой пульта не меняет то, что в зале;
- новый жест — строкой в «Карту жестов» DESIGN.md.

## 1. Что пульт берёт у системы

### 1.1. `Platform` (`pult/.../Platform.kt`)

Интерфейс всего системного: файлы, картинки, хранилище, сеть, диагностика. Файлы оператора адресуются строкой: `content://…` на телефоне, `file:///…` на компьютере; сами файлы в библиотеку не копируются.

| Член | Смысл | Android (`android/phone/.../AndroidPlatform.kt`) | Компьютер (`desktop/src/main/kotlin/app/deixis/phone/DesktopPlatform.kt`) |
|---|---|---|---|
| `filesDir` | папка данных пульта (библиотека, профили, копии, модули Библии) | `Application.filesDir` | `<dataDir>/pult` |
| `cacheDir` | временные файлы | `Application.cacheDir` | `<dataDir>/pult-cache` |
| `deviceName` | имя, под которым экраны видят пульт | `Settings.Global.DEVICE_NAME`, иначе `Build.MODEL` | имя хоста, иначе «Deixis» |
| `features` | что есть у пульта (см. `Features` ниже) | `desktop=false, screenShare=true, becomeScreen=true, cable=true, shareSound = API ≥ 29` | `desktop=true`, остальное `false` |
| `prefs(name)` | хранилище ключ–значение | `AndroidPrefs` поверх `SharedPreferences` | `FilePrefs`: `<filesDir>/prefs/<name>.properties`, запись через `.tmp` и переименование; множества — ключ `set.<key>`, разделитель `\u001F` |
| `open(uri)` | поток файла; `null` — файла нет или доступ пропал | `contentResolver.openInputStream` | `File(URI)` |
| `meta(uri)` | имя, размер, MIME | `OpenableColumns` + `getType` | `Files.probeContentType`, иначе `mimeOf` |
| `keep(uri)` | сохранить доступ после перезапуска | `takePersistableUriPermission(READ)` | ничего |
| `fileUri(f)` | адрес файла из папки пульта | `Uri.fromFile` | `File.toURI` |
| `pdfPages(uri)` | число страниц PDF | `PdfRenderer` | `app.deixis.desktop.PdfPages.count` |
| `videoDurationMs(uri)` | длительность видео | `MediaMetadataRetriever` | `VideoProbe.durationMs` (FFmpeg) |
| `heicToJpeg(uri, out)` | HEIC → JPEG для экранов, которые HEIC не открывают | `ImageDecoder` (API 28+, уменьшение до 3840 по длинной стороне) или `BitmapFactory`, JPEG 92 | всегда `false` |
| `canConvertHere(ext)` / `convertToPdf(src, ext, out)` | перевод презентации/документа в PDF на самом устройстве; возвращает предупреждение | `PptxPdf.FORMATS` + `Docx.FORMATS`, перевод `PptxPdf.convert` (android/receiver) | `pptx ppt docx doc odp odt key rtf` через переданный `DocConverter` (Office, LibreOffice, Keynote, встроенный) |
| `sql(file)` | модуль Библии (SQLite) только на чтение | `AndroidSql` | `JdbcSql` |
| `demoFiles()` / `openDemo()` / `demoSize()` | демо-набор | `assets/demo`; размер — у несжатых файлов, иначе `-1` | ресурсы `/demo/index.txt`, размер всегда `-1` |
| `folderFiles` / `folderWrite` / `folderDelete` / `keepFolder` | папка копий вне приложения | Storage Access Framework (`DocumentsContract`), доступ на чтение и запись | обычная папка |
| `decodeImage(bytes)` | картинка в `ImageBitmap` | `BitmapFactory` | Skia `Image.makeFromEncoded` |
| `thumbnail(uri, kind, index, width)` | миниатюра фото, видео или страницы PDF | `PdfRenderer` / `loadThumbnail` (API 29+), `ImageDecoder`, `BitmapFactory` / кадр 1 с из `MediaMetadataRetriever` | `PdfPages.page` / `ImageIO` / `VideoProbe.frame` (1 с) |
| `keepAlive()` | держать связь с экранами в фоне | `ShowService.start` | ничего (компьютер не засыпает с открытым окном) |
| `multicast(on)` | поиск экранов в сети | `WifiManager.MulticastLock` | ничего |
| `stopScreenShare()` | остановить трансляцию экрана | `ScreenShareService.stop` | ничего |
| `diagnostics()` | проверки устройства | `Checks.common` + `AndroidChecks.device/wifi/notifications/battery` | `Checks.common` + переданные `checks()` (мониторы, VLC, FFmpeg, перевод) |
| `appLabel` | «Deixis 0.10.28 · Android 14» для отчёта | версия из `ApkUpdates.currentVersion` | версия + `Diagnostics.system()` |
| `copyText(text)` | буфер обмена | `ClipboardManager`, не больше 200 000 символов | AWT `systemClipboard` |
| `shareFile(subject, name, text)` | отчёт файлом | файл в `cacheDir/share/`, `FileProvider` `app.deixis.phone.files`, `ACTION_SEND` | окно «Сохранить» и открытие папки |
| `lanAddress` | адрес для ссылок зрителям | `null` (ссылку строит экран) | лямбда `lan()` из приёмника компьютера |
| `visible` | пульт на виду (частота опроса Holyrics) | `PhoneApp.remoteVisible` | лямбда `shown()` |
| `dlna` | вывод на ТВ DLNA; `null` — не умеет | `DlnaOutput` (лениво) | `null` |

Связанные типы в том же файле:
- `FileMeta(name, size, mime)`; `Features(desktop, screenShare, becomeScreen, cable, shareSound)`.
- `Prefs` и `Prefs.Editor` — урезанный аналог `SharedPreferences`.
- `DlnaOut` — интерфейс вывода DLNA (`found`, `state`, `logo`, `logoPicture`, `search`, `open`, `close`, `register`, `show`, `toggle`, `video`); `NoDlna` — заглушка, которую `Controller` берёт при `platform.dlna == null`.
- `FilePicker`, `FolderPicker` — обёртки над лямбдой запуска системного окна выбора.

### 1.2. `PultHost` и `LocalHost`

`PultHost` — то, что нужно экранам интерфейса сверх `Platform`: службы приложения и вызовы системы. Он раздаётся через `val LocalHost = staticCompositionLocalOf<PultHost>`; читают его `UpdatesUi.kt`, `Screens.kt`, `SlideView.kt`, `ShowScreen.kt` (`LocalHost.current`).

| Член | `PhoneHost` (android/phone) | `DesktopHost` (desktop) |
|---|---|---|
| `platform` | `PhoneApp.platform` | передан в конструктор |
| `holyrics` | `PhoneApp.holyrics` | лениво из `holyricsOf()` |
| `version` | `versionName` пакета | передана |
| `updates` | `PhoneApp.updater` (`ApkUpdates.updater`, выпуски GitHub) | `DesktopUpdates` |
| `takeWhatsNew()` | `updater.takeWhatsNew()` | пусто: «Что нового» показывает окно программы |
| `updatesFromStore` | `ApkUpdates.fromPlay` (поставлено из Google Play) | `false` |
| `canInstallUpdates` | `ApkUpdates.canInstall` | `true` |
| `autoInstallTried` | в `PhoneApp` (на время процесса) | поле |
| `FeedbackSection()` | `PhoneFeedbackSection()` (`Feedback.kt`: «Поделиться») | текст с версией — в буфер обмена |
| `openCastSettings()` | `Settings.ACTION_CAST_SETTINGS`, запасной — `ACTION_WIRELESS_SETTINGS` | ничего |
| `becomeScreen()` | открыть `ScreenActivity` | ничего |

Провайдер ставится снаружи пульта: на телефоне — `MainActivity.onCreate` (`CompositionLocalProvider(LocalHost provides app.host)`), на компьютере — `PultWindow`. Без провайдера `LocalHost.current` падает с `error("PultHost")`.

### 1.3. Платформенные функции (аналог `expect/actual` по договорённости)

Общий код вызывает функции, которых в `pult/` нет: каждая платформа обязана объявить их в пакете `app.deixis.phone` с той же сигнатурой. Список — в комментарии в конце `Platform.kt`. Проверяет договор только компилятор каждой сборки: забытая функция ломает сборку одной платформы.

| Функция | Android | Компьютер |
|---|---|---|
| `rememberFilePicker(multiple, onPick)` | `PlatformUi.kt`: `OpenDocument` / `OpenMultipleDocuments` | `PlatformUi.kt`: AWT `FileDialog`, фильтр расширений из MIME (`extensionsFor`) |
| `rememberFolderPicker(onPick)` | `OpenDocumentTree` | `JFileChooser` (только папки) |
| `BackHandlerOn(enabled, onBack)` | `androidx.activity.compose.BackHandler` | стек `DesktopBack`, срабатывает по Esc (последний включённый) |
| `rememberActs(c, ui): Acts` | выбор файлов, Библии; трансляция экрана: разрешение `RECORD_AUDIO` (если звук), `POST_NOTIFICATIONS` (API 33+), системный запрос `MediaProjection`, затем `ScreenShareService.start`; большой файл после этого открывается в проигрывателе телефона | файлы и Библия — `rememberFilePicker`; `share` — только подсказка `L.desktopShareHint` |
| `VideoPreview(c, ui, cue)` | `VideoPreview.kt`: `VlcPlayer` по дескриптору `content://`, без передачи | `DesktopVideo.kt` |
| `Didot: FontFamily` | `app.deixis.receiver.Didot` | GFS Didot из ресурсов |
| `LogoFull(style, color)` | `app.deixis.receiver.LogoFull` | отрисовка картинкой (`Rendered`) |
| `TextSlideCanvas(...)`, `ScriptureSlideCanvas(...)` | те же композиции, что у экрана Android (`android/receiver/.../SlideCanvas.kt`) | отрисовка движком зала компьютера |

Слайды превью рисует тот же движок, что зал этого устройства, поэтому превью совпадает с залом.

`Acts` (`Sheets.kt`) — действия, которым нужен системный экран: `pick(mimes, presentation, then)`, `share(LargeFile?)`, `addBible()`, `holyrics()`; раздаётся через `LocalActs`.

## 2. `Controller` (`pult/.../Controller.kt`)

Один объект на процесс: состояние пульта, связь с экранами, показ, библиотека. Около 1880 строк; разделить его на части — известное желание, пока не сделано. Ниже — по разделам (в файле они отмечены комментариями `// ——— … ———`).

Создание и жизнь:
- `Controller(platform)`; внутренний `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
- На телефоне создаётся лениво в `PhoneApp` (`val controller by lazy`) и прогревается в фоне при старте процесса (`appScope.launch { runCatching { controller } }` в `PhoneApp.onCreate`): чтение библиотеки не ждёт в главном потоке. На компьютере — так же заранее (`ReceiverApp.pultController()`).
- `init`: язык (`I18n.apply`), загрузка модулей Библии и прогрев Писания в `Dispatchers.IO`, отсчёт, сообщения `libRecovered`/`libNewer`, ежедневная копия, демо-набор (`seedDemo`), перевод старых документов, перенос старых настроек, автоподключение экранов из `prefs("tokens")["outputs"]`.
- Сообщения оператору — `messages: MutableSharedFlow<String>` (тосты), ход операций — `progress`.

### 2.1. Экраны, выходы, подключение

- `Target(id, name, host, port, kind, platform)` — экран, на который можно показать. `Target.Kind`:

| Kind | Что это | Откуда |
|---|---|---|
| `NETWORK` | приёмник Deixis, найденный в сети (mDNS) | `startDiscovery()` → `Discovery.browse()` |
| `MANUAL` | введён адресом или ссылкой из QR | `addManual(input)` |
| `CABLE` | кабель USB-C → HDMI: приёмник внутри телефона на `127.0.0.1` | `CastOutput` → `setCable` |
| `MIRACAST` | беспроводной второй дисплей Android, тот же путь, что кабель | `CastOutput` → `setCable` |
| `DLNA` | обычный ТВ DLNA | `dlna.found` |
| `LOCAL` | экран на том же устройстве, что пульт (компьютер: зал на мониторах) | `ReceiverApp` → `setCable(... Kind.LOCAL ...)` |

  `via` — подпись способа подключения, `icon` — значок (`proj`, `laptop`, `phone`, `tv`).
- `targets` — все экраны: кабель + найденные + введённые + DLNA. `outputs: StateFlow<List<OutputView>>` — подключённые с состоянием `OutState` (`ONLINE`, `SYNC`, `CONNECTING`, `OFFLINE`, `PIN`).
- Внутри — `private class Output(target, client: SenderClient)`: свои задачи, `delivered` (хэши файлов, уже целиком на экране), `lastShown`, мьютекс `ahead` для файлов, отправляемых заранее.
- `connect(t)` — первый раз спрашивает PIN (`needPin`), дальше берёт токен из `prefs("tokens")`. `pair(t, pin)`, `disconnect`, `retry`. Кабель, Миракаст и `LOCAL` встают первыми в списке выходов. `primary` — первый выход: по нему видно состояние показа, через него идут зрители (`viewUrl`, `viewLocalUrl`), трансляция и Holyrics.
- Отказ `DenyReason.UNTRUSTED_SCREEN` — по адресу отвечает другой сертификат: токен не отправляется, нужен QR. `UNKNOWN_TOKEN` — экран забыл телефон: токен стирается, PIN спрашивается заново.
- При (пере)подключении экран сразу получает то, что в зале, если там ещё не ровно это (`lastShown`), и слой «Поверх показа».
- `approvals` / `answerConnect(a, allow)` — чужое устройство ввело верный PIN и ждёт «Разрешить».
- `keepAlive()` — пока подключён хотя бы один экран, `platform.keepAlive()` (на телефоне `ShowService`).
- Компьютер как экран: `screenUpdate` (обновление экрана), `screenCapture` (трансляция экрана компьютера — входы), `screenOutputs` (мониторы и раскладка — выходы), `screenAirPlay` (приём AirPlay). UI — `ScreenInputs`, `ScreenOutputs`, `ScreenUpdateSheet` в `UpdatesUi.kt`.
- Коды подключения: `ConnectInfo`, `connectInfos()` (QR и PIN каждого подключённого экрана, свой компьютер первым), `connectCard(t, on)` — «QR и код на экран» поверх показа. UI — `ConnectCodesUi.kt`.
- Диагностика: `diagnoseAll()` (это устройство + каждый экран), `diagnosticsText(...)` — отчёт с журналами. UI — `DiagnosticsUi.kt`.

### 2.2. Показ: «Сейчас», «Предпросмотр», режимы

- `live: MutableStateFlow<Cue?>` — что в зале; `preview` — что в предпросмотре; `draft` — правка оформления, видная только в предпросмотре.
- `Cue(source, index, startMs)`; `Source` — элемент библиотеки или разовый показ (текст, Holyrics, экран); `Slide` — `Text`, `Scripture`, `Page`, `Picture`, `Movie`, `Pending` (документ ещё не переведён), `Screen`, `Countdown` (`Model.kt`).
- `tap(cue)` — если элемент уже в зале (и не включено «всегда через предпросмотр») — сразу в зал, иначе в предпросмотр. `toPreview(cue)` — заодно отправляет файл заранее. `previewToLive()`, `goLive(cue)`, `step(±1)`, `previewStep`, `goLiveVideo`.
- `deliver(cue)` → `deliverTo(o, cue)` для каждого выхода: `contentFor` строит `Content` (у файлов — `fileOn`, у страниц — `pageOn`, у непереведённых — `convertOn`). Устаревший слайд (пока шёл файл, в зал отправили другое) не показывается — так сбой или задержка не выводят не то.
- `mode(m)` — «Чёрный», «Логотип», «Очистить», «Уйти с экранов»; повтор возвращает `NORMAL`. Своя картинка логотипа сначала передаётся на экран. `mode` — состояние главного экрана или локальное, если экранов нет.
- `video(action, …)` — команды видео всем экранам.
- Оформление: `styleFor(cue)` (слайд → элемент → сетлист → «всегда»), `startDraft`/`applyDraft(scope)`/`cancelDraft`, `draftScope`, `defaultStyle`, темы (`themes`, `saveTheme`, `applyTheme`, `applyThemeTo`).
- Обратный отсчёт: `timer`, `timerToggle/Add/Restart/Stop`; после нуля может сам перейти к следующему элементу сетлиста.
- Слой «Поверх показа»: `overlays`, `overlayDrafts`, `setOverlays` (`OverlayUi.kt`).
- Демонстрация экрана телефона: `openScreenStream()`, `openScreenAudio()`, `showScreen(w, h)`, `screenStopped(error)` (после остановки — «Чёрный», чтобы последний кадр со шторкой не остался в зале), `sharing`, `tooLarge`, `largeFor(item)`.

### 2.3. Передача файлов и `sendAhead`

- `fileOn(o, item)` — хэш файла считается один раз (`StoreData.hashes`); если файл уже на экране (`o.delivered`) — не передаётся. Одна передача на пару «экран/элемент»: `sending` хранит `Deferred`, листание во время передачи ждёт её.
- `sendFile` — ход `Transfer` в `transfers` (ключ `"<id экрана>/<id элемента>"`): `preparing` — считается хэш, `queued` — ждёт очереди.
- `sendAhead(item)` — файл на все подключённые экраны в фоне, по одному на экран (`Output.ahead`). `sendSetAhead()` — все фото, видео и PDF активного сетлиста. `canSendAhead` — только фото, видео и PDF.
- `stored` и `refreshStored(o)` — при подключении экран спрашивается, какие файлы библиотеки у него уже есть (`fileStatus`). `onScreens(...)` — состояние для значка в строке библиотеки (`FileBadge` в `Screens.kt`).
- `busy` — строка хода для окна «Сейчас» (перевод, Holyrics, передача файла, которого ждёт зал); фоновые передачи там не видны.

### 2.4. Библиотека, сетлисты, настройки, профили

- `data: MutableStateFlow<StoreData>`; все изменения — через `private fun edit(change)` → `store.update`. `editIn(profileId, change)` — результат долгой операции ложится в профиль, где её начали, даже если профиль уже сменили.
- `importFiles(uris, preferPresentation, then)`, `saveItem`, `deleteItem`, `importBible`, `removeBible`, демо-набор (`restoreDemo`, `removeDemo`, `hasDemo`).
- Сетлисты: `activeSet`, `pickSet`, `newSet`, `saveSet`, `duplicateSet`, `deleteSet`, `addToSet`, `moveInSet`, `removeFromSet`.
- `settings: StateFlow<AppSettings>` и `setSettings(change)`: язык применяется сразу, смена вида логотипа и регистра сразу уходит в зал.
- Профили: `profiles`, `switchProfile`, `createProfile`, `editProfile`, `deleteProfile`. Копии: `backups`, `backupNow(reason)` (зовётся и перед обновлением APK из `PhoneApp.updater`), `restore(d)`.

### 2.5. Библия и кэш Писания

- `bible: BibleLibrary` (лениво: встроенные модули копируются при первом запуске), `bibleModules`, `desk: BibleDesk` (открытая глава и история вкладки «Библия»).
- `scripture(pick)` — слайды места Писания, кэш `scriptureCache: ConcurrentHashMap<BiblePick, List<ScriptureContent>>`. Пустой результат (модули ещё грузятся) не кэшируется.
- `warmScripture()` в `init` (на `Dispatchers.IO`, после загрузки модулей) заранее строит слайды всех мест Писания в библиотеке — иначе первая отрисовка сетлиста с Писанием читала бы SQLite на главном потоке.

### 2.6. Holyrics

- `HolyricsSource(platform, controller)` (`HolyricsSource.kt`) — связь с Holyrics на компьютере, опрос текущего показа; задаёт `controller.holyricsRemote` (`showBible`, `toggleKey`).
- В `Controller`: `holyricsRemote`, `holyricsVerse`, `toHolyrics()` (режим `WorkMode.HOLYRICS`: Библия, «Чёрный» и «Очистить» идут в Holyrics), `showSlides(...)`, `showImage/showVideo/showRemote`, `importFromHolyrics(req)`, `importHolyrics(haul)` → `HolyricsImport.merge`.
- UI: `HolyricsUi.kt` (окно подключения, карточка «в Holyrics»), `HolyricsMode.kt` (переключатель режима, библиотека и плейлисты Holyrics вместо своих вкладок).

## 3. Данные: `Model.kt`, `StoreRead.kt`, `Profiles.kt`, `Backups.kt`

### 3.1. Модель

- `StoreData` (`@Serializable`) — весь файл `library.json`: `items`, `setlists`, `active` (id выбранного сетлиста), `recent` (до 12 id), `styles` (ключи `all`, `set:<id>`, `item:<id>`, `slide:<id>:<n>`), `settings`, `hashes` (`FileRef` по id элемента), `conversions` (`Conversion(output, pdf, pages)`), `themes` (свои), `localFiles` (хэш → адрес файла телефона), `demoSeeded`, `overlays`, `overlayDrafts`, `bibleBooksV2` (миграция номеров книг).
- `LibItem(id, kind, title, slides, labels, uri, mime, size, pages, durationMs, bible, style, created, videoFit, videoBackdrop, countdown, demo)`; `Kind`: `SONG`, `TEXT`, `BIBLE`, `PHOTO`, `VIDEO`, `PRESENTATION`, `FILE`, `COUNTDOWN`.
- `Setlist(id, title, date, items)` — план показа на дату (`date` — ISO-строка); `items` — id элементов.
- `AppSettings(theme, alwaysPreview, darkDuringShow, clicker, volumeKeys, caps, church, logo, logoImage, themePanel, workMode, shareSound, lang)`; `lang = null` — как в системе.
- Новое поле в любом `@Serializable` классе — только со значением по умолчанию: иначе старые `library.json` не прочитаются строго и уйдут в чтение по частям.

### 3.2. Запись: `StoreWriter` и `writeDurably`

- `writeDurably(file, text)` — запись во `.tmp`, `fd.sync()`, переименование. Читатель видит прежний файл целиком или новый.
- `StoreWriter` — свой поток `deixis-save`: `save(file, data, onFail)` кладёт снимок в `pending` (по файлу), поток пишет только последний. Данные неизменяемы, поэтому снимок можно писать позже. `flush()` ждёт до 10 с — зовётся перед повторным чтением (смена профиля, восстановление) и в `MainActivity.onStop` (в отдельном потоке `deixis-flush`).
- `Store.update(change)` пишет, только если объект изменился (`!==`). Ошибка записи → `onSaveFailed` → тост `L.noSpace` не чаще раза в 30 с (`SAVE_WARNING_MS`).

### 3.3. Чтение: `Store.load` и `StoreRead`

- Нет файла — первый запуск (пустой сетлист на сегодня).
- `StoreRead.read(text)`: сначала строго (`DeixisJson`); не вышло — по частям: каждый элемент `items`, `setlists`, `themes`, `styles`, `hashes`, `conversions` проверяется отдельно, непонятное пропускается; остальные поля проверяются по одному; неизвестные значения перечислений — по умолчанию (`coerceInputValues`). Возвращает `ReadStore(data, skipped)`; `null` — это не библиотека Deixis.
- `skipped > 0` (файл новой версии): оригинал копируется в `library.newer-<время>.json`, оператор получает `L.libNewer(n)`.
- Файл не читается вовсе: копия в `library.broken-<время>.json`, библиотека берётся из самой свежей копии (`Backups.newest()`) и сразу перезаписывается; `recovered = true` → тост `L.libRecovered`.
- Тесты: `StoreReadTest`, `CompatLibraryTest` (файлы прошлых версий из `testdata/compat`, путь передаётся в `deixis.compat`).

### 3.4. Профили (`Profiles.kt`)

- `Profiles(root)`: список — `profiles.json` (`ProfileBook(list, active)`). Профиль `main` — прежние данные на прежнем месте (`library.json`, `backups/`), остальные — `profiles/<id>/`.
- Испорченный `profiles.json` откладывается (`profiles.broken-…`), список собирается по папкам с `library.json`.
- `Profile(id, name, kind: ProfileKind, accent: Accent)`; акцент меняет `tint` палитры. Экраны, PIN и Holyrics общие для профилей.
- Смена профиля в UI пересоздаёт корень (`key(profile.current.id, lang)` в `DeixisPhone`). Показ в зале не трогается.
- Тест: `ProfilesTest`.

### 3.5. Резервные копии (`Backups.kt`, UI — `BackupsUi.kt`)

- `Backups(platform, root, prefix, lastKey)`: копии в `<root>/backups/`, имя `<prefix><yyyyMMdd-HHmmss>-<причина>.json`; у профилей свой префикс (`deixis.<id>-`).
- `daily` (если прошло больше суток), `save(data, reason)` (`manual`, `before-update-<версия>`, `before-restore`), хранится `KEEP = 7`; одна копия старше трёх дней от новейшей не вытесняется (якорь).
- Копия дублируется в папку, выбранную оператором (`folder`, через `Platform.folder*`). Копия больше 64 МБ не читается. Медиафайлы в копию не входят — только ссылки.

## 4. Устройство интерфейса

| Файл | Что в нём |
|---|---|
| `App.kt` | `AppTab` (`SHOW`, `BIBLE`, `LIBRARY`, `SETLISTS`, `DEVICES`), `Ui` (вкладка, стек шторок, полноэкранный вид, тосты, состояние списков), `DeixisPhone` (корень: палитра, `LocalUi`, `LocalActs`, язык, профиль), `Root` (вкладки снизу или слева при ширине ≥ 1024 dp, `HorizontalPager`), `HallBlack` (кнопка «Чёрный» на вкладках без полосы показа), `SheetHost`, `Toasts`, `Modifier.swipes` |
| `ShowScreen.kt` | вкладка «Показ»: окна «Сейчас» и «Предпросмотр», сетлист, `ControlBar` (полоса показа), `tapSlide`, `liveSlide`, `openStyle` |
| `Screens.kt` | `Header`, `LibraryScreen`, `FileBadge`, `SetlistsScreen`, `DevicesScreen` |
| `Sheets.kt` | `Acts`/`LocalActs`, `ConnectSheet`, `OutputCell`, `PinSheet`, `ApproveSheet`, `SettingsSheet`, `PickSheet`, `TextSheet`, `MirrorSheet`, `LargeSheet`, `FindSheet` |
| `SlideView.kt` | `SlideView`, `CueView`, `ItemThumb`, `screenRatio` (пропорции зала для превью), `LocalScreenRatio` |
| `FullView.kt` | `FullHost` — слайд во весь экран (`FullSpec.Live/Preview/Local/Video`) |
| `BibleScreen.kt`, `BiblePicker.kt`, `BibleDesk.kt`, `BibleLibrary.kt` | вкладка «Библия», выбор места, состояние главы и истории, модули |
| `Themes.kt`, `ThemePanel.kt`, `ThemeDrag.kt` | встроенные темы, редактор, панель тем, перенос темы перетаскиванием |
| `SongEditor.kt`, `CountdownUi.kt`, `OverlayUi.kt` | редактор песни/текста, отсчёт, «Поверх показа» |
| `UpdatesUi.kt`, `DiagnosticsUi.kt`, `ConnectCodesUi.kt`, `BackupsUi.kt` | обновления (свои и экранов), входы/выходы экрана, диагностика, коды подключения, копии |
| `HolyricsUi.kt`, `HolyricsMode.kt`, `HolyricsSource.kt`, `HolyricsImport.kt` | Holyrics |
| `Theme.kt` | токены и базовые элементы (`Txt`, `Icon`, `IconBtn`, `Btn`, `Pill`, `Cell`, `Group`, `GHead`, `Sep`, `Seg`, `Field`, `Switch`, `Note`, `Modifier.press`…) |
| `Strings.kt` | `Lang`, `I18n`, `L` |

Навигация:
- Вкладки — `ui.tab`; «Библия» есть, только если включён церковный раздел (`settings.church`).
- Шторка — `ui.sheet(title) { … }` (заменяет стек), `ui.push(title) { … }` (поверх, «Назад» возвращает к прежней), `ui.close()`, `ui.closeAll()`. На широком экране (≥ 1024 dp) шторка — диалог по центру.
- Полноэкранный вид — `ui.full = FullSpec…`; тост — `ui.toast(text)` или `controller.messages.emit(text)`.
- «Назад» (Android) и Esc (компьютер) закрывают шторку, затем полноэкранный вид (`BackHandlerOn` в `Root`).

## 5. Строки: `Strings.kt`

- `enum class Lang(code)`: `RU` (`ru`), `UZ` (`uz`, латиница), `EN` (`en`).
- `object I18n`: `lang` (текущий), `flow` (смена языка пересоздаёт корень интерфейса), `fromSystem()` (ru/uk/be/kk → русский, uz → узбекский, иначе английский), `of(code)`, `apply(code)`, `locale` (для дат).
- `object L`: свойства — готовые строки (`val connect: String get() = t("Подключить", "Ulash", "Connect")`), функции — шаблоны с числами и именами (`fun slidesN(n: Int)`), `ruPlural(n, one, few, many)`. `t(ru, uz, en)` — приватная.
- Строки обязаны быть `get()`, а не значением: иначе они вычислятся один раз и не сменятся с языком.
- Обращения на «ты» и «вы» нет: инфинитив и безличные формы («Подключить», «Сначала убрать показ…»).
- `StringsTest` (`android/phone/src/test/.../StringsTest.kt`):
  - `everyStringHasAllThreeLanguages` — каждое публичное свойство и функция `L`, возвращающие `String`, на каждом языке непустые; в узбекском и английском нет кириллицы. Функции вызываются с перебором значений перечисления или с числами 1, 2, 5, 11, 22. Строк должно быть больше 500.
  - `noRussianLiteralsOutsideStrings` — «храповик»: лексер Kotlin (`kotlinLiterals`) находит строковые литералы в `android/phone/src/main/.../phone`, `pult/.../phone`, `desktop/.../phone` (кроме `Strings.kt`); кириллица в литерале — ошибка. Исключения: участки между `// i18n:off` и `// i18n:on` (демо-набор в `Controller.kt`) и короткий `ALLOWED` (журнал в `ScreenActivity.kt`, «беспровод» в `HdmiOutput.kt`, «Демо» и «Горы» в `Controller.kt`).
  - Тест не смотрит `android/receiver`, `android/tv` и `desktop/.../desktop` — там русские строки есть (см. ANDROID.md).
- `tools/ui_names.py` берёт переводы из `Strings.kt` для сценариев на эмуляторе: сценарий пишется по-русски, кнопка ищется на любом из трёх языков.

## 6. Токены: `Theme.kt`

- `Palette(bg, cell, ink, l2, l3, tint, tintText, red, fill, live, gold, sep, fill2, dark)` — цвета из DESIGN.md → «Цвет интерфейса». `onTint` выбирается по контрасту (белый, пока контраст с акцентом ≥ 3:1, иначе тёмный).
- Палитры `Light`, `Dark`, `Paper`, `Oled`; `paletteOf(theme, accent)` (`"light"`, `"dark"`, `"paper"`, `"oled"`); `accentColors(accent, dark)` — акценты профиля `BRONZE`, `BLUE`, `GREEN`, `PLUM`, `SLATE`. Текущая — `P.current` (`internal val P = staticCompositionLocalOf`). Проверка контраста — `ContrastTest`.
- `object V` — цвета предпросмотра видео (на чёрном, как зал).
- `object T` — размеры (`gutter`, `gap`, `rowMin`, `icon`, `btn`, `barKey`, …), скругления (`rCard`, `rBtn`, `rSlide`, `rPill`…), шрифты (`fTab` … `fTitle`), длительности (`fast = 140`, `mid = 240`, `slow = 340`, `spin`), кривые (`eOut`, `eIn`, `eSpring`), доли (`scrim`, `disabled`, `pressScale`), пороги жестов (`sheetDismiss`, `flingClose`).
- Значки — `private val paths: Map<String, List<Part>>`: контуры SVG 24×24, линия 1.7 (те же, что в прототипе). `icon(name)` строит и кэширует `ImageVector`; неизвестное имя молча рисуется значком `"file"`. `Icon(name, color, size)`.
- Правило «без чисел в коде интерфейса»: цвет, размер, длительность — из `P`, `T`, `V`. На деле правило соблюдено не везде: вне `Theme.kt` в `pult/` около 160 литералов `N.dp`/`N.sp` (чаще всего отступы `8.dp`, `4.dp`, порог `1024.dp`), и ещё в `MainActivity.kt` (экран падения) и `VideoPreview.kt`. Тестом правило не проверяется. Новый код пишется уже с токенами.

## 7. Как добавить…

### …строку
1. В `Strings.kt`, в подходящий раздел `L`: `val myThing: String get() = t("…", "…", "…")` или функцию-шаблон. Узбекский — латиницей, без кириллицы; без «ты/вы».
2. В коде — только `L.myThing`.
3. `./gradlew :android:phone:testDebugUnitTest --tests '*StringsTest*'`.

### …значок
1. В `Theme.kt`, в `paths`: `"name" to listOf(Part("…"))` — контур 24×24; заливка — `fill = true`, другая толщина — `width`. Комментарий, если смысл неочевиден.
2. Тот же значок — в прототипе (`prototype/index.html`), чтобы они не расходились.
3. Использовать `Icon("name")` / `IconBtn("name", L.…)`. Опечатка в имени не падает, а рисует `"file"` — проверить глазами.

### …токен
1. В `T` (размер, длительность, доля) или в `Palette` и во все четыре палитры (цвет), с комментарием, зачем.
2. Цвет — сначала в DESIGN.md (таблица «Цвет интерфейса»), потом в код; при новом цвете на акценте — дополнить `ContrastTest`.

### …шторку
1. `@Composable fun MySheet(c: Controller, ui: Ui)` в подходящем файле `pult/` (или новом). Содержимое — `Group`, `Cell`, `GHead`, `Note`, `Btn` из `Theme.kt`.
2. Открыть: `ui.sheet(L.myTitle) { MySheet(c, ui) }`; поверх другой шторки — `ui.push(...)`.
3. Системное (выбор файла, разрешение) — через `LocalActs.current` или `rememberFilePicker`; новое системное действие — новый член `Platform`/`PultHost` или новая платформенная функция, реализованная в **обеих** платформах.
4. Проверить четырьмя принципами и шестью свойствами DESIGN.md, добавить в SCREENS.md и прототип.

### …экран (вкладку)
1. Значение в `AppTab` (значок и заголовок через `L`), ветка в `Screen(...)` в `App.kt`.
2. Порядок вкладок = порядок `AppTab.entries`; набор вкладок зависит от `settings.church`.
3. Обновить `walk_phone` в `tools/android-smoke.sh` (вкладки обходятся по тексту) и сценарии снимков.

### …жест
1. Через `Modifier.swipes(...)`, `combinedClick`, `press(onLong)` и т. п.; не на полях ввода, клавишах и полосах прокрутки; край экрана — системному «Назад».
2. Строка в «Карте жестов» DESIGN.md — обязательно.

### …системную возможность
1. Член в `Platform` (данные, файлы, система) или `PultHost` (службы приложения, Compose-раздел).
2. Реализация в `AndroidPlatform`/`PhoneHost` и `DesktopPlatform`/`DesktopHost`; если на одной платформе смысла нет — явная заглушка (`= Unit`, `false`, `null`) и поле в `Features`, по которому UI прячет кнопку.
