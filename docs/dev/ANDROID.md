# Android: экран, телефон, Deixis TV

Три модуля Gradle в `android/`:

| Модуль | Тип | `applicationId` / `namespace` | Что это |
|---|---|---|---|
| `android/receiver` | библиотека (`com.android.library`) | `app.deixis.receiver` | среда экрана: приёмник, отрисовка зала, видео, PDF, трансляция, обновления APK. `api(project(":core"))`, `api(libs.libvlc)`, Compose |
| `android/phone` | приложение | `app.deixis.phone` | **Deixis** (универсальная): пульт (общий код из `pult/`, см. [PULT.md](PULT.md)) и экран на любом Android |
| `android/tv` | приложение | `app.deixis.tv` | **Deixis TV**: только экран, без пульта, Holyrics и демонстрации экрана |

Общие параметры: `compileSdk = 36`, `minSdk = 26` (Android 8.0), `targetSdk = 35`, Java/Kotlin 17, Compose.

## 1. `android/receiver` — среда экрана

Пакет `app.deixis.receiver`, всё в `android/receiver/src/main/kotlin/app/deixis/receiver/`.

### 1.1. `ReceiverRuntime`

Приёмник на Android: ядро (`ReceiverCore` из `core`), сервер (`ReceiverServer`, Ktor), объявление в сети.

Конструктор: `ReceiverRuntime(context, name, platform, dataDir, port = ReceiverServer.DEFAULT_PORT /* 47820 */, loopbackOnly = false, screen = null)`.

| Кто создаёт | `platform` | `dataDir` | Особенности |
|---|---|---|---|
| `DeixisTvApp` | `android-tv` | `filesDir/receiver` | обычный экран в сети |
| `ScreenActivity` («Стать экраном», универсальный Deixis на ТВ) | `android-tv` на ТВ, иначе `android-screen` | `filesDir/screen` | то же |
| `CastOutput` (кабель, Миракаст) | `android-hdmi` | `filesDir/hdmi` | `port = 0`, `loopbackOnly = true` (слушает `127.0.0.1`, без TLS, без mDNS, без AirPlay), `screen` = размер второго дисплея |

Что делает `init`:
- `id` экрана — файл `<dataDir>/id` (UUID, создаётся один раз).
- TLS-ключ экрана — `TlsIdentity.loadOrCreate(<dataDir>/tls)`, если не `loopbackOnly`; ошибка не мешает старту (тогда без шифрованного входа).
- `ReceiverInfo(…, convert = true, documents = true)`: PPTX/PPT и DOCX экран переводит сам (`PptxPdf.converter`).
- Кэш файлов `FileStore(<dataDir>/files)`: не больше 10 ГБ и не больше половины свободного места, но не меньше 256 МБ.
- Сопряжение `Pairing(FileTokenStore(<dataDir>/tokens.json))`, PIN — `pin: StateFlow<String>`.
- `core.pageRenderer` — страницы PDF для веб-пульта (PNG из `PdfPages`), `core.bible` — модули Библии на экране (`AndroidSql`), `core.conversions` — перевод презентаций, `core.pageCounter = PdfPages::count`.
- AirPlay: `if (BuildConfig.AIRPLAY && !loopbackOnly) core.airPlay = AirPlayHost(...)`.
- Журнал в файл (`Log.toFile(dataDir)`), `core.diagChecks` = общие проверки + `AndroidChecks.device/wifi/screen/player`.

`start()` (на `Dispatchers.IO`):
- поднимает сервер; порт занят (на одном ТВ стоят Deixis TV и универсальный Deixis) — берёт любой свободный, адрес в QR и объявлении берётся из фактического порта (`actualPort`);
- кроме `loopbackOnly`: `WifiLock` (`FULL_LOW_LATENCY` с API 29, иначе `FULL_HIGH_PERF`), `refresh()` и слежение за сетью.

Сеть: `NetworkCallback` на Wi-Fi и Ethernet без требования интернета + опрос раз в 15 с (прошивки без событий, раздача Wi-Fi с самого экрана). `refresh()` выбирает адрес (`ConnectivityManager`, без VPN; запасной — перебор интерфейсов), обновляет `address`, переобъявляет mDNS (`Discovery.advertise`) с `MulticastLock` и перезапускает AirPlay на новом адресе. Работа с JmDNS — в своём потоке `deixis-net`.

`pairingUri()` — ссылка для QR (`PairingCode`: адрес, порт, PIN, id, имя, TLS-порт, отпечаток). `stop()` снимает всё.

### 1.2. `ReceiverScreen` (`ReceiverScreen.kt`)

`@Composable fun ReceiverScreen(runtime, player: VlcPlayer, showIdleInfo = true, overscan = false)` — весь зал. В зал попадает только содержимое; ошибки уходят на пульт (`core.reportError`), на экране остаётся чёрный или прежний кадр.

Слои снизу вверх:
1. чёрный фон;
2. видеофон темы (`BackdropVideo` → `BackdropPlayer`: свой беззвучный libVLC по кругу), смена — `Crossfade`;
3. видео (`VideoSurface`: раскладка по `core.videoRect`, поля — чёрные, цвет темы или размытая копия кадра `BlurBackdrop`);
4. трансляция экрана (`ScreenSurface`: `SurfaceView` + `ScreenDecoder`, звук — `playScreenSound`, только в режиме `NORMAL`); идёт и под «Чёрным», чтобы картинка вернулась сразу;
5. содержимое по режиму и типу: `connectCard` → `IdleScreen`; `BLACK`/`AWAY` → чёрный; `LOGO` → своя картинка (`ImageSlide`) или `LogoFull`; нет содержимого → `IdleScreen` (если `showIdleInfo`); `TextContent`/`ScriptureContent`/`CountdownContent` → `TextSlide`/`ScriptureSlide`/`CountdownSlide` (`clear` в режиме `CLEAR`); `ImageContent` → `ImageSlide`; `PdfContent` → `PdfSlide`;
6. `OverlayLayer` — над всем, в том числе над видео; «Чёрный» и заставка его убирают.

Видео: `LaunchedEffect(video.file.sha256)` запускает файл из кэша и раз в 500 мс отдаёт `player.state()` в `core.reportVideo`; команды пульта — из `core.videoCommands`; на «Чёрный»/«Логотип» видео встаёт на паузу (`hide`/`unhide`).

`IdleScreen(name, pin, address, pairingUri)` — экран ожидания и он же карточка «QR и код на экран» (`state.connectCard`, включается пультом через `Controller.connectCard`): знак ΔΕΙΞΙΣ (Didot), имя экрана, QR (`qrMatrix`, ZXing), PIN цифрами через пробел, адрес. Широкий экран — QR слева от PIN, вертикальный/квадратный — столбиком (`SIDE_BY_SIDE_MIN_ASPECT`). Размеры — доли кадра, без `dp`. На кабельном выводе (`CastActivity`, `CastPresentation`) `showIdleInfo = false`: QR и PIN телефона на проектор не выводятся.

Шрифты: `Didot` (`R.font.gfs_didot`), `NotoSans` (`R.font.noto_sans_semibold`). Вспомогательное: `decodeSampled` (фото уменьшается при чтении), `renderPdfPage` → `PdfPages.page`, `drawStyleBackground`, `drawStyledText`, `drawBox`.

### 1.3. `PdfPages` — страницы PDF

`object PdfPages`, общий для зала, превью страниц веб-пульта (`pageRenderer`) и DLNA:
- **документ остаётся открытым**: один `PdfRenderer` на последний файл (путь + `lastModified`); другой файл закрывает прежний. Рисование — под `lock` (у `PdfRenderer` открыта одна страница за раз);
- **кэш готовых страниц**: LRU `LinkedHashMap` по ключу `путь:время:страница:ШxВ`, до 40 МБ (~4 страницы Full HD); только что положенная не вытесняется;
- **следующая заранее**: `page()` рисует запрошенную и ставит `ahead(page + 1)` в поток `pdf-ahead` (низкий приоритет); `pending` не даёт ставить одну страницу дважды; ошибки заранее молча пропускаются;
- **прежняя страница остаётся, пока рисуется новая**: `PdfSlide` держит `shown` и меняет его только на готовый `Bitmap`, поэтому при листании зал не мигает чёрным. Если страница уже в кэше — показывается сразу и заказывается следующая;
- **bitmap общие — никогда не вызывать `recycle()`**: одна и та же страница может одновременно быть в зале, в PNG для веб-пульта и в кадре DLNA. Вытесненная из кэша страница освобождается сборщиком мусора, когда её перестают показывать. Комментарии об этом стоят в `ReceiverRuntime` (`pageRenderer`) и `DlnaOutput.pdfPage`;
- в зале страница рисуется не больше 1920 по длинной стороне (`PDF_MAX_SIDE`), растягивает её уже экран.

### 1.4. `PptxPdf` — перевод презентаций и документов

Встроенный перевод PPTX/PPSX/POTX/PPTM/PPSM, старых PPT/PPS/POT и DOCX в PDF на самом устройстве (телефон или ТВ без компьютера). Раскладку делает `core` (`Pptx`, `Ppt`, `Docx`, `DocxLayout`, `DeckRender`), рисует `AndroidPainter` на `Canvas` в `android.graphics.pdf.PdfDocument`. Шрифты Carlito, Caladea, Liberation Sans — с метриками Calibri, Cambria, Arial. `convert(context, input, output)` возвращает список предупреждений, `warning(list)` — строку для оператора; `converter(context)` — `DocConverter` для `core.conversions`. ODP и KEY — только компьютер (`unsupported(ext)`). Пульт телефона зовёт его через `AndroidPlatform.convertToPdf`.

### 1.5. `SlideCanvas`, `SlideBitmap`, `LogoMarkView`

- `TextSlideCanvas(text, ref, style, clear, overscan, bg, drawBackground)` и `ScriptureSlideCanvas(...)` — текст по раскладке ядра (`layoutSlideText`, `layoutScripture`). Один код для зала и превью пульта на телефоне (`pult` вызывает их через `PlatformUi.kt`), поэтому превью совпадает с залом.
- `renderSlideBitmap(...)` — тот же слайд картинкой (для DLNA-телевизоров), «Чёрный», «Логотип», своя картинка логотипа (`drawFit`).
- `LogoFull(style, color)`, `drawLogoMark(...)` — заставка «Логотип» по раскладке `LogoMark` из ядра.

### 1.6. Видео: `VlcPlayer`

libVLC (`org.videolan.android:libvlc-all`): почти любой формат, аппаратное декодирование с откатом на программное. Параметры: `--no-sub-autodetect-file`, `--audio-time-stretch`, `--deinterlace=-1 --deinterlace-mode=yadif`, `--no-osd`, `--no-video-title-show` (никаких надписей VLC в зал).
- `play(file, name, loop, startMs)` — зал; `play(fd, name, startMs)` — предпросмотр на телефоне по дескриптору `content://` без копии (`VideoPreview.kt`).
- `apply(VideoCommand)`: `PLAY`, `PAUSE`, `SEEK`, `VOLUME` (0–200), `LOOP`; `hide()`/`unhide()`; `state()` → `VideoState`.
- `checkStuck()`: файл без воспроизводимых дорожек через 5 с даёт ошибку на пульт (сам VLC об этом не сообщает).
- `aspect` — пропорции с учётом поворота (вертикальное видео с телефона), `setStretch`, `attach/detach(VLCVideoLayout)`, `release()`.
- `BackdropPlayer` — второй экземпляр для видеофона: без звука, `--input-repeat=65535`.

### 1.7. Трансляция экрана: `ScreenDecoder`, `ScreenSound`

- `ScreenDecoder(hub: StreamHub, surface, onError)` — подписка на `core.stream`; ждёт ключевой кадр, по SPS/PPS (`csd-0`, `csd-1`) создаёт аппаратный `MediaCodec` H.264 (`KEY_LOW_LATENCY` с API 30, `KEY_PRIORITY = 0`) и рисует на поверхность сразу по готовности (поток `deixis-screen`, без ожидания меток времени). Новые SPS/PPS — новый декодер; кадр не принят — ждать следующий ключевой. При сбое на экране остаётся последний кадр. `stop()` ждёт освобождения декодера, прежде чем поверхность уничтожат.
- `playScreenSound(chunks)` — звук трансляции через `AudioTrack` в режиме малой задержки, темп — `AudioPacer` из ядра (запас ~40 мс, сверх 100 мс выбрасывается). Нет звука на устройстве — картинка идёт без него.

### 1.8. `OverlayLayer`

Слой «Поверх показа»: логотип, плашка с именем, бегущая строка, часы. Раскладка и движение — из ядра (`OverlayMotion`), та же, что на компьютере и в браузере. Перерисовка: каждый кадр, пока что-то движется или бежит строка; дважды в секунду для часов; иначе не перерисовывается. Видимость — `overlaysVisible(mode)`.

### 1.9. `Away.kt`

- `ComponentActivity.followAway(runtime)` — режим `AWAY` («Уйти с экранов»): `moveTaskToBack(true)`, на экране то, что под Deixis. Возврат: `startActivity` с `REORDER_TO_FRONT`; если через 2,5 с окно не впереди (Android не пускает из фона), пульт получает подсказку через `reportError`.
- `ComponentActivity.guardBackOnHall()` — «Назад» на экране зала срабатывает только при втором нажатии за 2 с; подсказка в зал не выводится.

### 1.10. `ApkUpdates` и приёмники системы

- `ApkUpdates.updater(context, target, scope, beforeInstall)` — `Updater` из ядра (выпуски GitHub, выбор APK по `Build.SUPPORTED_ABIS`), файлы в `filesDir/updates`.
- `verify(context, apk)`: тот же пакет, версия новее, тот же ключ подписи; иначе установка не начинается. `install` — сеанс `PackageInstaller`; ответ — `InstallResultReceiver` (подтверждение у оператора или причина в `onResult`).
- `fromPlay` (установщик `com.android.vending` — обновляет магазин), `silent` (API 31+ и прошлую версию ставил сам Deixis — без вопроса), `canInstall`, `permissionIntent`.
- `PackageReplacedReceiver` (`MY_PACKAGE_REPLACED`): если перед обновлением устройство было экраном (`ScreenRole`, файл `filesDir/screen-active`), открывает его снова.
- `ScreenUpdateHost` — обновление экрана с пульта (`/api/update`). Установка открывает окно системы поверх зала, поэтому при показе отказ («Сначала уберите показ…»). Режим «Автоматически»: раз в минуту, если обновление готово, экран простаивает 10 минут и `silent` — ставит молча.

### 1.11. `AndroidChecks`, `AndroidSql`

- `AndroidChecks` — проверки «Диагностики»: `wifi`, `device`, `player` (libVLC), `screen`, `notifications`, `battery`; названия на трёх языках через `Check(..., { it.t(ru, uz, en) })` из модуля `diag`.
- `AndroidSql(file): SqlSource` — SQLite Android только на чтение для модулей MyBible.

### 1.12. Известное о строках экрана

Строки экрана не идут через `L`: в `ReceiverScreen.kt` по-русски написаны тексты `IdleScreen` («PIN для подключения», «Наведите камеру телефона…») и ошибки для пульта («Нет файла …», «Не удалось открыть PDF»); то же в `VlcPlayer`, `Away.kt`, `ApkUpdates.kt`, `PptxPdf.kt`, `PdfPages.kt` и в `StartError` Deixis TV. `StringsTest` эти папки не проверяет. По правилу CLAUDE.md («все строки — через систему переводов») это долг. Переводы есть только у проверок `AndroidChecks` (через `diag`).

## 2. `android/phone` — Deixis (универсальная)

### 2.1. `PhoneApp` и `MainActivity` (`MainActivity.kt`)

`PhoneApp : Application` — всё долгоживущее, чтобы поворот или пересоздание окна не рвали связь и передачу:
- лениво: `platform` (`AndroidPlatform`), `controller` (`Controller`), `holyrics` (`HolyricsSource`), `host` (`PhoneHost`), `cast` (`CastOutput`), `updater` (`ApkUpdates.updater(..., UpdateTarget.PHONE, ...)`; перед установкой — `controller.backupNow("before-update-<версия>")`);
- `onCreate`: журнал в файл, перехват необработанных ошибок, **прогрев контроллера в фоне** — `appScope.launch { runCatching { controller } }` на `Dispatchers.Default`: библиотека читается, пока создаётся окно; окно, если успеет раньше, дождётся того же `lazy`;
- падение пишется в `filesDir/last-crash.txt`; при следующем запуске `MainActivity` показывает `CrashScreen` с кнопками «Отправить отчёт» (`Feedback.crash`) и «Продолжить».

`MainActivity`:
- на ТВ или устройстве без сенсорного экрана (`ScreenActivity.isTelevision`) сразу открывает `ScreenActivity` и закрывается;
- иначе — `DeixisPhone(controller, holyrics, ui)` под `LocalHost provides app.host`, плюс `CastPresentation` (запасной вывод на второй экран, если своё окно там не открылось);
- ссылки из QR: `deixis://…` → `controller.addManual`, `deixis-holyrics://…` → `holyrics.connect`;
- `dispatchKeyEvent` → `clickerKey`: Bluetooth-кликер и педаль (`PAGE_DOWN/UP`, `MEDIA_NEXT/PREVIOUS`; стрелки, пробел и `B` — только когда нет открытой шторки), кнопки громкости — если включены в настройках;
- `onStart`: `remoteVisible = true`, поиск экранов, `keepAlive`, `cast.start()`; `onStop`: `StoreWriter.flush()` в отдельном потоке, остановка поиска;
- отладочная сборка: `am start --ef deixis.test.ratio 0.5625` — превью в пропорциях вертикального зала без экрана.

### 2.2. `ScreenActivity` — «Стать экраном»

Тот же приёмник, что в Deixis TV, внутри универсального приложения. Открывается плиткой «Стать экраном» (`PhoneHost.becomeScreen`), а на ТВ — сразу (`LEANBACK_LAUNCHER`). `FLAG_KEEP_SCREEN_ON`, системные панели скрыты (`hideSystemBars`), `sensorLandscape`. Приёмник — синглтон `ScreenRuntime` (живёт, пока открыт режим экрана; сбой запуска пишется в журнал и не роняет приложение); на устройствах не из Google Play экран обновляется с пульта (`ScreenUpdateHost`). Выход (`isFinishing`) останавливает приёмник и снимает `ScreenRole`. Сеть открывается только здесь — по действию человека.

### 2.3. `ShowService` — связь в фоне

Foreground-служба `connectedDevice`: пока подключён хотя бы один экран, держит `WifiLock` (`FULL_HIGH_PERF`) и `PARTIAL_WAKE_LOCK`, иначе Android через пару минут в фоне усыпляет сеть приложения. Уведомление (свои `RemoteViews` `notif_show`/`notif_show_big`) повторяет «Сейчас»: экран(ы), что в зале, режим; кнопки «Назад», «Дальше», «Чёрный», «Логотип», «Уйти» работают и на экране блокировки (`VISIBILITY_PUBLIC`). Нет экранов — `stopSelf()`. Из фона Android не даёт её начать: тогда она начнётся при следующем открытии пульта (`ShowService.start` в `runCatching`).

### 2.4. `ScreenShare.kt` — демонстрация экрана (MediaProjection)

- `ScreenShareService` — foreground `mediaProjection|microphone`; уведомление поднимается до получения проекции (требование Android 14); повторный запуск сначала гасит прежнюю трансляцию.
- `ScreenCapture` — `VirtualDisplay` прямо во входную поверхность кодировщика; размер — экран, уменьшенный до 1920×1080, кратно 16; поворот пересоздаёт кодировщик.
- `Encoder` — `MediaCodec` H.264 Baseline, уровень 4.1, `KEY_LATENCY = 1`, ключевой кадр раз в секунду, 30 к/с, битрейт `w*h*3` в пределах 1,5–8 Мбит/с, `REPEAT_PREVIOUS_FRAME_AFTER = 100 мс`; ключевой кадр по запросу — не чаще трёх раз в секунду. Кадры — в `ScreenStream` главного экрана (`Controller.openScreenStream`).
- `SoundCapture` (API 29+, при разрешении `RECORD_AUDIO` и включённом звуке) — `AudioPlaybackCaptureConfiguration`, то, что играют приложения, без микрофона, куски по 10 мс в `AudioSender`.
- Остановка кодировщика (его забрали, сбой медиасервера) честно заканчивает трансляцию: в зале чёрный, на телефоне причина.

### 2.5. `HdmiOutput.kt` — кабель и второй дисплей

- `CastOutput` слушает `DisplayManager` (`DISPLAY_CATEGORY_PRESENTATION`). Есть второй дисплей — создаёт `ReceiverRuntime` (`loopbackOnly`, порт 0, размер дисплея) и сам сопрягает пульт с ним (`pairing.pair(pin, L.thisPhone)`, токен сохраняется как `cable`). Для пульта это обычный экран: `Target("cable", …, "127.0.0.1", port, Kind.CABLE)` или `Kind.MIRACAST` (определяется скрытым `Display.getType() == 3`, иначе по имени дисплея).
- Вывод — своё окно `CastActivity` на втором дисплее (`ActivityOptions.setLaunchDisplayId`, `singleInstance`, своя `taskAffinity`, `FLAG_NOT_FOCUSABLE` — кликер остаётся у пульта). Не открылось за 2 с — `fallback`, и вывод идёт через `CastPresentation` из открытого пульта.
- Приёмник один на всё приложение: дисплей отключили — окно закрывается, приёмник остаётся, вернули — в зале то же. Режим `AWAY` закрывает окно на втором дисплее.
- Miracast на устройствах не проверен (ARCHITECTURE.md).

### 2.6. `DlnaOutput` — обычный ТВ по DLNA

`DlnaOut` для пульта: поиск SSDP раз в 20 с, пока открыт пульт; `DlnaFileServer` на телефоне раздаёт файлы; фото и видео ТВ забирает напрямую, слайды, страницы PDF (кадр 1920×1080 из `PdfPages`), «Чёрный» и «Логотип» уходят JPEG (`renderSlideBitmap`). Демонстрация экрана здесь не работает.

### 2.7. Прочее

- `AndroidPlatform`, `PhoneHost`, `PlatformUi.kt` — реализация договора пульта, см. [PULT.md](PULT.md).
- `VideoPreview.kt` — предпросмотр видео на телефоне (`VlcPlayer` по дескриптору), «В зал» с того же места, «С начала».
- `Feedback.kt` — «Сообщить о проблеме», «Есть идея», отчёт о падении: текст с версией и устройством через «Поделиться». Сам Deixis ничего не отправляет.

## 3. `android/tv` — Deixis TV

- `DeixisTvApp : Application` — создаёт `ReceiverRuntime(this, имя, "android-tv", filesDir/receiver)` при старте процесса (поворот и пауза окна не рвут связь), обновления с пульта (`ScreenUpdateHost`, кроме установки из Google Play), запуск сервера в фоне. Ошибка создания или запуска → `startError`. Падение пишется в `last-crash.txt`; при следующем запуске переносится в `previous-crash.txt`, а экран запускается как обычно.
- `MainActivity` — `ReceiverScreen(runtime, vlc, overscan = true)`, `followAway`, `guardBackOnHall`, `ScreenRole.mark`, без системных панелей. Если приёмник не создан или libVLC не загрузился — экран `StartError` с причиной (чтобы её можно было сфотографировать).

## 4. Сборка

### 4.1. Версия, подпись, APK

- Версия — из переменной `DEIXIS_VERSION` (метка `v1.2.3` → `1.2.3`), без неё `0.1.0`. `versionCode = major*10000 + minor*100 + patch` (не меньше 1).
- Подпись выпуска — только из окружения (секреты CI), ключа в репозитории нет:

| Переменная | Значение |
|---|---|
| `DEIXIS_KEYSTORE_FILE` | путь к файлу ключа; нет файла — выпуск подписывается отладочным ключом (для эмулятора) |
| `DEIXIS_KEYSTORE_PASSWORD` | пароль хранилища |
| `DEIXIS_KEY_ALIAS` | имя ключа (`release.yml` ожидает `deixis`) |
| `DEIXIS_KEY_PASSWORD` | пароль ключа |

  Схемы подписи v1 и v2 вместе: установщики части проекторов и приставок проверяют только v1. `release.yml` проверяет ключ и пароль `keytool` до сборки.
- `splits.abi`: отдельные APK `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (в 3–4 раза меньше) и `universal`. `release.yml` переименовывает их в `Deixis-<версия>-<abi>.apk` и `Deixis-TV-<версия>-<abi>.apk`, `universal` → `all`.
- `packaging.resources.excludes`: `META-INF/INDEX.LIST`, `DEPENDENCIES`, `*.kotlin_module`, `versions/9/OSGI-INF/MANIFEST.MF` (дубли из JVM-библиотек).
- `android/phone`: `testOptions.unitTests` получают `deixis.compat = testdata/compat` (чтение данных прошлых версий).

### 4.2. R8 и `android/proguard-deixis.pro`

В обоих приложениях `release { isMinifyEnabled = true; isShrinkResources = true }` с `proguard-android-optimize.txt` и `android/proguard-deixis.pro`. Выигрыш — оптимизированные Compose, AndroidX и stdlib Kotlin, меньший APK. Риск — тихо сломанная сеть (отражение, JNI, загрузка по имени), поэтому правила осторожные (DECISIONS.md, 2026-11-11):

| Группа | Правила | Зачем |
|---|---|---|
| Имена | `-dontobfuscate` | имена классов не перемешиваются: журнал ошибок, `last-crash.txt` и «Диагностика» читаются без файла сопоставления |
| Свой код | `-keep class app.deixis.** { *; }` | протокол на kotlinx.serialization, AirPlay, приёмник, пульт — целиком. Сжимаются и оптимизируются только библиотеки |
| Сеть | `-keep class io.ktor.**`, `javax.jmdns.**`, `org.bouncycastle.**` | движки и плагины Ktor находятся по имени; JmDNS; BouncyCastle (Ed25519/X25519 для AirPlay, TLS-ключ экрана) — провайдеры по имени |
| Видео | `-keep class org.videolan.** { *; }` | классы libVLC вызываются из нативного кода (JNI) |
| QR | `-keep class com.google.zxing.** { *; }` | ZXing целиком (осторожность; конкретная причина в файле не указана) |
| Сериализация | `-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod`; `-keepclassmembers class **$$serializer { *; }`; `-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }` | сериализаторы ищутся через `Companion.serializer()` и сгенерированные `$$serializer` |
| Предупреждения | `-dontwarn java.lang.management.**`, `javax.naming.**`, `org.slf4j.**`, `io.ktor.**`, `org.bouncycastle.**`, `javax.jmdns.**`, `reactor.blockhound.**`, `java.lang.ProcessHandle`, `java.lang.ProcessHandle$Info` | классы JVM, которых нет на Android и которые библиотеки трогают только на компьютере |

Проверка, что R8 ничего не сломал, — `tools/android-smoke.sh` (раздел 6) на APK выпуска.

### 4.3. `BuildConfig`

`buildConfig = true` включён только в `android/receiver`. Единственный флаг:

```kotlin
buildConfigField("boolean", "AIRPLAY", (providers.gradleProperty("deixis.store").orNull != "play").toString())
```

`./gradlew -Pdeixis.store=play …` → `BuildConfig.AIRPLAY = false`: `ReceiverRuntime` не создаёт `AirPlayHost` (единственное место, где флаг читается). Без свойства (GitHub, F-Droid) — `true`. Других различий у сборки для Google Play в Gradle нет (нет flavors).

Внимание: ARCHITECTURE.md говорит, что при `AIRPLAY = false` «R8 выбрасывает код». Но код AirPlay лежит в `core` в пакете `app.deixis.core.airplay`, а правило `-keep class app.deixis.** { *; }` сохраняет его целиком — классы остаются в APK, просто не запускаются. Если для Google Play код UxPlay не должен входить в APK вовсе (CLAUDE.md, NOTICE), нужно исключение из правила `-keep` или отдельный набор исходников. Ни один workflow в `.github/workflows` не собирает с `-Pdeixis.store=play`, так что сборка для Google Play в CI не проверяется.

Приложения отличают установку из Google Play во время работы: `ApkUpdates.fromPlay` (установщик `com.android.vending`) — тогда обновления только через магазин.

## 5. Манифесты

### `android/receiver` (вливается в оба приложения)
- Разрешения: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS), `WAKE_LOCK` (`WifiLock`), `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (обновление APK).
- `InstallResultReceiver`, `PackageReplacedReceiver` (`MY_PACKAGE_REPLACED`), оба `exported="false"`.

### `android/phone`
- Разрешения: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` (звук приложений при трансляции; микрофон не включается), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (`ShowService`), `CHANGE_NETWORK_STATE`, `WAKE_LOCK`.
- `uses-feature` `leanback` и `touchscreen` — `required="false"`: ставится и на телефон, и на ТВ, проектор, доску.
- `application`: `PhoneApp`, `allowBackup="false"`, `dataExtractionRules` (`res/xml/data_extraction_rules.xml`: в облачную копию — ничего; при переносе на новое устройство не переносятся `tokens.xml`, папки `receiver` и `screen` — ключи и токены сопряжения), `banner`, `usesCleartextTraffic="true"` (пульт ходит к экранам по HTTP в локальной сети).
- `MainActivity`: `LAUNCHER`, `singleTop`, `adjustResize`; `VIEW` для схем `deixis` и `deixis-holyrics` (QR, открытые камерой).
- `ScreenActivity`: `LEANBACK_LAUNCHER`, `singleTask`, `sensorLandscape`, тема `Theme.Deixis.Screen`, `exported="true"`.
- `CastActivity`: `singleInstance`, `taskAffinity="app.deixis.phone.cast"`, `excludeFromRecents`, `exported="false"`.
- Службы: `ScreenShareService` (`mediaProjection|microphone`), `ShowService` (`connectedDevice`).
- `FileProvider` с authority `app.deixis.phone.files`, `res/xml/shared_files.xml`: только `cache/share/` (отчёт диагностики для «Поделиться»).

### `android/tv`
- `WAKE_LOCK` (плюс разрешения из `receiver`), `leanback`/`touchscreen` не обязательны, `DeixisTvApp`, `allowBackup="false"`, `dataExtractionRules`, `banner`.
- `MainActivity`: `LAUNCHER` и `LEANBACK_LAUNCHER`, `singleTask`, `sensorLandscape`. `usesCleartextTraffic` не задан.

## 6. Проверка на эмуляторе: `tools/android-smoke.sh`

Запускается в `.github/workflows/smoke.yml` (Android 8, 11, 13; собирает `:android:phone:assembleRelease :android:tv:assembleRelease :android:tv:assembleDebug :desktop:installDist`, передаёт `DEIXIS_CLI`) и в `release.yml` (Android 8 и 13, `APK_DIR=apk` — ровно те APK, что уйдут в выпуск; без зелёной проверки выпуск не выходит).

Что делает:
1. **Какие APK.** `APK_DIR` задан — `Deixis-TV-*-all.apk` и `Deixis-[0-9]*-all.apk` оттуда; иначе `android/<app>/build/outputs/apk/release/*universal*.apk`.
2. **Для `tv:MainActivity`, `phone:MainActivity`, `phone:ScreenActivity`:** `adb install -r`, `am start -W`, 20 с, снимок `smoke/<app>-<activity>.png`, процесс жив (`pidof`). Потом журнал `smoke/<app>-<activity>-logcat.txt`, буфер `crash` по пакету (есть падение — ошибка), `force-stop`.
3. **Обход пульта (`walk_phone`).** Касания по тексту, как у человека: «Библия», «Библиотека», «Сетлисты», «Устройства», «Показ», «Настройки»; `uiautomator dump` → координаты по `text`/`content-desc`; названия на любом языке через `tools/ui_names.py` (переводы из `Strings.kt`). Снимок после каждого шага, затем «Назад»; после каждого шага пакет должен быть жив.
4. **Сеть ТВ (`net_check`)** — на APK выпуска после R8:
   - `adb forward tcp:47820` → `curl http://127.0.0.1:47820/info` (сервер отвечает);
   - из ответа — `tlsPort`, `adb forward` на него → `curl -k https://127.0.0.1:<tlsPort>/info` должен вернуть `"id"` (шифрованный вход); при отказе — подробный `curl -v` и строки журнала про SSL/TLS/Conscrypt;
   - если задан `DEIXIS_CLI` (`desktop/build/install/deixis/bin/deixis`): PIN читается из `uiautomator dump`, затем `deixis send --to 127.0.0.1:47820 --pin <PIN> --text "Проверка связи"` и снимок `smoke/tv-shown.png`.
5. **Сравнение без R8.** Только без `APK_DIR` и если есть отладочный APK ТВ: удалить выпуск, поставить `android/tv/build/outputs/apk/debug/*universal*.apk`, повторить проверку шифрованного входа, поставить выпуск обратно. Результат только печатается и на итог не влияет — помогает понять, виноват ли R8.

Известные слабые места сценария:
- PIN ищется шаблоном `text="[0-9][0-9][0-9][0-9]"`, а `IdleScreen` выводит его цифрами через пробел (`pin.toCharArray().joinToString(" ")`, «1 2 3 4»). Похоже, шаг с `DEIXIS_CLI` всегда заканчивается «PIN на экране не найден» и молча не проверяет показ (статус при этом не портится). На эмуляторе не проверено.
- В сравнении без R8 при пустом `tlsPort` запрос идёт на `https://127.0.0.1:/info`, и печатается «тоже НЕ отвечает» — вывод вводит в заблуждение.
- После сравнения выпуск ставится заново, но не запускается; журнал `tv-MainActivity-logcat.txt` включает запуск отладочной сборки.

Итог — код выхода: `1`, если хоть одна проверка не прошла. Всё собранное — в `smoke/` (артефакт workflow).

Запуск вручную (эмулятор или устройство в `adb`):

```sh
./gradlew :android:phone:assembleRelease :android:tv:assembleRelease :android:tv:assembleDebug :desktop:installDist
DEIXIS_CLI=$PWD/desktop/build/install/deixis/bin/deixis ./tools/android-smoke.sh
```
