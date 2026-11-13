# Карта кода Deixis

Где что лежит: модули, файлы, ресурсы, проверки и вспомогательные папки. Описания взяты из KDoc и объявлений верхнего уровня самих файлов. Общая картина — в [ARCHITECTURE.md](../../ARCHITECTURE.md), дизайн-код — в [DESIGN.md](../../DESIGN.md).

Пакеты: `app.deixis.diag`, `app.deixis.core` (+ `app.deixis.core.airplay`, `app.deixis.core.quiz`), `app.deixis.phone` (пульт: `pult/`, `android/phone`, а также `desktop/src/main/kotlin/app/deixis/phone`), `app.deixis.desktop`, `app.deixis.receiver`, `app.deixis.tv`.

## 1. Модули и зависимости

```
diag  ←api─  core  ←implementation─  desktop  (+ исходники pult/)
                └──api──  android:receiver  ←implementation─  android:tv
                                            ←implementation─  android:phone  (+ исходники pult/)
```

| Модуль | Файл сборки | Что подключает |
|---|---|---|
| `:diag` | `diag/build.gradle.kts` | Kotlin JVM; `api` coroutines и kotlinx.serialization. Ни от чего своего не зависит. |
| `:core` | `core/build.gradle.kts` | `api(project(":diag"))`, coroutines, serialization; Ktor server/client (CIO, websockets), jmdns, zxing, BouncyCastle. Байт-код Java 17 (общий с Android). В ресурсы кладёт `CHANGELOG.md` под именем `changelog.md` (`tasks.processResources`). |
| `:desktop` | `desktop/build.gradle.kts` | `implementation(project(":core"))`, vlcj, PDFBox, imageio (webp, tiff), Apache POI (ooxml, scratchpad), sqlite-jdbc, FFmpeg (bytedeco) своей платформы, Compose Desktop. Исходники `../pult/src/main/kotlin` добавлены через `kotlin.sourceSets["main"].kotlin.srcDir`. Главный класс `app.deixis.desktop.MainKt`. Генерирует ресурсы: `deixis-version.txt` (из `DEIXIS_VERSION`) и `demo/index.txt`; демо-файлы берёт из `android/phone/src/main/assets`. |
| `:android:receiver` | `android/receiver/build.gradle.kts` | Android-библиотека `app.deixis.receiver`: `api(project(":core"))`, libVLC, Compose BOM/ui/foundation, coroutines-android. `BuildConfig.AIRPLAY` = false при `-Pdeixis.store=play`. |
| `:android:tv` | `android/tv/build.gradle.kts` | Приложение `app.deixis.tv`: `implementation(project(":android:receiver"))`. |
| `:android:phone` | `android/phone/build.gradle.kts` | Приложение `app.deixis.phone`: `implementation(project(":android:receiver"))`; `sourceSets["main"].java.srcDir("../../pult/src/main/kotlin")`. Сплиты APK по ABI + универсальный; release — R8 с `android/proguard-deixis.pro`. |

Важно:

- **`pult/` — не Gradle-модуль**, а папка исходников. Её компилируют два модуля: `android/phone` и `desktop`. Поэтому в `pult/` нет зависимостей от Android или Swing: всё системное идёт через `Platform` и `PultHost` (`pult/.../Platform.kt`), а несколько `@Composable`-функций (`rememberFilePicker`, `rememberFolderPicker`, `BackHandlerOn`, `rememberActs`, `VideoPreview`, `Didot`, `LogoFull`, `TextSlideCanvas`, `ScriptureSlideCanvas`) каждая платформа определяет сама с одинаковой сигнатурой в пакете `app.deixis.phone` (не `expect/actual`; контракт описан комментарием в конце `Platform.kt`).
- **Android-модули включаются условно** (`settings.gradle.kts`): только если есть `local.properties`, `ANDROID_HOME` или `ANDROID_SDK_ROOT`, и не задана `DEIXIS_SKIP_ANDROID`. То же условие в корневом `build.gradle.kts` решает, подключать ли Android Gradle Plugin. CI для ядра и компьютера ставит `DEIXIS_SKIP_ANDROID=1`.
- Репозитории: зеркало Maven Central от Google первым (`settings.gradle.kts`), версии — `gradle/libs.versions.toml`.
- `testdata/compat/generator/build.gradle.kts` в `settings.gradle.kts` не включён: его копирует `tools/compat-fixtures.sh` во временный worktree выпуска.

## 2. Исходники по модулям

### 2.1. `diag` — журнал и диагностика

| Файл | Что внутри |
|---|---|
| `diag/src/main/kotlin/app/deixis/diag/Log.kt` | `object Log` — общий журнал всех площадок: последние записи в памяти (для отчёта), всё — в `log.txt` папки данных, если площадка указала файл. Никуда не отправляется. |
| `diag/src/main/kotlin/app/deixis/diag/Diagnostics.kt` | `Status`, `CheckResult`, `DiagReport`, `Lang` (ru/uz/en), `Check`; `object Diagnostics` — прогон проверок площадки с отчётом и ошибками журнала (упавшая/зависшая проверка = FAIL); `object Checks` — проверки, общие для всех площадок. |

### 2.2. `core` — протокол, приёмник, источник, форматы

| Файл | Что внутри |
|---|---|
| `core/.../core/Protocol.kt` | Протокол: `PROTOCOL_VERSION`, `SERVICE_TYPE` (`_deixis._tcp.local.`), `sealed interface Msg` и все сообщения (`Hello`, `PairRequest`, `Show`, `SetMode`, `SetTimer`, `SetOverlays`, `VideoCommand`, `Welcome`, `Status`, `Denied`, `Peers`…), `Content` (`TextContent`, `ImageContent`, `VideoContent`, `PdfContent`), `FileRef`, `TextStyle`, `Mode` (NORMAL/BLACK/LOGO/CLEAR/AWAY), `ReceiverInfo`, `ReceiverState`, `DeixisJson`, `encode`/`decodeMsg` (неизвестное сообщение → null). |
| `core/.../core/Receiver.kt` | `ReceiverCore` — логика приёмника без экрана (состояние показа, файлы, сопряжённые); `ReceiverServer` — Ktor-сервер приёмника: `/info`, `/files/{sha}` (GET/PUT), `/ws`, `/stream`, `/audio`, `/stream/mp4`, `/api/pairing`, `/api/connect-card`, `/api/activate`, `/holyrics/media`, `/remote/closed`; подключает остальные `*Routes`. Также `PageRenderer`, `loadViewKey`/`newViewKey`, `PairingInfo`, `TlsEndpoint`. |
| `core/.../core/Sender.kt` | Сторона источника: `SenderClient` (управляющий канал к одному приёмнику, переподключение с паузой, передача файлов с докачкой), `ScreenStream` (поток кадров экрана, без накопления), `UploadSource`, `Connection`, `PairingException`, `uploadError` (понятный текст ошибки передачи). |
| `core/.../core/Pairing.kt` | `Pairing` — PIN, токены, блокировка перебора по адресу (loopback без паузы); `PairedSender`, `TokenStore`, `MemoryTokenStore`, `FileTokenStore` (JSON через временный файл). |
| `core/.../core/PairingCode.kt` | `PairingCode` — веб-ссылка для QR на экране приёмника (понимается и прежняя `deixis://`); `qrMatrix` (zxing). |
| `core/.../core/Peers.kt` | `PeerDesk` — запросы на подключение и подключённые пульты: кто подтверждает новое устройство, отключение (`ConnectRequest`, `ConnectedRemote`, `PeersState`). |
| `core/.../core/Discovery.kt` | `object Discovery` — mDNS (`_deixis._tcp`) через jmdns; `FoundReceiver`, `localHostName`, `AddressCandidate` (выбор локального адреса, «подозрительные» интерфейсы в конец). |
| `core/.../core/Tls.kt` | `TlsIdentity` (ключ и сертификат экрана), `fingerprintOf`, `PinnedTrustManager` (проверка по запомненному отпечатку), `UntrustedScreenException`, `TlsGateway` (TLS-вход → внутренний `127.0.0.1`), `SelfSigned` (X.509 v3 без сторонних библиотек). |
| `core/.../core/FileStore.kt` | `FileStore` — кэш файлов приёмника по SHA-256, `.part` для недокачанных, вытеснение старых; `StoreException`, `sha256Of`. |
| `core/.../core/Stream.kt` | Трансляция экрана: `ScreenContent`, `FILE_LIMIT_BYTES` (1 ГБ), `StreamFrame` (формат на проводе), `StreamSample`, `StreamHub` (подписчики, отставший прыгает на ключевой кадр), разбор Annex-B/NAL/SPS (`splitNalUnits`, `annexBSamples`, `avcFrameSize`, `avcCodecString`), `Fmp4Muxer` (fMP4 для MSE в браузере). |
| `core/.../core/Audio.kt` | Звук трансляции: `AudioFormat`, `AudioChunk`, `AudioHub` (раздача), `AudioPacer` (общее правило запаса/сброса), `AudioSender`. |
| `core/.../core/MirrorOutput.kt` | `MirrorSink`, `MirrorOutput` — картинка источника на самом приёмнике (AirPlay, экран компьютера) идёт в общий поток экрана; после отключения возвращается прежний показ. |
| `core/.../core/Capture.kt` | `CaptureSource`, `CaptureState`, `CaptureRequest`, `CaptureHost`; маршрут `/api/capture` (501, если платформа не умеет). |
| `core/.../core/Outputs.kt` | Выводы приёмника: `OutputLayout`, `OVERLAY_DIM_DEFAULT`, `MonitorOutput`, `NetworkOutput`, `OutputsState`, запросы `MonitorRequest`/`LinkRequest`/`UnlinkRequest`/`LinkResult`, `OutputsHost`; маршруты `/api/outputs`, `/api/outputs/monitor|link|unlink` (501 без хоста). |
| `core/.../core/Relay.kt` | `ScreenRelay` — пересылка показа на другие экраны Deixis в сети (файлы, `show`, режим, видео; переподключение); `RelayEntry`, `RelayStore`, `MemoryRelayStore`, `FileRelayStore`. |
| `core/.../core/Viewer.kt` | Страница зрителей по QR: `/view`, `/view/assets/{name}`, `/view/ws`, `/view/stream`, `/view/file/{sha}`, `/view/page/{sha}/{page}`; `ViewerState`, `VIEW_BAD_KEY`, `MAX_VIDEO_STREAMS`, `fmp4Stream`. |
| `core/.../core/WebRemote.kt` | Веб-пульт в браузере: `/remote`, `/remote/*` (иконка, манифест, `keepawake.mp4`, `qr.svg`, `session`, файлы и страницы), `/upload/{id}`, `/overlay-samples`, `/pdf/{sha}/pages`, `/convert`, `/bible*`, `/holyrics/call`, `/holyrics/slides`. Классы `UploadArea` (приём файла, хэш считает приёмник), `BibleStore` (модули MyBible на приёмнике), DTO Библии и Holyrics, `HEADER_NAME`, `REMOTE_COOKIE`. |
| `core/.../core/DiagRoutes.kt` | `/api/diagnostics`, `/api/diagnostics/log`; `ReceiverCore.diagnose`, `ReceiverCore.logText`, проверки самого экрана. |
| `core/.../core/AirPlayRoutes.kt` | `/api/airplay` — включить/выключить приём AirPlay (501 — сборка без AirPlay). |
| `core/.../core/Updates.kt` | Обновления: `UpdateChannel`, `UpdateMode`, `UpdateTarget`, `Release`/`ReleaseAsset`, `parseVersion`/`compareVersions`, `parseReleases`, `latestFor`, `pickAsset` (имена файлов из `release.yml`), `parseSha256Sums`, `parseChangelog`/`whatsNew`/`bundledChangelog`, `UpdateHost`, `ReleaseSource`, `GitHubReleases` (с запасным `release.json`), `Updater`; маршрут `/api/update`. |
| `core/.../core/Media.kt` | `MediaKind`, `mediaKindOf`, `mimeOf` — тип файла по расширению. |
| `core/.../core/Convert.kt` | `DocConverter`, `PdfResult`, `Converted`, `LibreOfficeConverter` (запуск установленного `soffice` со своим профилем), `Conversions` (без повторного перевода одного файла). |
| `core/.../core/Cfb.kt` | `Cfb` — своё чтение Compound File Binary (OLE2) для PPT/DOC с проверкой цепочек. |
| `core/.../core/Pptx.kt` | Модель колоды (`Deck`, `DeckSlide`, `DeckItem`, `DeckText`, `DeckPara`, `DeckRun`, `DeckPaint`, `Box`, `Geom`, `PathCmd`, `TableCell`…), `PptxException`, `object Pptx` — своё чтение PPTX. |
| `core/.../core/Ppt.kt` | `object Ppt` — чтение старого PPT (CFB → «PowerPoint Document» → записи OfficeArt) в ту же модель `Deck`. |
| `core/.../core/Docx.kt` | `WordPage`, `WordBlock`, `WordCell`, `WordDoc`, `object Docx` (чтение DOCX, `FORMATS`), `object DocxLayout` (вёрстка по страницам в колоду `Deck`). |
| `core/.../core/DeckRender.kt` | `DeckPainter` (кисть: Canvas на Android, Graphics2D в проверке), `Outline`, `object DeckRender` — отрисовка колоды. Используется `android/receiver/PptxPdf.kt`; компьютер для PPTX/PPT идёт своим путём (`desktop/OfficeConverter.kt`). |
| `core/.../core/TextLayout.kt` | Раскладка текста слайда: `MAX_LINES_PER_SLIDE`, `SAFE_MARGIN`, `OVERSCAN_MARGIN`, `SafeArea`/`safeArea`, `fitParagraphs`, `splitIntoSlides`, `wrapLine`, `layoutSlideText`, `LogoStyle`, `object LogoMark`. Платформы только меряют шрифт и рисуют. |
| `core/.../core/VideoFit.kt` | `VideoRect`, `displaySize` (SAR, поворот), `videoRect` — одна формула кадра для всех экранов. |
| `core/.../core/Countdown.kt` | Отсчёт: `AfterZero`, `CountdownFormat`, `CountdownContent`, `countdownFace`, `formatCountdown`, `countdownFrameMs`, `layoutCountdown` (цифры в ячейках равной ширины). |
| `core/.../core/Overlays.kt` | Слой «Поверх показа»: `Overlays`, `LogoOverlay`, `LowerThird`, `Ticker`, `ClockOverlay`, `Corner`, геометрия (`tickerBox`, `lowerThirdBox`, `logoBox`, `cornerBox`…), `overlaysVisible`, `OverlayMotion`/`OverlayFrame` (появление и уход), `OVERLAY_MOTION_MS`. |
| `core/.../core/OverlaySamples.kt` | `OverlaySample`, `object OverlaySamples` — готовые примеры слоя с картинками из `resources/overlay-samples`. |
| `core/.../core/Bible.kt` | MyBible: `SqlSource`, `BibleModule`, `BibleBook`, `Verse`, `cleanVerseText`, `BibleRef`/`parseBibleRef`, `ScriptureOptions`, `ScriptureContent`, `buildScriptureSlides`, `refLabel`, `layoutScripture`. |
| `core/.../core/BibleSearch.kt` | `BibleSearch` (поиск по тексту в памяти, без регистра и «ё»), `VerseHit`, `allVerses`, `VerseRow`. |
| `core/.../core/DefaultBibles.kt` | `object DefaultBibles` — установка встроенных RST и KJV из ресурсов в папку модулей (удалённый не возвращается). |
| `core/.../core/SongText.kt` | `SongSlide`, `object SongText` — песня/текст как текст с `[подписями]` и пустыми строками между слайдами; деление и слияние слайдов. |
| `core/.../core/SongFormats.kt` | `ImportedSong`, `object SongFormats` — импорт ChordPro и OpenLyrics. |
| `core/.../core/Holyrics.kt` | Клиент API Server Holyrics: DTO (`HolyricsSong`, `HolyricsTheme`, `HolyricsPresentation`…), `HolyricsClient` (вход по хешу), `HolyricsException`/`HolyricsPermissions`, `HolyricsLink` (ссылка для QR), `holyricsColor`, `HolyricsTheme.toTextStyle`. |
| `core/.../core/HolyricsBible.kt` | `HolyricsBibleVersion`, `object HolyricsBible` — стихи из модулей Deixis показываются в Holyrics его переводом. |
| `core/.../core/HolyricsBridge.kt` | `HolyricsBridge`, `HolyricsMediaRequest` — фото/видео/аудио из папки Holyrics на том же компьютере (папку спрашивает у Holyrics на 127.0.0.1). |
| `core/.../core/HolyricsDiscovery.kt` | `object HolyricsDiscovery` — поиск Holyrics в сети /24 по ответу на «Auth». |
| `core/.../core/Dlna.kt` | Запасной вывод на DLNA-телевизор: `DlnaRenderer`, `DlnaState`, `object Dlna` (SSDP), `DlnaController` (AVTransport), `DlnaFileServer` (`/dlna/{id}/{name}`). |

Подпапка `core/.../core/airplay/` — приём AirPlay, перенос UxPlay (см. шапки файлов и `NOTICE`):

| Файл | Что внутри |
|---|---|
| `core/.../airplay/AirPlayHost.kt` | `AirPlayHost` — включение приёма на экране (по умолчанию выключен), выбор и ключ в папке данных; `AirPlayState`. |
| `core/.../airplay/AirPlayServer.kt` | `AirPlayServer` — сервер AirPlay (RTSP, объявление в mDNS после `start`), один iPhone одновременно, необязательный пароль. |
| `core/.../airplay/AirPlayPairing.kt` | `AirPlayIdentity` (постоянный Ed25519-ключ), `AirPlayPairSession` (pair-verify), `airPlaySessionKey`, `MirrorCipher` (AES-CTR видео). |
| `core/.../airplay/FairPlay.kt` | `object FairPlay` — `setupReply`, `handshakeReply`, `decryptKey` (порядок как в UxPlay). |
| `core/.../airplay/FairPlayTables.kt` | Таблицы playfair строками hex; сгенерирован из таблиц C, руками не правится. |
| `core/.../airplay/Bplist.kt` | `object Bplist` — двоичный plist Apple (`bplist00`), чтение и запись. |
| `core/.../airplay/MirrorStream.kt` | `MirrorEvent`, `MirrorReader` — пакеты потока зеркала → Annex-B. |

Сокращение `core/...` = `core/src/main/kotlin/app/deixis`.

**Ресурсы ядра** (`core/src/main/resources/`):

| Файл | Назначение |
|---|---|
| `web/remote.html` | Веб-пульт в браузере (около 3100 строк, HTML+CSS+JS в одном файле). Отдаётся по `/remote` (`WebRemote.kt`). Также копируется в веб-демо (`tools/demo`). |
| `web/view.html` | Страница зрителей по QR (`/view`, `Viewer.kt`). |
| `web/remote-icon.png` | Значок веб-пульта (`/remote/icon.png`, apple-touch-icon). |
| `web/keepawake.mp4` | Беззвучное видео, которым веб-пульт не даёт экрану телефона гаснуть, если нет Wake Lock API (`/remote/keepawake.mp4`). |
| `web/GFSDidot-Regular.ttf`, `web/GFSDidot-OFL.txt` | Шрифт знака ΔΕΙΞΙΣ и лицензия; отдаётся через `/view/assets/`. |
| `web/NotoSans-SemiBold.ttf`, `web/NotoSans-OFL.txt` | Шрифт слайдов в браузере и лицензия. |
| `bible/RST.SQLite3`, `bible/KJV.SQLite3` | Встроенные модули MyBible (собирает `tools/bible/make_modules.py`). |
| `overlay-samples/deixis.png`, `live.png`, `spring.png` | Картинки примеров слоя (рисует `tools/overlay-samples.py`). |
| `changelog.md` (генерируется) | Копия корневого `CHANGELOG.md` для «Что нового» (`bundledChangelog`). |

### 2.3. `pult` — общий пульт (Compose), пакет `app.deixis.phone`

| Файл | Что внутри |
|---|---|
| `pult/.../phone/App.kt` | Корень интерфейса `DeixisPhone`: `AppTab`, `Ui` (вкладка, шторка, полноэкранный слайд, тосты), `SheetSpec`, `FullSpec`, `LocalUi`, вкладки, хост шторок, тосты, `Modifier.swipes`. |
| `pult/.../phone/Controller.kt` | `Controller` — вся логика пульта (≈1900 строк): данные/профили/копии, поиск и подключение экранов (`targets`, `outputs`, `connect`, `pair`), показ (`live`, `preview`, `goLive`, `step`, `mode`, `video`), отсчёт, слой, темы и оформление, передача файлов заранее, импорт, Библия, Holyrics, трансляция экрана, диагностика. Также `Target`, `OutState`, `OutputView`, `LargeFile`, `Approval`, `OnScreens`, `mb()`. |
| `pult/.../phone/Model.kt` | Данные: `Kind`, `LibItem`, `Setlist`, `AppSettings`, `WorkMode` (DEIXIS/HOLYRICS), `ThemePanel`, `StoreData`, `OverlayDrafts`, `Theme`, `Slide`, `Source`, `Cue`; хранение: `writeDurably`, `StoreWriter` (запись в своём потоке), `Store`. |
| `pult/.../phone/StoreRead.kt` | `ReadStore`, `object StoreRead` — чтение библиотеки строго, затем по частям (данные более новой версии). |
| `pult/.../phone/Profiles.kt` | `Profile`, `ProfileKind`, `Accent`, `ProfileBook`, `Profiles` (папки `profiles/<id>/`), шторка `ProfilesSheet` и редактор. |
| `pult/.../phone/Backups.kt` | `BackupInfo`, `Backups` — резервные копии библиотеки (в памяти приложения и в выбранной папке). |
| `pult/.../phone/BackupsUi.kt` | `BackupsSection`, список копий и подтверждение восстановления. |
| `pult/.../phone/Platform.kt` | Контракт платформы: `Platform`, `FileMeta`, `Features`, `Prefs`, `DlnaOut`/`NoDlna`, `PultHost`/`LocalHost`, `FilePicker`, `FolderPicker`; комментарий со списком функций, которые определяет каждая платформа. |
| `pult/.../phone/Strings.kt` | `Lang`, `object I18n` (текущий язык), `object L` — все строки пульта на ru/uz/en. |
| `pult/.../phone/Theme.kt` | Токены интерфейса: `Palette`, палитры `Light`/`Dark`/`Paper`/`Oled` (private), `P` (CompositionLocal текущей палитры), `paletteOf`, `accentColors`, `contrast`, `object T` (размеры, скругления, движение), `object V` (цвета предпросмотра видео); значки `icon`/`Icon`; базовые элементы `Txt`, `Btn`, `Pill`, `Cell`, `Group`, `Seg`, `Field`, `Switch`, `Stepper`, `Swatch`, `Note`, `Modifier.press`, `combinedClick`; `textSizeStep`. |
| `pult/.../phone/ShowScreen.kt` | Главный экран «Показ»: верхняя полоса экранов, мониторы, плитки «Показать», сетлист и сетка слайдов, `tapSlide`/`liveSlide`, `ControlBar`, перемотка видео, `Mode.inHall`. |
| `pult/.../phone/SlideView.kt` | Превью слайдов: `LocalScreenRatio`/`screenRatio`, `Frame` (рамка «В зале»/предпросмотр), `SlideView`, `CueView`, миниатюры файлов (`Thumbs`, `ItemThumb`), `LocalLogoStyle`, `LocalLogoImage`. |
| `pult/.../phone/FullView.kt` | Полноэкранный просмотр слайда (`FullHost`, `FullView`), всегда на чёрном. |
| `pult/.../phone/Screens.kt` | Вкладки «Библиотека», «Сетлисты», «Устройства» (`LibraryScreen`, `SetlistsScreen`, `DevicesScreen`), `Header`, `FileBadge`. |
| `pult/.../phone/Sheets.kt` | Шторки: `Acts`/`LocalActs`, подключение (`ConnectSheet`, `PinSheet`, `ApproveSheet`, ручной адрес), `SettingsSheet`, выбор файлов (`PickKind`, `PickSheet`), `MirrorSheet`, `LargeSheet`, «Найти», сетлисты, меню элемента, оформление (`StyleSheet`), `AddSheet`, модули Библии, `Qr`, `ViewersBlock`. |
| `pult/.../phone/SongEditor.kt` | Редактор песни/текста по слайдам с подписями. |
| `pult/.../phone/Themes.kt` | `BUILT_IN_THEMES`, `LocalBackgrounds`, `ThemesRow`, `ThemeEditor`, `StyleControls`, `DefaultStyleEditor`. |
| `pult/.../phone/ThemePanel.kt` | Панель тем на главном (`ThemeStrip`, `ThemeGrid`): свои, встроенные и темы Holyrics. |
| `pult/.../phone/ThemeDrag.kt` | Перетаскивание темы на элемент/слайд/«Сейчас»: `ThemeDrag`, `themeTarget`, `themeSource`, `ThemeGhost`. |
| `pult/.../phone/OverlayUi.kt` | Шторка слоя «Поверх показа» (`OverlaySheet`). |
| `pult/.../phone/CountdownUi.kt` | Шторка отсчёта, `rememberTimerLeft`, `timerText`. |
| `pult/.../phone/BibleScreen.kt` | Вкладка «Библия»: стихи, история, навигатор, панель кнопок, параметры Писания. |
| `pult/.../phone/BibleDesk.kt` | `BibleDesk` — состояние открытой главы/истории; `ChapterText`, `Passage`. |
| `pult/.../phone/BibleLibrary.kt` | `BibleLibrary` — модули .SQLite3 на устройстве пульта. |
| `pult/.../phone/BiblePicker.kt` | Выбор книги/главы/стихов и параметров для элемента «Писание». |
| `pult/.../phone/HolyricsSource.kt` | `HolyricsSource` — Holyrics как источник и как экран; `HolyricsStatus`, `HolyricsFound`, `HolyricsLive`. |
| `pult/.../phone/HolyricsUi.kt` | Окно Holyrics: подключение, недостающие права, библиотека, QR-ссылка, `HolyricsLiveCard`. |
| `pult/.../phone/HolyricsMode.kt` | Режим «пульт Holyrics»: `WorkModeSwitch`, плейлист, клавиши F8–F10, библиотека/плейлисты/темы/Библия Holyrics. |
| `pult/.../phone/HolyricsImport.kt` | `HolyricsHaul`, `object HolyricsImport` — перенос песен, текстов, тем и плейлиста в библиотеку Deixis с постоянными id. |
| `pult/.../phone/ConnectCodesUi.kt` | `ConnectCodesSheet` — коды подключения экранов. |
| `pult/.../phone/DiagnosticsUi.kt` | `DiagnosticsSheet` — отчёт диагностики. |
| `pult/.../phone/UpdatesUi.kt` | Обновления в пульте: `updateStatus`, `updateAction`, `UpdatesSection`, `WhatsNewSheet`, `UpdatesEffects`, `ScreenUpdateSheet`, `ScreenInputs`, `ScreenOutputs` (настройки экрана в «Устройствах»). |

Сокращение `pult/...` = `pult/src/main/kotlin/app/deixis`.

### 2.4. `desktop` — Deixis для компьютера

Пакет `app.deixis.desktop` (`desktop/src/main/kotlin/app/deixis/desktop/`):

| Файл | Что внутри |
|---|---|
| `Main.kt` | `main`: команды `receive` (приёмник, ключи `--operator/--no-operator`, роль), `send`, `list`, `dlna`, `help`; разбор аргументов. |
| `ReceiverApp.kt` | `ReceiverOptions`, `ReceiverApp` — приёмник с несколькими выводами: окна мониторов, пересылка на экраны в сети, трей, AirPlay, захват, обновления, окно пульта (`openOperator`: Compose-`PultWindow`, при headless/сбое — браузер через `OperatorLauncher`). |
| `RoleChooser.kt` | `Role` (REMOTE/SCREEN/BOTH), `RoleStore`, `RoleChooser` — окно «Как работает этот компьютер?». |
| `Monitors.kt` | `ScreenInfo`, `withoutMirrors` (проектор-зеркало не второй монитор), `SavedMonitor`, `MonitorSettings`, `MonitorSettingsStore`, `resolveMonitors`, `OutputLayout.title`. |
| `OutputWindow.kt` | `WindowMode`, `OutputHost`, `OutputWindow` — окно вывода на одном мониторе со своей раскладкой; `KeyAction`, `keyAction` (Esc/F11/Ctrl+Q/Ctrl+O). |
| `OutputFrames.kt` | `OutputTokens` (токены вывода), `FrameState`, `captionContent`, `overlayAlpha`, `overlayContent`, `OutputFrames` — кадры раскладок «Сцена», «Титры», «Хромакей», «Наложение». |
| `SlideRenderer.kt` | `SlideRenderer` — кадр приёмника в картинку (текст, Писание, фото, PDF, логотип, экран ожидания `IdleInfo`); `Fonts`, `REF_ALPHA`, `renderPdfPagePng`. |
| `OverlayPainter.kt` | `OverlayPainter` — рисование слоя (логотип, плашка, строка, часы) на компьютере. |
| `OverlayWindow.kt` | `OverlayWindow` — прозрачное окно над окном вывода (VLC рисует в своё системное окно). |
| `ClickThrough.kt` | `object ClickThrough` — окно, прозрачное для мыши (Windows/X11/macOS). |
| `PdfPages.kt` | `object PdfPages` — открытый PDF (PDFBox), страницы в памяти, следующая заранее, закрытие простаивающих. |
| `PdfEngines.kt` | `PdfEngine`, `LibreOfficeEngine`, `MsOfficeEngine` (PowerShell/COM), `KeynoteEngine` (AppleScript), `findPdfEngines`. |
| `OfficeConverter.kt` | `OfficeConverter : DocConverter` — внешние программы по порядку, затем встроенный Apache POI для PPTX/PPT с предупреждениями; `BuiltInTableStyles`. |
| `VideoPlayer.kt` | `VideoPlayer` — видео через libVLC (vlcj); без VLC `available = false`. |
| `VideoPane.kt` | `VideoPane` — размещение кадра по `videoRect`, поля (чёрные/цвет/размытие). |
| `VideoProbe.kt` | `object VideoProbe` — длительность и кадр видео через FFmpeg (для пульта). |
| `BackgroundVideo.kt` | `BackgroundVideo` — видеофон слайдов (VLC в память, без звука, по кругу). |
| `ScreenView.kt` | `ScreenView` — показ трансляции телефона с малой задержкой (декодер → окно). |
| `LowLatencyDecoder.kt` | `LowLatencyDecoder` — H.264-декодер FFmpeg без буфера (slice threads, LOW_DELAY). |
| `ScreenAudio.kt` | `ScreenAudio` — звук трансляции в колонки компьютера (правило `AudioPacer`). |
| `ScreenCapture.kt` | `CaptureArea`, `ScreenCapture` — съёмка монитора/окна (x11grab/gdigrab/avfoundation) и сжатие в H.264 через FFmpeg. |
| `CaptureTargets.kt` | `AppWindow`/`AppWindows` (окна программ для трансляции), `XOutput`/`Xrandr` (разбор xrandr), `LinuxVirtualScreen` (виртуальный монитор через xrandr), `VirtualScreen` (выбор реализации по системе). |
| `WindowsVirtualScreen.kt` | `object WindowsVirtualScreen` — включение/выключение драйвера Deixis Display через `deixis-display.exe` с правами администратора. |
| `MacVirtualScreen.kt` | `object MacVirtualScreen` — виртуальный дисплей через CGVirtualDisplay (недокументированный механизм). |
| `MacScreen.kt` | `object MacScreen` — полный экран macOS (`com.apple.eawt`), ⌘Q, меню Dock. |
| `Operator.kt` | `OperatorLauncher` (веб-пульт в окне Chromium-браузера `--app`), `TrayActions`, `Tray` (значок в области уведомлений). |
| `ApprovalWindow.kt` | `ApprovalWindow` — «Новое подключение» на мониторе оператора, когда окно пульта закрыто. |
| `HolyricsQrWindow.kt` | `object HolyricsQrWindow` — QR со ссылкой Holyrics для телефона. |
| `DesktopUpdates.kt` | `UpdateApp`, `DesktopUpdates` (загрузка, установка при выходе), `UpdatesWindow`, `WindowTokens` (токены обычных окон). |
| `DesktopChecks.kt` | `object DesktopChecks` — проверки диагностики компьютера (мониторы, VLC, FFmpeg, перевод презентаций). |
| `SendCommand.kt` | `SendCommand` (`deixis send`), `listReceivers` (`deixis list`). |
| `DlnaCommand.kt` | `dlnaCommand` (`deixis dlna`). |
| `JdbcSql.kt` | `JdbcSql : SqlSource` — MyBible через sqlite-jdbc. |
| `AppIcon.kt` | `object AppIcon` — значок окон, трея и Dock. |
| `Words.kt` | `object Words` — строки окон самой программы (ru/uz/en по языку системы). |

Пакет `app.deixis.phone` на компьютере (`desktop/src/main/kotlin/app/deixis/phone/`) — платформа для общего пульта:

| Файл | Что внутри |
|---|---|
| `DesktopPlatform.kt` | `DesktopPlatform : Platform` — файлы `file:///`, перевод тем же `DocConverter`, что у приёмника, PDF через PDFBox, видео через FFmpeg; `FilePrefs` (.properties). |
| `DesktopHost.kt` | `DesktopHost : PultHost` — обновления программы, Holyrics, отзыв. |
| `PultWindow.kt` | `PultWindow` — окно Compose с пультом; свой экран по 127.0.0.1 без PIN; клавиши кликера. |
| `PlatformUi.kt` | Реализация контракта из `Platform.kt`: `rememberFilePicker`, `rememberFolderPicker`, `DesktopBack`/`BackHandlerOn`, `rememberActs`, `Didot`, `LogoFull`, `TextSlideCanvas`, `ScriptureSlideCanvas` (рисует тем же движком, что зал); `pultFrame`. |
| `DesktopVideo.kt` | `VideoPreview` — предпросмотр видео в пульте на компьютере. |

Ресурсы: `desktop/src/main/resources/app-icon.png`; генерируемые `deixis-version.txt` и `demo/index.txt`; демо-файлы из `android/phone/src/main/assets/demo/`. Значки установщиков — `desktop/packaging/deixis.{icns,ico,png}`.

### 2.5. `android/receiver` — приёмник на Android (общий для ТВ, «Стать экраном» и вывода по кабелю)

Пакет `app.deixis.receiver` (`android/receiver/src/main/kotlin/app/deixis/receiver/`):

| Файл | Что внутри |
|---|---|
| `ReceiverRuntime.kt` | `ReceiverRuntime` — ядро, сервер, объявление в сети; `loopbackOnly = true` для вывода по кабелю с телефона. Подключает AirPlay при `BuildConfig.AIRPLAY`. |
| `ReceiverScreen.kt` | `ReceiverScreen` (Compose-экран зала), `Wordmark`, шрифты `Didot`/`NotoSans`, `decodeSampled`, `renderPdfPage`. |
| `SlideCanvas.kt` | `TextSlideCanvas`, `ScriptureSlideCanvas` — рисование слайдов (используется и превью пульта на телефоне). |
| `SlideBitmap.kt` | `renderSlideBitmap` — слайд картинкой для DLNA-телевизоров. |
| `LogoMarkView.kt` | `LogoFull`, `drawLogoMark` — знак по раскладке `LogoMark`. |
| `OverlayLayer.kt` | `OverlayLayer` — слой поверх показа на Android. |
| `PdfPages.kt` | `object PdfPages` — PdfRenderer, страницы в памяти, следующая заранее. |
| `PptxPdf.kt` | `object PptxPdf` — PPTX/PPT/DOCX → PDF на устройстве через `Pptx`/`Ppt`/`Docx` + `DeckRender` (шрифты Carlito/Caladea/Liberation). |
| `VlcPlayer.kt` | `VlcPlayer` (видео через libVLC), `BackdropPlayer` (видеофон). |
| `ScreenDecoder.kt` | `ScreenDecoder` — MediaCodec для трансляции, без буфера. |
| `ScreenSound.kt` | `playScreenSound` — AudioTrack малой задержки. |
| `Away.kt` | `followAway` («Уйти с экранов»), `guardBackOnHall` (двойное «Назад»). |
| `ApkUpdates.kt` | `ApkUpdates` (PackageInstaller), `InstallResultReceiver`, `PackageReplacedReceiver`, `ScreenRole`, `ScreenUpdateHost`. |
| `AndroidChecks.kt` | `object AndroidChecks` — проверки диагностики Android. |
| `AndroidSql.kt` | `AndroidSql : SqlSource` — MyBible через SQLite Android. |

Ресурсы: `res/font/` (`gfs_didot`, `noto_sans_semibold`, `carlito_*`, `caladea_*`, `liberation_sans_*`); `AndroidManifest.xml` регистрирует `InstallResultReceiver` и `PackageReplacedReceiver`.

### 2.6. `android/phone` — Deixis (пульт + экран), пакет `app.deixis.phone`

| Файл | Что внутри |
|---|---|
| `MainActivity.kt` | `PhoneApp : Application`, `MainActivity` (пульт), `clickerKey` (Bluetooth-кликер/педаль, кнопки громкости). |
| `AndroidPlatform.kt` | `AndroidPlatform : Platform` (файлы через систему документов, перевод своим движком), `AndroidPrefs`. |
| `PhoneHost.kt` | `PhoneHost : PultHost`. |
| `PlatformUi.kt` | Реализация контракта пульта для Android (`rememberFilePicker`, `BackHandlerOn`, `rememberActs`, `Didot`, `LogoFull`, `TextSlideCanvas`… через `app.deixis.receiver`). |
| `VideoPreview.kt` | `VideoPreview` — предпросмотр видео на телефоне. |
| `HdmiOutput.kt` | Вывод по кабелю USB-C → HDMI и Miracast: `CastOutput` (DisplayListener), `CastActivity` (окно на втором экране), `CastPresentation` (запасной путь). Имя файла не совпадает с именами классов. |
| `ScreenActivity.kt` | `ScreenActivity` — режим «Стать экраном» (тот же приёмник, что в TV); `ScreenRuntime`. |
| `ScreenShare.kt` | `ScreenShareService` — демонстрация экрана (MediaProjection → H.264 → `ScreenStream`, звук → `AudioSender`). |
| `ShowService.kt` | `ShowService` — видимая служба, держит связь с экранами в фоне. |
| `DlnaOutput.kt` | `DlnaOutput : DlnaOut` — DLNA-телевизор как экран. |
| `Feedback.kt` | `object Feedback`, `PhoneFeedbackSection` — отзыв через «Поделиться». |

Ресурсы: `assets/demo/` (демо-набор первого запуска; его же берёт `desktop`), `res/layout/notif_show*.xml` и `res/drawable/n_*.xml` (уведомление `ShowService` с кнопками), `res/drawable/ic_launcher.xml`, `banner.xml`, `ic_stat_deixis.xml`, `res/values/{themes,notification}.xml`, `res/xml/shared_files.xml` (FileProvider), `res/xml/data_extraction_rules.xml`.

### 2.7. `android/tv` — Deixis TV, пакет `app.deixis.tv`

| Файл | Что внутри |
|---|---|
| `android/tv/src/main/kotlin/app/deixis/tv/DeixisTvApp.kt` | `DeixisTvApp : Application` — держит `ReceiverRuntime`, пока жив процесс. |
| `android/tv/src/main/kotlin/app/deixis/tv/MainActivity.kt` | `MainActivity` — экран приёмника на ТВ. |

Ресурсы: `res/drawable/{banner,ic_launcher}.xml`, `res/values/themes.xml`, `res/xml/data_extraction_rules.xml`.

## 3. Прочие папки

### `windows-display/` — виртуальный монитор Windows

| Файл | Что внутри |
|---|---|
| `windows-display/driver/DeixisDisplay.cpp` | Indirect Display Driver (IddCx, UMDF 2): устройство `Root\DeixisDisplay`, монитор с EDID «Deixis» 1920×1080 (и 1600×900, 1280×720). Написан по документации Microsoft. |
| `windows-display/driver/DeixisDisplay.inf`, `DeixisDisplay.vcxproj` | INF драйвера и проект Visual Studio. |
| `windows-display/installer/deixis-display.cpp` | Помощник `deixis-display.exe`: `install <inf> <cer>` (сертификат в доверенные + устройство), `remove`, `status` (код 0/1), `--log <файл>`. Вызывается из `desktop/WindowsVirtualScreen.kt`. |
| `windows-display/build.ps1` | Сборка драйвера и помощника (VS 2022 + WDK), подпись своим сертификатом (закрытый ключ удаляется после подписи); результат в `out\`. Запускается в `.github/workflows/windows-display.yml`. |

### `tools/`

| Файл | Что делает и когда |
|---|---|
| `tools/android-smoke.sh` | Запуск APK на эмуляторе: установка, 20 с жизни, проход пульта по вкладкам; журналы и снимки в `smoke/`. CI: `smoke.yml`, `release.yml`. |
| `tools/android-shots.sh` | Снимки интерфейса пульта на эмуляторе (телефон, вертикальный зал, планшет) в `shots/`. CI: `ui-shots.yml`. |
| `tools/ui_names.py` | Названия кнопок пульта на всех языках для сценариев двух скриптов выше. |
| `tools/test-failures.py` | Сводка упавших проверок из XML-отчётов Gradle. CI: `ci.yml`. |
| `tools/compat-fixtures.sh` | Записывает образцы данных выпуска в `testdata/compat` кодом этого выпуска (`tools/compat-fixtures.sh vX.Y.Z`); вручную после выпусков, где менялись сохраняемые данные. |
| `tools/app-icons.py` | Рисует значок Deixis для компьютера (`desktop/packaging`, `app-icon.png`). Вручную. |
| `tools/overlay-samples.py` | Рисует картинки примеров слоя в `core/src/main/resources/overlay-samples`. Вручную. |
| `tools/bible/make_modules.py` | Собирает встроенные модули RST и KJV (`core/src/main/resources/bible`). Вручную. |
| `tools/create-signing-key.bat` | Windows: создаёт ключ подписи Android в «Документах» и копирует его для секрета GitHub. Вручную, один раз. |
| `tools/demo/build.py` | Собирает веб-демо в `build/demo/` (настоящие `remote.html`/`view.html` с демо-приёмником). CI: `demo.yml`. |
| `tools/demo/hub.js` | Демо-приёмник в браузере (тот же протокол, что `Protocol.kt`). |
| `tools/demo/shim.js` | Вставка в копии `remote.html` и `view.html`: вместо сети — `hub.js` в родительском окне. |
| `tools/demo/app.html`, `tools/demo/shell.html` | Страницы витрины демо. |
| `tools/demo/make_assets.py`, `make_docx.py`, `art.py` | Демо-материалы: лекция PPTX→PDF→JPEG, документ Word, картинки и видео. Результаты лежат в `tools/demo/media/` и `android/phone/src/main/assets/demo/`. |
| `tools/smoke/xorg-dummy.conf` | Xorg с dummy-драйвером для проверки на Linux. CI: `desktop-smoke.yml`. |

### `.github/workflows/`

| Файл | Назначение |
|---|---|
| `ci.yml` | Сборка и проверки на push/PR: задания `core-desktop`, `desktop-gui`, `android`. |
| `release.yml` | Выпуск по метке `vX.Y.Z` или вручную; Android, smoke, компьютер, публикация. |
| `smoke.yml` | Запуск релизных APK на эмуляторах Android 8, 11, 13. |
| `desktop-smoke.yml` | Запуск собранной программы для компьютера на настоящих системах. |
| `ui-shots.yml` | Снимки пульта на эмуляторе в `refs/ci/ui-shots`. |
| `windows-display.yml` | Сборка, подпись и проверка драйвера Deixis Display. |
| `demo.yml` | Веб-демо на GitHub Pages, вручную. |

### Остальное

| Путь | Что это |
|---|---|
| `prototype/index.html` | Кликабельный прототип экранов пульта (один HTML). Справочник вида, в сборку не входит. |
| `testdata/compat/` | Данные прошлых версий: `library-*.json` (библиотека телефона), `tokens-*.json` (сопряжённые пульты), `monitors-*.json` (мониторы компьютера), `README.md`. Читаются `CompatLibraryTest`, `CompatTest`, `CompatDesktopTest` (путь передаётся свойством `deixis.compat`). Руками не правятся. |
| `testdata/compat/generator/` | `Fix.kt` (`app.deixis.phone.FixKt`) и `build.gradle.kts` — генератор образцов, который `tools/compat-fixtures.sh` запускает в worktree выпуска. |
| `android/proguard-deixis.pro` | Правила R8 для release-сборок Android: `-keep class app.deixis.**` целиком, Ktor, jmdns, BouncyCastle, VLC, zxing, сериализаторы kotlinx; имена не обфусцируются. |
| `docs/` | `README.md`; `docs/dev/` — документация для разработчиков (этот файл и соседние); `docs/audit/` — `PLAN.md`, `REPORT.md`. |

## 4. Проверки

Запуск: `./gradlew :core:test`, `:desktop:test`, `:diag:test`, `:android:phone:testDebugUnitTest`. GUI-проверки компьютера включаются `-Ddeixis.gui=1`. Ручные свойства: `deixis.dumpStream`, `deixis.render`, `deixis.renderFile`, `deixis.pptDump`, `deixis.docxPng`, `deixis.docxFile` (core), `deixis.outputsPng`, `deixis.trayPng` (desktop).

### `diag`

| Файл | Что проверяет |
|---|---|
| `diag/src/test/.../DiagnosticsTest.kt` | Общие проверки и отчёт; упавшая или зависшая проверка — FAIL; тексты проверок на трёх языках. |

### `core` (`core/src/test/kotlin/app/deixis/core/`)

| Файл | Что проверяет |
|---|---|
| `ProtocolTest.kt` | Каждое сообщение переживает кодирование; сообщение более новой версии и неизвестные поля пропускаются. |
| `EndToEndTest.kt` | Приёмник + источник целиком: PIN, неизвестный токен, загрузка и докачка, показ, ошибка при отсутствии файла (экран не меняется), переподключение, команды видео, отказ без сопряжения. |
| `PairingTest.kt` | Блокировка перебора PIN по адресу и её рост, смена PIN, токены и ключ зрителей после перезапуска, локальное имя. |
| `PeersTest.kt` | Кто подтверждает подключение (оператор, окно экрана, подключённый телефон), отключение забывает токен, диагностика экрана. |
| `SecureLinkTest.kt` | Объявление TLS-входа, переход сопряжения на TLS, отказ чужому сертификату, обновление старого сопряжения. |
| `TlsTest.kt` | Стабильный ключ и сертификат; клиент с отпечатком через `TlsGateway` и отказ другому экрану. |
| `ConnectCardTest.kt` | Карточка подключения (PIN и QR) на экране: включение, снятие следующим слайдом, запрет посторонним. |
| `LocalOperatorTest.kt` | Пульт этого компьютера: закрытие окна, сигнал ухода, перезагрузка страницы, второй запуск. |
| `LocalAddressTest.kt` | Выбор локального адреса: частные сети, CGNAT, Wi-Fi выше остальных, понижение подозрительных интерфейсов. |
| `DiscoveryTest.kt` | Приёмник находится через mDNS. |
| `FileStoreTest.kt` | Докачка, неверное смещение, порча, размер, выход за кэш, вытеснение (кроме показываемого), очистка старше суток. |
| `UploadErrorTest.kt` | Текст ошибки передачи: таймаут → «экран не отвечает». |
| `StreamTest.kt` | Annex-B, размер из SPS, кадры на проводе, подписчики и ключевые кадры, fMP4, трансляция до зала и зрителей, звук, запас звука, требование сопряжения. |
| `ViewerTest.kt` | Страница зрителей: ресурсы, ссылка, неверный ключ, счётчик, 50 зрителей, файлы с Range, страницы PDF. |
| `WebRemoteTest.kt` | Веб-пульт: страница и QR, загрузка с докачкой, модули Библии и поиск, превью, перевод презентации, Holyrics через приёмник. |
| `OutputsTest.kt` | `/api/outputs` без хоста — 501, маршруты до хоста, веб-экран не считается зрителем. |
| `RelayTest.kt` | Пересылка на экраны: файлы, режим, слой, переподключение, мёртвый экран не мешает, видео и позиция, повторный PIN, один экран по адресу и по mDNS. |
| `TextLayoutTest.kt` | Деление на слайды, подбор размера, перенос по словам, безопасные поля, заглавные, длинное слово, `PairingCode`. |
| `AspectTest.kt` | Текст и Писание помещаются на любом формате экрана. |
| `VideoFitTest.kt` | Режимы вписывания видео, SAR и поворот. |
| `CountdownTest.kt` | Формат табло, после нуля, собственные часы экранов, неподвижные цифры. |
| `OverlaysTest.kt` | Слой: смена слайдов, «Чёрный», логотип и его файл, старое состояние читается, геометрия, бегущая строка, часы, «Уйти с экранов». |
| `OverlayMotionTest.kt` | Появление/уход слоя, мгновенный уход на «Чёрный», примеры с картинками. |
| `BibleTest.kt` | Модуль MyBible: книги, очистка разметки, разбор ссылок, слайды Писания, раскладка. |
| `BibleSearchTest.kt` | Поиск без регистра и «ё», английский, узбекские апострофы. |
| `DefaultBiblesTest.kt` | Установка встроенных модулей, удалённый не возвращается, свой модуль сохраняется, замена устаревшего. |
| `TestBibles.kt` | Помощник: тестовые модули MyBible (проверок нет). |
| `SongTextTest.kt` | Текст песни с подписями, деление по курсору, слияние и перестановка. |
| `SongFormatsTest.kt` | ChordPro, OpenLyrics, отказ от чужого XML и внешних сущностей. |
| `PptxTest.kt` | Чтение PPTX (лекция), отрисовка заданного файла, отказ не-презентации. |
| `PptTest.kt` | Чтение PPT, дамп записей, отказ зашифрованного (`resources/encrypted.ppt`) и мусора. |
| `DocxTest.kt` | DOCX: стили, списки, перенос на страницы, таблицы, картинки, отрисовка. |
| `DlnaTest.kt` | SSDP/XML, управление поддельным ТВ, раздача файлов с Range. |
| `HolyricsTest.kt` | Клиент Holyrics: поиск, вход по хешу, права, темы, плейлист и клавиши, Библия, переподключение, QR-ссылка, медиа из папки Holyrics. |
| `HolyricsBibleTest.kt` | Номера книг MyBible → id стихов Holyrics, выбор перевода. |
| `UpdatesTest.kt` | Сравнение версий, каналы и файлы, «Что нового», запасной `release.json`, SHA256SUMS, проверка загрузки, автоматический режим. |
| `CompatTest.kt` | Сопряжённые пульты из `testdata/compat/tokens-*.json` читаются после обновления. |
| `airplay/AirPlayTest.kt` | bplist, ключ, pair-verify, ключ сессии, расшифровка потока, битые пакеты, пароль, NTP. |
| `airplay/FairPlayTest.kt` | FairPlay совпадает с эталоном на C (setup, handshake, decryptKey, хэши). |
| `airplay/AirPlayServerTest.kt` | Сеанс зеркала от начала до конца, запрос пароля. |

Тестовые ресурсы: `core/src/test/resources/encrypted.ppt`, `screen-sample.h264`.

### `desktop` (`desktop/src/test/kotlin/app/deixis/desktop/`)

| Файл | Что проверяет |
|---|---|
| `SlideRendererTest.kt` | Текст в полях, «Чёрный»/«Очистить», логотип и экран ожидания, фото с полями, PDF (цвета, отсутствующая страница). |
| `AspectDesktopTest.kt` | Кадры зала и «Сцены» на всех форматах (`-Ddeixis.outputsPng`). |
| `OutputsDesktopTest.kt` | Мониторы: зеркало проектора, один/два монитора, выдернутый проектор, сохранение выбора, испорченный файл; браузер пульта на Windows; титры, «Сцена», зал. |
| `OverlayLayoutTest.kt` | Раскладка «Наложение»: плотность, титры внизу, сохранение по монитору. |
| `OverlayMotionDesktopTest.kt` | Появление слоя по кадрам. |
| `LogoPictureTest.kt`, `LogoStyleDesktopTest.kt` | Своя картинка «Логотипа»; четыре вида знака на разных экранах. |
| `PdfPagesTest.kt` | Страница из памяти, следующая заранее, изменённый файл перечитывается. |
| `OfficeConverterTest.kt` | Скрытые слайды, встроенный перевод POI, проверка числа страниц, документы требуют LibreOffice, стиль таблиц, порядок программ. |
| `LowLatencyDecoderTest.kt` | Декодер отдаёт кадр сразу, скорость Full HD, сборка FFmpeg под LGPL. |
| `ScreenCaptureTest.kt` | Съёмка экрана Xvfb в H.264 и обратно, окно программы; в том же файле — виртуальный экран Мака. |
| `XrandrTest.kt` | Разбор xrandr, выбор свободного выхода. |
| `DesktopUpdatesTest.kt` | Скрипты установки обновлений Windows и Mac (.dmg). |
| `CompatDesktopTest.kt` | Выбор мониторов из `testdata/compat/monitors-*.json` сохраняется. |
| `TrayIconTest.kt` | Значок трея на Linux непрозрачный, Δ видна. |
| `DesktopWindowTest.kt` (GUI) | Настоящие окна: выход Ctrl/⌘+Q, Esc/F11, роль после перезапуска, один монитор не запирает, окно выбора роли. |
| `PultWindowTest.kt` (GUI) | Окно пульта Compose управляет своим экраном, окно подтверждения. |
| `OverlayGuiTest.kt`, `OverlayWindowGuiTest.kt` (GUI) | Слой над слайдами и VLC; окно «Наложения» пропускает мышь. |
| `BackdropGuiTest.kt`, `VideoFitGuiTest.kt` (GUI, VLC, ffmpeg) | Видеофон под текстом; режимы вписывания видео на настоящем VLC. |

Тестовые ресурсы: `desktop/src/test/resources/colour.pdf`, `lecture16x9.ppt`, `lecture16x9.pptx`.

### `android/phone` (`android/phone/src/test/kotlin/app/deixis/phone/`)

Здесь же проверяется код `pult/`.

| Файл | Что проверяет |
|---|---|
| `StringsTest.kt` | У каждой строки `L` три непустых языка; в исходниках пульта (`android/phone`, `pult`, `desktop/.../phone`) нет русских литералов вне `Strings.kt` (кроме участков `// i18n:off … // i18n:on`). |
| `ContrastTest.kt` | Контраст цветов пульта по WCAG во всех темах и акцентах. |
| `StoreReadTest.kt` | Библиотека из новой версии читается по частям; обычный файл — строго; не-библиотека → null. |
| `CompatLibraryTest.kt` | Библиотеки из `testdata/compat/library-*.json` открываются целиком. |
| `ProfilesTest.kt` | Профили: первый — прежняя библиотека, новый пустой, удаление, восстановление списка. |
| `HolyricsImportTest.kt` | Повторный перенос из Holyrics обновляет, а не дублирует. |

`android/receiver` и `android/tv` своих unit-проверок не имеют.

## 5. Где искать…

| Задача | Файлы |
|---|---|
| Добавить строку перевода в пульт | `pult/.../phone/Strings.kt` (`object L`, три языка); проверка — `StringsTest.kt` |
| Строка в окнах программы для компьютера (Swing) | `desktop/.../desktop/Words.kt` |
| Цвета, размеры, анимации пульта (токены) | `pult/.../phone/Theme.kt` (`object T`, палитры `Light`/`Dark`/`Paper`/`Oled`, `P`, `accentColors`); источник — DESIGN.md |
| Токены вывода и окон компьютера | `desktop/OutputFrames.kt` (`OutputTokens`), `desktop/DesktopUpdates.kt` (`WindowTokens`), `desktop/RoleChooser.kt` (`ChooserTokens`) |
| Новый значок | `pult/.../phone/Theme.kt` (`icon`, контуры 24×24) |
| Новый жест | код в `pult/` (`App.kt` `swipes`, `Theme.kt` `combinedClick`/`press`, `ThemeDrag.kt`) + «Карта жестов» в DESIGN.md |
| Новое сообщение протокола | `core/.../core/Protocol.kt` (+ обработка в `Receiver.kt` и `Sender.kt`); проверка `ProtocolTest.kt` |
| Новый эндпоинт приёмника | `core/.../core/Receiver.kt` (`ReceiverServer`) или отдельный `Route.*Routes` по образцу `Outputs.kt`, `Capture.kt`, `DiagRoutes.kt` |
| Веб-пульт в браузере | `core/src/main/resources/web/remote.html` + маршруты `core/.../core/WebRemote.kt` |
| Страница зрителей по QR | `core/src/main/resources/web/view.html` + `core/.../core/Viewer.kt` |
| Сопряжение, PIN, токены | `core/.../core/Pairing.kt`, `PairingCode.kt`, `Peers.kt`; шифрование — `Tls.kt` |
| Поиск экранов в сети | `core/.../core/Discovery.kt` (mDNS); в пульте — `Controller.startDiscovery` |
| Логика пульта (что показано, переходы, подключение) | `pult/.../phone/Controller.kt` |
| Главный экран пульта | `pult/.../phone/ShowScreen.kt`, превью — `SlideView.kt` |
| Хранение библиотеки, сетлистов, настроек | `pult/.../phone/Model.kt` (`Store`, `StoreData`, `AppSettings`), `StoreRead.kt`, `Profiles.kt`, `Backups.kt` |
| Раскладка текста слайда | `core/.../core/TextLayout.kt`; рисование — `desktop/SlideRenderer.kt`, `android/receiver/SlideCanvas.kt`, `SlideBitmap.kt` |
| Раскладка монитора (зал, сцена, титры, хромакей, наложение) | `core/.../core/Outputs.kt` (`OutputLayout`) + `desktop/OutputFrames.kt`, `OutputWindow.kt`, `Monitors.kt` |
| Виртуальный монитор | `desktop/CaptureTargets.kt` (`LinuxVirtualScreen`, `VirtualScreen`), `WindowsVirtualScreen.kt`, `MacVirtualScreen.kt`, `windows-display/` |
| Трансляция экрана компьютера | `desktop/ScreenCapture.kt`, `CaptureTargets.kt` (`AppWindows`), `core/.../core/Capture.kt`, `MirrorOutput.kt` |
| Трансляция экрана телефона | `android/phone/ScreenShare.kt` → `core/Sender.kt` (`ScreenStream`) → `core/Stream.kt` → `desktop/ScreenView.kt` + `LowLatencyDecoder.kt` или `android/receiver/ScreenDecoder.kt`; звук — `core/Audio.kt`, `desktop/ScreenAudio.kt`, `android/receiver/ScreenSound.kt` |
| PDF-страницы | `desktop/PdfPages.kt`, `android/receiver/PdfPages.kt`; для браузеров — `desktop/SlideRenderer.kt` (`renderPdfPagePng`) |
| Перевод PPTX/PPT/DOCX в PDF | Android: `android/receiver/PptxPdf.kt` + `core/Pptx.kt`, `Ppt.kt`, `Cfb.kt`, `Docx.kt`, `DeckRender.kt`; компьютер: `desktop/OfficeConverter.kt`, `PdfEngines.kt`, `core/Convert.kt` |
| Видео | `desktop/VideoPlayer.kt`, `VideoPane.kt`, `BackgroundVideo.kt`; `android/receiver/VlcPlayer.kt`; геометрия — `core/VideoFit.kt` |
| Слой «Поверх показа» | `core/Overlays.kt`, `OverlaySamples.kt`; рисование — `desktop/OverlayPainter.kt`, `OverlayWindow.kt`, `android/receiver/OverlayLayer.kt`; пульт — `pult/.../OverlayUi.kt` |
| Отсчёт времени | `core/Countdown.kt`, `pult/.../CountdownUi.kt` |
| Библия | `core/Bible.kt`, `BibleSearch.kt`, `DefaultBibles.kt`; пульт — `pult/.../BibleScreen.kt`, `BibleDesk.kt`, `BiblePicker.kt`, `BibleLibrary.kt`; SQLite — `desktop/JdbcSql.kt`, `android/receiver/AndroidSql.kt` |
| Holyrics | `core/Holyrics*.kt`; пульт — `pult/.../Holyrics*.kt`; QR на компьютере — `desktop/HolyricsQrWindow.kt` |
| Песни, импорт ChordPro/OpenLyrics | `core/SongText.kt`, `SongFormats.kt`; редактор — `pult/.../SongEditor.kt` |
| Темы оформления | `pult/.../Themes.kt`, `ThemePanel.kt`, `ThemeDrag.kt`; модель — `TextStyle` в `core/Protocol.kt` |
| Пересылка на другие экраны | `core/Relay.kt` |
| DLNA | `core/Dlna.kt`, `android/phone/DlnaOutput.kt`, `desktop/DlnaCommand.kt` |
| AirPlay | `core/airplay/*`, `core/AirPlayRoutes.kt`; подключение — `desktop/ReceiverApp.kt`, `android/receiver/ReceiverRuntime.kt` |
| Обновления | `core/Updates.kt`; `desktop/DesktopUpdates.kt`; `android/receiver/ApkUpdates.kt`; пульт — `pult/.../UpdatesUi.kt`; имена файлов выпуска — `.github/workflows/release.yml` |
| Диагностика и журнал | `diag/Log.kt`, `Diagnostics.kt`; `core/DiagRoutes.kt`; площадки — `desktop/DesktopChecks.kt`, `android/receiver/AndroidChecks.kt`; пульт — `pult/.../DiagnosticsUi.kt` |
| Аргументы командной строки компьютера | `desktop/Main.kt`, `SendCommand.kt`, `DlnaCommand.kt` |
| Окно пульта на компьютере | `desktop/.../phone/PultWindow.kt`, `DesktopPlatform.kt`, `DesktopHost.kt`, `PlatformUi.kt`; запуск — `desktop/ReceiverApp.kt` (`openOperator`) |
| Платформенная функция пульта (выбор файла, «Назад», превью видео) | контракт — конец `pult/.../Platform.kt`; реализации — `android/phone/PlatformUi.kt`, `VideoPreview.kt` и `desktop/.../phone/PlatformUi.kt`, `DesktopVideo.kt` |
| Вывод по HDMI с телефона | `android/phone/HdmiOutput.kt` (`CastOutput`, `CastActivity`) |
| Работа телефона в фоне, уведомление | `android/phone/ShowService.kt`, `res/layout/notif_show*.xml` |
| Режим «Стать экраном» | `android/phone/ScreenActivity.kt` |
| Данные прошлых версий | `testdata/compat/`, `tools/compat-fixtures.sh`, проверки `Compat*Test.kt` |
| Правила R8 | `android/proguard-deixis.pro` |
| Демо-набор | `android/phone/src/main/assets/demo/` (общий с компьютером), `tools/demo/` (веб-демо) |

Подпапка `core/.../core/quiz/` — викторины ([QUIZ.md](QUIZ.md), [../quiz/FORMAT.md](../quiz/FORMAT.md)): `Quiz.kt` — модель файла; `QuizValidator.kt` — проверка недоверенного файла с путями ошибок; `QuizGrading.kt` — `grade()` и `QuizRun` (самостоятельное прохождение).

В корне `core/.../core/`: `QuizContent.kt` — вопрос на экране (новый `Content`, `quizContent()`), `QuizLayout.kt` — раскладка вопроса и `QuizColors`. Рисуют: `desktop/.../SlideRenderer.kt` (`drawQuiz`), `android/receiver/.../ReceiverScreen.kt` (`QuizSlide`).

`pult/.../phone/QuizSession.kt` — прохождение викторины с пульта (состояние, таймер, показ на экранах); `QuizUi.kt` — шторка «Урок» (открыть файл, играть, итог). Тест — `desktop/src/test/.../phone/QuizSessionTest.kt` (логика пульта без Compose проверяется в desktop).

`core/.../quiz/QuizGame.kt` — движок живой игры (игроки, ответы, очки за скорость, таблица); подключается к сети и экранам отдельным шагом.

Живая игра: `core/.../quiz/QuizPlay.kt` — `QuizHub` (центр игры на приёмнике: зал, игроки, таймер) и сообщения `PlayIn`/`PlayOut`; `core/.../QuizRoutes.kt` — `/play`, `/play/ws`, `/api/quiz/…`; `core/src/main/resources/web/play.html` — страница игрока.
