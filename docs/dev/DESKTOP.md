# Deixis для компьютера: как устроено внутри

Документ для разработчика модуля `desktop/` (Windows, macOS, Linux). Общая архитектура, протокол и форматы — в [ARCHITECTURE.md](../../ARCHITECTURE.md), экраны и роли — в [SCREENS.md](../../SCREENS.md), решения — в [DECISIONS.md](../../DECISIONS.md), дизайн-код — в [DESIGN.md](../../DESIGN.md). Здесь — только то, как работает программа для компьютера, по коду на момент написания.

Что где лежит:

| Путь | Что там |
|---|---|
| `desktop/src/main/kotlin/app/deixis/desktop/` | приёмник: окна вывода (Swing), видео (vlcj), PDF (PDFBox), съёмка экрана (FFmpeg bytedeco), виртуальный монитор, трей, команды `send`/`list`/`dlna` |
| `desktop/src/main/kotlin/app/deixis/phone/` | платформа для общего пульта: `DesktopPlatform`, `DesktopHost`, `PultWindow` (Compose Multiplatform), выбор файлов, предпросмотр видео |
| `pult/src/main/kotlin/` | общий пульт телефона и компьютера; `desktop/build.gradle.kts` подключает его исходники через `kotlin.sourceSets["main"].kotlin.srcDir("../pult/src/main/kotlin")` |
| `core/`, `diag/` | ядро (`ReceiverCore`, `ReceiverServer`, `ScreenRelay`, `AirPlayHost`, `Updater` …) и журнал/диагностика (`Log`, `Checks`) |
| `windows-display/` | драйвер виртуального монитора Windows «Deixis Display» (IddCx) и помощник `deixis-display.exe` |
| `desktop/packaging/` | значки установщиков: `deixis.ico`, `deixis.icns`, `deixis.png` |

Приёмник — Swing (DECISIONS 2026-10-22: «Приёмник на компьютере — Swing и vlcj»); Compose используется только для окна пульта.

---

## 1. Запуск

### 1.1. `Main.kt`: команды и ключи

`main()` берёт первый аргумент как команду, если он не начинается с `--`; иначе команда — `receive`. Ключи разбирает `parseOptions`: `--ключ значение`, `--флаг` (значение `""`), позиционный аргумент — под ключом `file`.

| Команда | Что делает | Ключи |
|---|---|---|
| `receive` (по умолчанию) | `ReceiverApp(ReceiverOptions(...)).run()` | `--name`, `--port` (по умолчанию `ReceiverServer.DEFAULT_PORT`), `--screen N`, `--windowed`, `--operator` / `--no-operator`, `--role remote\|screen\|both`; не описаны в `USAGE`, но читаются: `--data ПАПКА` (папка данных, ими пользуются проверки CI), `--snapshots ПАПКА` (кадры окон в PNG) |
| `send` | `SendCommand(opts, ~/.deixis/sender-tokens.json).run()` — показ файла, текста, Писания, трансляции записи H.264, слоя, режима, команд видео на чужой приёмник | см. `USAGE` в `Main.kt` |
| `list` | `listReceivers(seconds)` — поиск приёмников по mDNS | `--seconds N` (5) |
| `dlna` | `dlnaCommand(opts)` (`DlnaCommand.kt`) — обычный ТВ с DLNA: без файла — список, с файлом — показ фото/видео через `DlnaFileServer` + `DlnaController` | `--to ИМЯ`, `--watch N` (5), `--seconds N` (3, в `USAGE` не описан) |
| `help`, `-h` | печатает `USAGE` | — |

`ReceiverOptions.askRole` = true, только если не заданы `--screen`, `--windowed`, `--operator`, `--no-operator`: эти ключи «отвечают сами», окно выбора роли не показывается.

Папка данных по умолчанию — `~/.deixis/receiver` (`File(System.getProperty("user.home"), ".deixis")` + `receiver`).

### 1.2. `ReceiverApp`: конструктор (`init`)

По порядку:

1. `dataDir.mkdirs()`.
2. Если есть экран (`!GraphicsEnvironment.isHeadless()`): `Log.captureConsole()` — консоль и ошибки идут в журнал в памяти **сразу**; первая строка — `Deixis $APP_VERSION · Diagnostics.system()`. В файл журнал ещё **не** пишется (см. шаг 2 в `run()`).
3. `id` — UUID экрана, создаётся один раз.
4. Имя — `--name`, иначе имя хоста, иначе «Компьютер».
5. `hallSize()` — размер первого монитора с залом (по `monitors.json`), без мониторов — 1920×1080; идёт в `ReceiverInfo` как размер экрана (пульт строит превью в этих пропорциях).
6. `findPdfEngines()` — PowerPoint/Word/Excel, LibreOffice, Keynote (см. §7). `ReceiverInfo(..., holyrics = true, convert = true, documents = ...)`; платформа — `desktop-<os.name>`.
7. `Pairing(FileTokenStore(tokens.json))`, `ReceiverCore(info, FileStore(files/), pairing, loadViewKey(view-key))`.
8. Подключения ядра: `pageRenderer` (`renderPdfPagePng` → `PdfPages`), `holyrics = HolyricsBridge`, `bible = BibleStore(bible/) { JdbcSql(it) }`, `pageCounter = PdfPages.count`, `conversions = Conversions(core, OfficeConverter, convert/)`.
9. `monitorStore.load()`, `ScreenRelay(core, FileRelayStore(screens.json), …)`, `core.outputs = DesktopOutputs()`.
10. `DesktopUpdates` → `core.updates`.
11. `ReceiverServer(core, port, tls = TlsIdentity.loadOrCreate(tls/, имя))` (ошибка TLS — сервер без TLS).
12. `AirPlayHost(core, dataDir)`, `core.capture = DesktopCapture()`, `core.diagChecks = Checks.common + DesktopChecks.all(...)`.

### 1.3. `ReceiverApp.run()`

1. `AppIcon.applyToDock()`.
2. `server.start()`. Если не вышло (порт занят) — `activateRunning()`: `POST http://127.0.0.1:<port>/api/activate` с `Authorization: Bearer <operator-token>` (токен — из файла `operator-token`, без прокси, таймауты 2/3 с). Ответ 204 — работающая копия вернула зал на экраны и открыла пульт (`core.onActivate` = `core.returnToScreens()` + `openOperator()`), эта копия выходит с кодом 0. Сервер принимает `/api/activate` только с loopback, без TLS и с действующим токеном (`core/.../Receiver.kt`). Не ответили — исключение запуска.
3. Только теперь `Log.toFile(dataDir)`: `log.txt`, прежний — в `log.old.txt`. Так второй запуск не перетирает журнал первого.
4. `operatorToken()` — токен «Этот компьютер» выдаётся сразу (через `pairing.pair(pin, "Этот компьютер")`) и пишется в `operator-token`, чтобы повторный запуск мог активировать программу даже при роли «Экран», где пульт не открывали.
5. `core.pairingUri`, `core.onActivate`, `core.onOperatorClosed` (последний — только для пульта в браузере: закрытие страницы → `quit()`, если роль не «Экран»).
6. **mDNS — в фоне** (`Dispatchers.IO`): `Discovery.advertise(...)`. JmDNS может ждать сеть десятки секунд (так было на Маке), а зал и пульт должны открыться сразу. Если за это время начался выход (`exiting`), объявление сразу закрывается.
7. `airPlay.start()` в фоне (слушает, только если включён флагом `airplay-on`).
8. `Discovery.browse(...)` → `relay.found(...)` — экраны Deixis в сети для пересылки.
9. Роль: `--role` → `RoleStore(role).load()` → окно `RoleChooser` (если `askRole` и есть экран) → `Role.SCREEN`. Закрыли окно без выбора — `quit()`. Выбранное в окне сохраняется в файл `role` (если роль не пришла из `--role`).
10. В потоке окон (`invokeAndWait`): `JPopupMenu.setDefaultLightWeightPopupEnabled(false)` (меню над «тяжёлым» окном VLC), `refreshScreens()`, `applyRole(...)`, `Tray.install(trayActions)`, на Маке `MacScreen.install(quit, openOperator, dockMenu())`, `reconcile()`, `startTimers()`.
11. `observe()` — подписки на `core.peers.state`, `core.videoCommands`, `connectCard`, `core.state + pinFlow` → `update(frameState())` во все окна.
12. `autoVirtualHall()` (см. §4.6), `updates.start()`.
13. «Что нового» (`UpdatesWindow.whatsNew`) — если это не компьютер-экран с одним монитором (окно открылось бы поверх зала).
14. `addShutdownHook { shutdown() }`.
15. Пульт при запуске: `opts.operator ?: (role != SCREEN || operatorArea() != null)`. В журнал: `Роль: …, мониторов: …, пульт при запуске: да/нет`.

### 1.4. Роли

`Role` (`RoleChooser.kt`): `REMOTE` («Пульт»), `SCREEN` («Экран»), `BOTH` («Пульт и экран»). Хранится текстом (`Role.name`) в файле **`role`** (не `role.txt`) папки данных; испорченный файл — как первый запуск.

`applyRole(r, changed)`:

| Роль | Мониторы | Пульт |
|---|---|---|
| `REMOTE` | все мониторы выключены (`enabled = false`, раскладка сохраняется), настройки пишутся в `monitors.json`; `--screen` сбрасывается | открывается |
| `SCREEN` | при смене роли — `MonitorSettings()` (умолчания) | при запуске — только если есть место вне зала (`operatorArea() != null`) |
| `BOTH` | при смене — умолчания; один монитор — он помечается «в окне» (`windowedIds`) | открывается |

Окно `RoleChooser`: три карточки, клавиши 1–3, Esc — закрыть без выбора. Смена из меню — `chooseRole()`.

### 1.5. Выход

`quit()`: если `DesktopUpdates.installOnExit()` (режим «Автоматически» и скачанная версия готова) — ждёт до `INSTALL_WAIT_MS` = 60 с, пока фаза `installing`, затем `exitNow()`. `shutdown()` (один раз, флаг `stopped`): сначала `VirtualScreen.releaseOnExit()` (Linux, Мак — иначе остаётся пустой монитор), затем поиск, пересылка, mDNS, AirPlay, съёмка, сервер, трей, пульт и окно подтверждения, окна вывода.

---

## 2. Мониторы и окна вывода

### 2.1. Список мониторов (`Monitors.kt`)

- `ScreenInfo(id, x, y, width, height)` — `GraphicsDevice.iDstring`, положение и **настоящий** размер (из `displayMode`, при масштабе 125–150 % он больше `bounds`).
- `withoutMirrors(screens, primaryId)` — мониторы с общим левым верхним углом считаются одним (остаётся основной). Так проектор в режиме «повтор» (X11: два выхода с одного угла) не становится «вторым монитором» и зал не закрывает рабочий стол оператора. Число таких — в журнал (`monitors`).
- `resolveMonitors(screens, primaryId, saved, singleTakesHall)` → `List<MonitorOutput>`: имя «Монитор N», `layout` (по умолчанию `HALL`), `enabled` (по умолчанию: один монитор — зал на нём; несколько — основной свободен, на остальных зал), `dim` (плотность «Наложения»).
- `singleTakesHall = !sawSeveralScreens || role == SCREEN`: проектор пропал посреди работы — оставшийся монитор пульта зал сам не берёт. Если один монитор остаётся один дольше `SINGLE_TAKES_HALL_AFTER_MS` = 15 с, он снова может взять зал.
- `--screen N` (`screenOverride`) — зал только на мониторе N, пока оператор не выберет иначе (`setMonitor` сбрасывает).
- `MonitorSettingsStore` — `monitors.json` (`MonitorSettings(monitors: Map<id, SavedMonitor(enabled, layout, dim)>)`); испорченный файл — умолчания; запись через `.tmp`. Хранится только то, что оператор менял.
- Таймер горячего подключения: `HOTPLUG_MS` = 2 с, `refreshScreens()`, при изменении — `reconcile()`.

Управление мониторами приходит из трёх мест, все ведут в `setMonitor(MonitorRequest)`: пульт (`/api/outputs`, `DesktopOutputs : OutputsHost`), меню трея, меню по правой кнопке на мониторе оператора.

### 2.2. Раскладки и режимы окна

`OutputLayout` (ядро; названия — `OutputLayout.title` в `Monitors.kt`):

| Раскладка | Что на мониторе | Кто рисует |
|---|---|---|
| `HALL` «Зал» | показ как есть; видео, видеофон, трансляция, слой поверх показа | `SlideRenderer`, `VideoPlayer`, `ScreenView` |
| `STAGE` «Сцена» | «Сейчас» и «Далее», часы, время показа, отсчёт, метка режима | `OutputFrames.stage` |
| `LOWER_THIRD` «Титры» | текст и Писание внизу на чёрном (`captionContent`) | `OutputFrames.caption` |
| `CHROMA` «Хромакей» | то же на `#00FF00` (`OutputTokens.CHROMA_ARGB`), подпись непрозрачная (`refAlpha = 1f`) | `OutputFrames.caption` |
| `OVERLAY` «Наложение» | прозрачное окно поверх монитора (§3) | `OutputFrames.overlay` |

`WindowMode` (`OutputWindow.kt`) выбирает `ReceiverApp.windowMode(id, shared)`:

| Режим | Когда | Как |
|---|---|---|
| `WINDOWED` | `--windowed` или монитор в `windowedIds` (Esc, двойной щелчок, меню) | окно 960×540 посреди монитора |
| `EXCLUSIVE` | монитор только для зала, не Windows | `GraphicsDevice.fullScreenWindow` |
| `NATIVE` | монитор оператора на Маке | обычное окно, через 300 мс `MacScreen.toggleFullScreen` (полный экран macOS; без `--add-exports java.desktop/com.apple.eawt` — просто окно по монитору) |
| `COVER` | монитор оператора на Windows/Linux; на Windows — и мониторы только для зала | окно без рамки по монитору; `alwaysOnTop = topmost` (= не монитор оператора) |
| `OVERLAY` | раскладка `OVERLAY` | см. §3 |

«Монитор оператора» (`shared`) — единственный или основной: курсор появляется при движении мыши (прячется через 2 с), правая кнопка — меню (`ReceiverApp.menu`), Esc — в окно, F11 / Ctrl+⌘F — обратно, двойной щелчок — переключение. На мониторах только для зала курсора и меню нет. Ctrl+Q/⌘Q — выход, Ctrl+O/⌘O — пульт на любом окне с фокусом (`keyAction`). Окна только для зала не берут фокус (`focusable = shared || tray == null`: без трея Ctrl+Q и Ctrl+O нужны везде).

### 2.3. `reconcile()`

Только в потоке окон. Для каждого включённого монитора: нет окна или сменился `WindowMode` — окно пересоздаётся; иначе `dim`, `setLayout`, `moveTo(device)`. Окна выключенных мониторов закрываются. `primary` (звук и ход видео) — у первого окна с раскладкой `HALL`. Затем `update(frameState())` во все окна, `tray.rebuild()`, `updateApproval()`.

`frameState()` собирает `FrameState` из `core.state`: содержимое, режим, следующий слайд, видео, время начала показа, `IdleInfo` (имя, PIN, адрес, `pairingUri`), отсчёт, слой, логотип. При `connectCard` («QR и код на экран») вместо содержимого — `null`, то есть экран ожидания с QR и PIN.

Таймеры (`startTimers`): горячее подключение 2 с; отчёт о видео 500 мс (`hallWindow().videoState()` → `core.reportVideo`); обратный отсчёт 40 мс (с долями секунды) или 250 мс; часы «Сцены» 1 с.

### 2.4. `OutputWindow`

Одно окно на монитор (`JFrame` с `CardLayout`: `slide` — `SlideView`, `screen-fast` — `ScreenView`, `video` — `VideoPane`). Окно рисует только `FrameState`; ошибка кадра — чёрный кадр (для «Наложения» — прозрачный, для «Хромакея» — зелёный) и `host.reportError`.

- **Перерисовка только при изменении.** `update(s)` считает ключ кадра (`drawnKey`) по раскладке: для зала — содержимое, режим, экран ожидания, строка отсчёта, логотип; для «Сцены» — состояние с точностью до секунды. Не изменился — кадр не рисуется.
- **Рендер в фоне, `renderSeq`.** `redraw` берёт размер в настоящих пикселях (`defaultTransform.scaleX`), увеличивает `renderSeq` и рисует в `host.scope` (Dispatchers.Default) под `synchronized(frames)`. Если пока кадр ждал, пришёл новый — старый не рисуется и не ставится. Результат ставится в потоке окон, только если `renderSeq` не изменился. Так быстрое листание тяжёлого PDF и отсчёт с долями секунды не копят отставание. `--snapshots` пишет каждый кадр в `frame-<раскладка>-<время>.png`.
- **«Уйти с экранов»** (`Mode.AWAY`) прячет окно зала целиком; любой другой режим — окно на месте (`place()`).
- **Видео** (только `HALL`, `applyVideo`): VLC создаётся лениво (`ensureVideo`), программа без видео от VLC не зависит. Каждый монитор зала — свой `VideoPlayer`; звук — только у `primary` (`audio = primary`); новый монитор подхватывает фильм с `s.video.positionMs`. «Чёрный»/«Логотип» — `hide()` (пауза), обратно — `unhide()`. Нет VLC — пульту ошибка «На этом компьютере не найден VLC…». Смена `primary` перезапускает видео со звуком.
- **Трансляция экрана** (`ScreenContent`): быстрый путь, если есть `host.streamFrames()` и `LowLatencyDecoder.available` — `ScreenView` + `ScreenAudio` (только у `primary` и в `Mode.NORMAL`). Сбой декодера — `screenFailed = true` до конца работы, дальше трансляцию ведёт VLC по `server.localStreamUrl(port)` (`/stream/mp4`), с перезапуском потока через 300 мс при обрыве.
- **Видеофон** (`TextStyle.backgroundVideo`, `BackgroundVideo.kt`): отдельный беззвучный VLC в память (кадры не шире `MAX_W`), рисуется под слайдом в `SlideView`; тот же файл не перезапускается при смене слайда, смена фона — растворение за 500 мс; на «Чёрном» и «Логотипе» не показывается.
- **Слой поверх показа** (логотип, плашка, бегущая строка, часы): раскладка — из ядра (`OverlayMotion`, `OverlayFrame`), рисует `OverlayPainter`; частота таймера слоя — `OverlayPainter.tickMs` (движется — часто, стоит — реже или никогда). Где рисуется (`placeOverlay`): поверх `SlideView`/`ScreenView`; над видео на Маке — в `VideoPane` (кадры VLC идут через память, `CallbackMediaPlayerComponent`); на Windows и Linux VLC рисует в своё системное окно (`nativeWindow`), поэтому слой над видео — отдельное прозрачное `OverlayWindow` (`JWindow`, владелец — окно вывода, без фокуса). `OverlayWindow.supported` = только Windows: в Linux без композитора Java отвечает «умею», а окно выходит сплошным чёрным, — там слоя над видео нет. Слой виден на `HALL` и `OVERLAY` при режимах, где `overlaysVisible(mode)`; «Чёрный»/«Заставка» убирают его сразу, без анимации.

`VideoPlayer` (`VideoPlayer.kt`): `vlcFound` — `NativeDiscovery().discover()` один раз на программу. Аргументы libVLC: `--deinterlace=-1 --deinterlace-mode=yadif --no-osd --no-snapshot-preview --no-video-title-show` + `DEIXIS_VLC_ARGS` из окружения. Windows/Linux — `EmbeddedMediaPlayerComponent` (ввод VLC выключен), Мак — `CallbackMediaPlayerComponent` (встраивание окна VLC на Маке зависает). Запас потока трансляции — `STREAM_CACHING_MS` = 150. `VideoPane` ставит кадр по формуле ядра (`videoRect`: вписать, заполнить, растянуть, исходный размер), поля — чёрные, цвет темы или размытая копия кадра (снимок раз в `BLUR_MS`).

### 2.5. `OutputFrames` и `SlideRenderer`

`OutputFrames` — один на окно (кэш свой у каждого монитора), `render(layout, w, h, s, dim)` под `@Synchronized`:

- `HALL` → `SlideRenderer.render`; видео и трансляция в `NORMAL` дают чёрный кадр под ними; экран ожидания — только при `content == null` (если видео не запустилось, зал видит чёрное, а не PIN).
- `STAGE` — крупно «Сейчас» (62 % ширины; на вертикальном мониторе — 62 % высоты), справа «Далее», часы `HH:mm`, время показа, отсчёт, метки «Чёрный»/«Логотип»/«Очистить». Панели кэшируются (LRU, 6 штук).
- `LOWER_THIRD`/`CHROMA` — `captionContent`: только текст и Писание; фото, PDF и видео в титры не попадают.
- `OVERLAY` — §3.

`SlideRenderer` рисует кадр в `BufferedImage` (тестируется без окна): фон темы, текст, Писание, отсчёт (цифры в ячейках одной ширины), фото и страницы PDF (через `PdfPages`), «Логотип» (знак ΔΕΙΞΙΣ, Δ, δεῖξις, Deixis или своя картинка). **Экран ожидания** (`drawIdle`): знак, имя, QR (`pairingUri`), «PIN для подключения», PIN, адрес и подсказка; широкий экран — QR слева от PIN, вертикальный и квадратный — столбиком (`drawIdleTall`). Без сети QR нет, подсказка — подключить Wi-Fi. Шрифты — `Fonts` из ресурсов `/web/` (GFS Didot для знака, Noto Sans SemiBold). `renderPreview`/`renderOverlay` — превью для пульта и кадр «Наложения».

### 2.6. `PdfPages`

Общий объект на всю программу (все мониторы, зрители, миниатюры пульта):

- документ остаётся открытым (до `MAX_DOCS` = 3; не трогали `IDLE_MS` = 60 с — закрывается: на Windows открытый файл нельзя удалить);
- готовые страницы — LRU до `MAX_BYTES` = 64 МБ (около 6 страниц Full HD);
- ключ кэша — путь, `lastModified`, страница, размер;
- `page()` рисует запрошенную страницу и ставит **следующую** в очередь потока `pdf-ahead` (низкий приоритет); ошибка предзагрузки молча игнорируется.

---

## 3. «Наложение» (`OVERLAY`)

Раскладка для монитора, где уже идёт другая программа (DECISIONS 2026-11-11). Окно создаётся с `WindowMode.OVERLAY`; смена раскладки на «Наложение» и обратно пересоздаёт окно.

- **Окно:** без рамки, `Window.Type.UTILITY` (без значка на панели задач), `alwaysOnTop`, `focusableWindowState = false`, по границам монитора; курсор не прячется (виден курсор программы под окном). Меню по правой кнопке на нём нет.
- **Прозрачность:** `see` = `isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT)`. Тогда фон окна и панелей прозрачный, кадр — `TYPE_INT_ARGB`. Иначе в журнале `overlay: система не умеет прозрачные окна на этом мониторе: наложение — на чёрном`, окно сплошное.
- **Кадр** (`OutputFrames.overlay`): слой `Color(0, 0, 0, overlayAlpha(dim))`, плотность `dim` 0–100 % (по умолчанию `OVERLAY_DIM_DEFAULT`, хранится в `monitors.json`); поверх — `overlayContent`: текст и Писание — титрами внизу, отсчёт, картинки и PDF — как есть, видео и трансляция — нет. «Чёрный», «Логотип», «Уйти» — пустой прозрачный кадр; «Очистить» — только слой. Сбой рендера — прозрачный кадр, а не чёрный поверх чужой работы.
- **Мышь сквозь окно** — `ClickThrough.apply(frame)` после показа окна (`invokeLater`). Успех — `overlay: наложение на <id>: мышь проходит к программе под ним`; неудача — предупреждение, окно остаётся обычным, показ не страдает.

| ОС | Как | Проверка в CI |
|---|---|---|
| Windows | `GWL_EXSTYLE` |= `WS_EX_LAYERED \| WS_EX_TRANSPARENT \| WS_EX_NOACTIVATE \| WS_EX_TOOLWINDOW` (JNA `User32`) | строго: `OverlayWindowGuiTest` требует, чтобы щелчок дошёл до окна под наложением |
| Linux (X11) | `XFixesCreateRegion(d, null, 0)` + `XFixesSetWindowShapeRegion(..., ShapeInput, ...)` — пустая область ввода; своё соединение `XOpenDisplay` | под xvfb без композитора проверка прозрачности и щелчка пропускается |
| macOS | у окна ставится временный уникальный заголовок `deixis-overlay-<nanoTime>`, NSWindow ищется в `[NSApplication sharedApplication].windows` по заголовку (до 10 попыток по 50 мс; указатель JAWT у окон на слоях Core Animation бывает пустым), затем `setIgnoresMouseEvents:YES`; заголовок возвращается | **не подтверждено**: тест только печатает, проходит ли щелчок |

На Wayland путь X11 не применим; поведение там не проверено.

---

## 4. Виртуальный монитор

### 4.1. Фасад `VirtualScreen` (`CaptureTargets.kt`)

| Член | Смысл |
|---|---|
| `WIDTH`/`HEIGHT` | 1920×1080 |
| `supported`, `unsupported` | можно ли здесь; `unsupported` — причина текстом для пульта и диагностики |
| `exists` | Мак — `displayId != null`; Linux — `LinuxVirtualScreen.created != null`; Windows — `deixis-display.exe status`, результат кэшируется на 5 с |
| `create(): Result<CaptureArea>` | одна попытка за раз (`creating` — `AtomicBoolean`, иначе «уже создаётся — ответьте в окне Windows»); после неудачи 5 с (`RETRY_PAUSE_MS`) новые попытки сразу возвращают `lastError` — повторные нажатия не открывают окно UAC раз за разом |
| `lastError` | последняя ошибка создания (для пульта и `DesktopChecks`) |
| `isVirtual(screenId, x)` | этот монитор Java — виртуальный экран Deixis (Мак — по `displayId` в `iDstring`; Windows — по совпадению места с `WindowsVirtualScreen.find()` в настоящих точках; Linux — по левому краю `x`) |
| `releaseOnExit()` | Мак и Linux убирают экран при выходе; Windows — нет |
| `remove()` | убрать сейчас (Windows — с UAC) |

Все шаги пишутся в журнал с меткой `virtual`.

### 4.2. Linux (`LinuxVirtualScreen`, `Xrandr`)

`unsupported`: не Linux; сеанс Wayland (`WAYLAND_DISPLAY`); нет `xrandr` (пакет `x11-xserver-utils`).

`create()` (`@Synchronized`, повторный вызов возвращает уже созданный):

1. `xrandr` без ключей → `Xrandr.screen` (размер рабочего стола, `current W x H`) и `Xrandr.outputs` (`XOutput`: имя, подключён, основной, геометрия).
2. **Путь 1 — свободный выход видеокарты** (`viaOutput`): `Xrandr.freeOutput` — сначала `VIRTUAL*` (их заводит драйвер Intel), затем любой неподключённый и выключенный выход, кроме `eDP*`/`LVDS*`. `--newmode DEIXIS1080 173.00 1920 2048 2248 2576 1080 1083 1088 1120 -hsync +vsync` (cvt 1920 1080 60), `--addmode`, `--output … --mode DEIXIS1080 --pos <справа>x0`; повторный `xrandr` проверяет, что выход включился. Такой монитор видят система, настройки экранов и все программы. Не вышло — откат (`--off`, `--delmode`).
3. **Путь 2 — область рабочего стола:** `--fb (W+1920)x(max(H,1080))`, затем `--setmonitor DEIXIS 1920/531x1080/299+W+0 none`. Видят программы, но не настройки экранов (Cinnamon). Неудача `--setmonitor` — размер возвращается.
4. `created = Triple(x, 1920, 1080)`.

`remove()`: путь 1 — `--output … --off`, `--delmode`, `--rmmode`; путь 2 — `--delmonitor DEIXIS`, `--fb` к прежнему размеру. Вызывается из `shutdown()` первым — и из обычного выхода, и из хука завершения (`SIGTERM`); это проверяет `desktop-smoke.yml` (Linux: Xorg с `dummy`-драйвером, «повтор» на `DUMMY1`, после `pkill -TERM` мониторов снова 2).

### 4.3. Windows (`WindowsVirtualScreen`, `windows-display/`)

Свой драйвер **Deixis Display** (IddCx, UMDF 2), написан по документации (DECISIONS 2026-11-05). Сборка — `windows-display/build.ps1` (Visual Studio 2022 + WDK): `out\` с `DeixisDisplay.inf`, `.dll`, `.cat`, `.cer` и `deixis-display.exe`; подпись своим сертификатом, закрытый ключ удаляется сразу после подписи.

Где лежит драйвер (`dir`): системное свойство `-Ddeixis.display=<папка>` или `<папка Deixis.exe>\deixis-display` (по `jpackage.app-path`); папка годится, если в ней есть `deixis-display.exe` и `DeixisDisplay.inf`. Нет папки — `supported = false`, причина «драйвер Deixis Display не входит в эту установку…».

Помощник `deixis-display.exe` (`windows-display/installer/deixis-display.cpp`):

| Команда | Что делает | Коды |
|---|---|---|
| `install <inf> <cer>` | ставит сертификат в «Доверенные корневые» и «Доверенные издатели», заводит устройство `Root\DeixisDisplay`, `UpdateDriverForPlugAndPlayDevicesW` | 0 — монитор подключён; 2 — сертификат не принят и драйвер без него не встал; 3 — устройство не заведено; 4 — драйвер не встал; 1223 — отказ в окне Windows |
| `remove` | убирает устройство (драйвер остаётся в хранилище Windows) | — |
| `status` | устройство есть? | 0 — да, 1 — нет |
| `--log <файл>` (где угодно) | каждый шаг и ошибка Windows — ещё и в файл | — |
| (иное) | — | 64 |

`create()` в Deixis:

1. `find()` — монитор с `DXS0001` в `DeviceID` (`EnumDisplayDevices`, затем `EnumDisplayMonitors` → `rcMonitor`). Нашёлся — готово.
2. Если устройства нет (`!exists`): `elevated("install", inf, cer, "--log", <tmp>)`. Повышение прав — PowerShell: `Start-Process -FilePath … -ArgumentList … -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ErrorAction Stop; exit $p.ExitCode`, исключение (отказ в UAC) → `exit 1223`. Ждём до 120 с; не дождались или не запустилось — `-1`. Строки временного лога переносятся в журнал (`virtual: Windows: …`), файл удаляется.
3. Разбор кода:

| Код | Итог |
|---|---|
| 0 | дальше ждать монитор |
| −1 | окно UAC могло остаться под другими окнами: ждём монитор ещё `LATE_MS` = 60 с; не появился — `Refused("окно прав администратора осталось без ответа …")` |
| 1223 | `Refused(DECLINED)`, `DECLINED` = «права администратора не даны» |
| 2 / 3 / 4 | ошибка с объяснением (сертификат/антивирус; устройство; драйвер) |
| иное | «установка драйвера: код N» |

4. `waitFor(APPEAR_MS = 10 с)` с опросом 250 мс; нет — «драйвер стоит, но Windows не подключила монитор: перезагрузите компьютер …».

Монитор на Windows **остаётся** после выхода (Holyrics и другие программы помнят его); убирается только «Убрать» в пульте (`remove()` → `elevated("remove")`, тоже UAC).

### 4.4. macOS (`MacVirtualScreen`)

`CGVirtualDisplay` (macOS 11+, недокументированный API; тот же механизм, что у DeskPad, код не копировался) через JNA и `objc_msgSend`: `CGVirtualDisplayDescriptor` (очередь, имя «Deixis», 1920×1080, 531×299 мм, vendor `0xDE1`), `CGVirtualDisplaySettings` (без HiDPI, режим 1920×1080@30), `applySettings:`. Дисплей живёт, пока жив объект; `remove()` — `release`. `captureIndex(id)` — номер для avfoundation («Capture screen N») по `CGGetActiveDisplayList`. `supported` — есть ли класс `CGVirtualDisplay`.

### 4.5. Флаг `virtual-auto-off`

Файл `virtual-auto-off` в папке данных (`AUTO_VIRTUAL_OFF`) запрещает **автоматическое** создание при запуске. Пишется только при:

- отказе в окне прав Windows (`Refused` с сообщением `DECLINED`, то есть код 1223);
- «Убрать» в пульте (`CaptureSource.ID_REMOVE_VIRTUAL`).

Удаляется, когда оператор сам нажал «Создать» в пульте. Любая другая неудача (окно UAC без ответа, драйвер, xrandr) не запоминается — при следующем запуске попытка снова (DECISIONS 2026-11-11).

### 4.6. `autoVirtualHall()`

Условия (иначе в журнал `virtual: сам не создаю: <причина>`): есть экран и нет `--windowed`; роль `BOTH`; ровно один монитор (после `withoutMirrors`); нет `virtual-auto-off`; `VirtualScreen.unsupported == null`.

В фоне: `VirtualScreen.create()`; затем до `VIRTUAL_WAIT_MS` = 20 с раз в секунду `refreshScreens()` в потоке окон и поиск виртуального монитора (`isVirtual`, иначе «второй из двух»). Нашёлся — все прочие мониторы выключаются (`enabled = false`), виртуальный — `HALL`, снимается из `windowedIds`, `monitors.json` сохраняется, `reconcile()`. В журнал: `virtual: зал — на виртуальном мониторе` (эту строку ищут проверки CI) или `виртуальный монитор не появился в списке мониторов`.

Диагностика: `DesktopChecks` «Виртуальный монитор Deixis» — есть / не поддерживается (причина) / последняя ошибка / не создан.

---

## 5. Съёмка и ретрансляция экрана

### 5.1. Источники (`DesktopCapture`, внутренний класс `ReceiverApp`, реализует `CaptureHost`)

`state()` отдаёт пульту `CaptureState(list, activeId, error, note)`:

- мониторы (кроме виртуального экрана Deixis) с признаком «здесь зал»;
- «Виртуальный экран Deixis» (`id = "virtual"`) или «Создать виртуальный экран» (`ID_NEW_VIRTUAL`), если поддерживается;
- окна программ `AppWindows.list()` (`win:<id>`): Linux — `xprop -root _NET_CLIENT_LIST`, `xprop _NET_WM_NAME`, `xwininfo`; Windows — `User32.EnumWindows` (видимые с заголовком); Мак — пусто. Окна с заголовком на «Deixis» и меньше 120 точек не попадают;
- `note`: причина, почему виртуального экрана нет, или предупреждение про UAC на Windows.

`select(id)`:

- `ID_REMOVE_VIRTUAL` — пишет `virtual-auto-off`, `VirtualScreen.remove()`;
- `ID_NEW_VIRTUAL`/`virtual` — `VirtualScreen.create()`, удаляет `virtual-auto-off`; через 1,5 с, если Java увидела новый монитор и на нём зал, зал с него снимается; съёмка области;
- `win:<id>` — Linux: `CaptureArea.windowId`, Windows: `windowTitle`;
- монитор — если на нём зал, зал снимается (иначе Deixis снимал бы сам себя); на Windows координаты пересчитываются в настоящие точки (масштаб 125/150 %).

Сторож съёмки: раз в секунду; как только картинка была на экране, а потом пульт показал другое (`MirrorOutput.onScreen() == false`) или поток съёмки умер — съёмка останавливается. Новый подписчик (`core.stream.keyRequests`) → `ScreenCapture.requestKey()`.

### 5.2. `ScreenCapture`

FFmpeg (bytedeco, сборка LGPL) в потоке `deixis-capture`:

| ОС | Вход | Параметры |
|---|---|---|
| Linux | `x11grab` `$DISPLAY+x,y` или `window_id` | `video_size`, `draw_mouse=1` |
| Windows | `gdigrab` `desktop` или `title=<окно>` | `offset_x/y`, `video_size`, `draw_mouse=1` |
| macOS | `avfoundation` `Capture screen N:none` | `capture_cursor=1`, `pixel_format=nv12` |

Кодировщик — первый открывшийся: Мак `h264_videotoolbox` (`realtime`, baseline), Windows `h264_mf` (`display_remoting`, CBR, NV12), затем `libopenh264` (`allow_skip_frames`). 30 к/с, ключевой кадр раз в секунду и по просьбе; длинная сторона до 1920 (на 2 ядрах и меньше — 1280); битрейт ≈ 0,1 бит/пиксель (1–8 Мбит/с). Кадры Annex-B идут в `MirrorOutput(core)` — общий поток экрана: зал, экраны в сети, зрители по QR.

### 5.3. Показ трансляции на этом компьютере

- `LowLatencyDecoder` — декодер FFmpeg по принципу scrcpy: без буфера плеера, `FF_THREAD_SLICE`, `AV_CODEC_FLAG_LOW_DELAY`; `available` — загрузились ли библиотеки FFmpeg.
- `ScreenView` — получает `StreamSample` прямо из `core.stream.subscribe()` (без HTTP и MP4), декодирует в `Dispatchers.IO`, рисует последний кадр (`paintImmediately`, лишние кадры заменяются); сбой — `onFailed`, окно переходит на VLC.
- `ScreenAudio` — PCM из `core.audio`, запас около 40 мс, больше 100 мс накопилось — лишнее выбрасывается; нет звукового устройства — без звука.

### 5.4. Пересылка на экраны в сети (`ScreenRelay`, ядро)

`ReceiverApp` создаёт `ScreenRelay(core, FileRelayStore(screens.json), scope, name)`: каждое изменение `core.state` повторяется на сопряжённых экранах Deixis (файлы — из хранилища этого приёмника, затем `show`, режим, команды видео). `Discovery.browse` → `relay.found`; связь — `link`/`unlink` через `DesktopOutputs`. Сбой одного экрана не трогает другие и свой вывод.

### 5.5. AirPlay

`AirPlayHost(core, dataDir)` (ядро, перенос UxPlay; в сборку для Google Play не входит — см. CLAUDE.md). По умолчанию выключен; флаг — файл `airplay-on`, ключ — `airplay-key`. Переключатель «Принимать AirPlay (iPhone, iPad, Mac)» (`AIRPLAY_LABEL`) — в трее, меню по правой кнопке, меню Dock; `setAirPlay` идёт в `Dispatchers.IO`, затем `tray.rebuild()`. Картинка AirPlay идёт в тот же `StreamHub`, что и трансляция с телефона.

---

## 6. Пульт внутри программы

### 6.1. Открытие (`openOperator`)

- Без экрана (headless) — `OperatorLauncher.open("http://127.0.0.1:<port>/remote#t=<token>", operator-browser/)`: Edge/Chrome/Chromium с `--app`, своим профилем в `operator-browser`, иначе браузер по умолчанию.
- С экраном — в `Dispatchers.IO`: `operatorToken()`, затем **`pultController()` заранее** (первый раз читает библиотеку пульта; `@Synchronized`), чтобы окно открылось без паузы и зал не замирал. Затем в потоке окон `openPult(token)`; ошибка — запасной путь через браузер. В журнал: `Окно пульта: открыто` / `запасной путь — браузер` (строку ищут проверки CI).

`openPult`:

1. Первый раз — `PultWindow(controller, DesktopHost(...), Ui(), Words.operatorTitle) { pultClosed() }`.
2. `controller.setCable(Target(core.info.id, name, "127.0.0.1", port, Target.Kind.LOCAL, platform), token)` — свой экран первым, без PIN.
3. `controller.startDiscovery()`, `w.open(operatorArea())`.

**LOCAL-цель.** Для `Kind.LOCAL` `Controller` создаёт `SenderClient(...).apply { sameDevice = true }`. `SenderClient.adopt()` при `sameDevice` не переходит на TLS-вход экрана: связь не выходит в сеть, а шифрованный вход пометил бы токен «только по шифрованному каналу», и экран перестал бы считать пульт «этим компьютером» (и `/api/activate` с этим токеном перестал бы работать).

`operatorArea()` — где можно показать интерфейс, не задев зал: монитор без зала (сначала основной), иначе монитор, где зал в окне; `null` — все мониторы показывают зал во весь экран (минус панели системы — `getScreenInsets`).

`pultClosed()`: `setCable(null, null)`, `stopDiscovery()`; если роль не «Экран» — `quit()` (DECISIONS 2026-11-03), иначе только `updateApproval()`.

### 6.2. `PultWindow` (Compose)

`ComposeWindow` с `DeixisPhone(controller, host.holyrics, ui)` в `CompositionLocalProvider(LocalHost provides host)`. Размер 1400×900 (не больше области минус 40), минимум 720×520, по центру `operatorArea`. Esc — `DesktopBack.back()` (закрыть шторку); клавиши кликера (если включены в настройках пульта): →, пробел, PageDown, MediaNext — вперёд; ←, PageUp, MediaPrevious — назад; B — «Чёрный». `raise()` — на 600 мс `alwaysOnTop`, чтобы окно не осталось под окном зала (Windows). `close(notify)` — `StoreWriter.flush()` (несохранённое пульта дописывается сразу). Глобальная `pultFrame` — родитель для системных окон выбора файлов (`PlatformUi.kt`).

### 6.3. `DesktopPlatform` (реализация `Platform`)

| Что | Как |
|---|---|
| данные пульта | `filesDir` = `<data>/pult`, `cacheDir` = `<data>/pult-cache` |
| настройки | `prefs(name)` → `FilePrefs`: `<data>/pult/prefs/<name>.properties`, запись сразу через `.tmp`; наборы строк — ключ `set.<key>`, разделитель `\u001F` |
| файлы | URI `file:///…` — обычные файлы; `meta` — `Files.probeContentType` или `mimeOf` |
| PDF | `pdfPages` и миниатюры страниц — `PdfPages` (тот же открытый документ, что у зала) |
| видео | `videoDurationMs` и кадр миниатюры (1 с) — `VideoProbe` (FFmpeg); предпросмотр — `DesktopVideo.kt` |
| перевод | `canConvertHere`: pptx, ppt, docx, doc, odp, odt, key, rtf; `convertToPdf` — тот же `OfficeConverter`, что у приёмника, во временной `pult-cache/convert-<nanoTime>` |
| SQL | `JdbcSql` (модули MyBible) |
| HEIC | не переводится (`false`) |
| демо | ресурсы `/demo/*` и `/demo/index.txt` (из `android/phone/src/main/assets`, задача `writeDemoIndex`) |
| прочее | `features = Features(desktop = true, screenShare = false, becomeScreen = false, cable = false)`; `lanAddress` — адрес приёмника; `visible` — пульт на экране; `dlna = null`; `shareFile` — системное окно «Сохранить»; диагностика — `Checks.common(filesDir) + DesktopChecks` |

`DesktopHost : PultHost`: обновления — `DesktopUpdates`; «Что нового» показывает окно программы, не пульт (`takeWhatsNew` пуст); отзыв — текст с версией и ОС в буфер обмена; `openCastSettings`, `becomeScreen` — ничего.

### 6.4. Подтверждение подключений (`ApprovalWindow`)

`updateApproval()`: `core.peers.screenApprover = operatorArea() != null`. Пока окно пульта открыто, запросы «Новое подключение» показывает пульт (он подтверждающий); закрыто — первый запрос показывает `ApprovalWindow` — немодальный `JDialog` поверх всех, по центру `operatorArea`, кнопки «Разрешить» / «Отклонить» → `core.peers.answer(id, allow)`, «Ещё ждут: N». Негде показать (все мониторы — зал) — окна нет, на монитор зала оно не выводится никогда.

### 6.5. Трей и меню

`Tray` (`Operator.kt`, `java.awt.SystemTray`; нет трея — `null`, остаются Ctrl+O/Ctrl+Q): «Открыть пульт» (и щелчок по значку), «QR и код подключения на экране» (`connectCard`; у роли «Пульт» вместо этого открывается пульт — там «Устройства» → «QR и код для подключения»), «Экраны» (по монитору: «Показывать» и раскладки; «Скопировать ссылку „Экран в браузере“» — `/view?k=<viewKey>&screen=1`), «Принимать AirPlay», «Как работает компьютер…», «Holyrics — телефон по QR…», «Обновления…», «Журнал программы…», «Выйти». `rebuild()` — при `reconcile`, смене AirPlay и `connectCard`. Значок — `AppIcon.trayImage` точно под `trayIconSize`.

Меню по правой кнопке на мониторе оператора (`ReceiverApp.menu`) — то же плюс «В окно»/«Во весь экран». Меню Dock (`dockMenu`) — без «Экранов».

`Words.kt` — строки окон самой программы на трёх языках по `Locale.getDefault()` (`uz`, `en`, иначе русский): журнал, QR на экране, заголовок пульта, окно подтверждения. Пульт внутри окна переводится сам (язык — в его настройках).

---

## 7. Прочее

### 7.1. Перевод в PDF (`OfficeConverter`, `PdfEngines.kt`)

`findPdfEngines()` по порядку: `MsOfficeEngine.find()` (Windows, PowerShell + COM; PowerPoint для презентаций кроме ODP/KEY, Excel для таблиц, Word для документов кроме ODT; наличие — по ProgID в реестре), `LibreOfficeEngine` (`LibreOfficeConverter.find()`), `KeynoteEngine.find()` (`/Applications/Keynote.app`, AppleScript; PPTX, PPT, KEY). Таймаут программ — 180 с; работа — в `workDir` (`office.ps1`, `office.pdf`, `office-log.txt` / `keynote.*`).

`OfficeConverter.toPdf`: для презентации читает `Deck` (видимые слайды, размер) через Apache POI; нет видимых слайдов — ошибка. Каждый PDF программы сверяется (`check`: число страниц и пропорции); первый верный — результат. Без программ или при их ошибках PPTX/PPT рисует встроенный POI (`renderWithPoi`, с добавлением встроенных стилей таблиц `BuiltInTableStyles`), `audit` называет приблизительное (диаграммы, SmartArt, шрифты) — пульт получает предупреждение «Встроенный перевод. …». Документы, таблицы, ODP, KEY без программ — ошибка с советом поставить Office/LibreOffice.

### 7.2. Обновления (`DesktopUpdates.kt`)

`Updater(version, target, <data>/updates, scope)` из ядра (`update-settings.json`, `seen-version`, `update-<версия>-<файл>` и `.part` с проверкой SHA-256). Установка:

| ОС | Как |
|---|---|
| Windows | `update-install.cmd`: пауза 3 с, `msiexec /i … /passive /norestart`, при перезапуске — `start Deixis.exe`; запуск `cmd /c start "Deixis" /min`, программа выходит |
| macOS | `update-install.sh` через `nohup`: монтирует `.dmg`, меняет `Deixis.app` (при сбое возвращает старый), `open`; если программа не в записываемой папке — открывается образ и просьба перенести вручную |
| Linux | `xdg-open` на `.deb` (установщик системы с паролем) |

С пульта (`install`) — только когда ничего не показывается. Режим «Автоматически» ставит скачанное при выходе (`installOnExit`, без перезапуска). Окно «Обновления…» — `UpdatesWindow`; «Что нового» — один раз после обновления.

### 7.3. Holyrics: QR для телефона (`HolyricsQrWindow`)

Окно «Holyrics — телефон по QR»: проверяет API Server Holyrics на этом компьютере и показывает QR со ссылкой `deixis-holyrics://` (адрес компьютера в сети и токен). Токен Holyrics один раз вставляется оператором и хранится в `holyrics-token`.

### 7.4. `JdbcSql`

SQLite через sqlite-jdbc, только чтение (`open_mode=1`). Одно соединение — один запрос за раз: `query` и `close` — `@Synchronized` (стихи готовятся в фоне, пока пульт читает ту же базу).

### 7.5. Журнал и диагностика

`app.deixis.diag.Log` (модуль `diag`): в памяти с запуска, в файл — после старта сервера (§1.3). Метки, по которым удобно искать: `virtual`, `overlay`, `monitors`, `pult`. «Журнал программы…» открывает папку (на Windows — `explorer /select,log.txt`). `DesktopChecks`: мониторы и зал, виртуальный монитор, VLC, FFmpeg, перевод презентаций, окно пульта.

---

## 8. Файлы в папке данных

Папка — `~/.deixis/receiver` или `--data`. Проверено по коду `desktop/`, `core/`, `diag/`.

| Файл / папка | Кто пишет | Что |
|---|---|---|
| `id` | `ReceiverApp.init` | UUID экрана |
| `tokens.json` | `FileTokenStore` | токены сопряжённых пультов |
| `operator-token` | `ReceiverApp.operatorToken` | токен «Этот компьютер» (пульт по 127.0.0.1, `/api/activate`) |
| `view-key` | `loadViewKey` | ключ ссылки зрителей `/view?k=` |
| `role` | `RoleStore` | `REMOTE` / `SCREEN` / `BOTH` |
| `monitors.json` (+ `.tmp`) | `MonitorSettingsStore` | выбор оператора по мониторам |
| `screens.json` | `FileRelayStore` | экраны пересылки |
| `virtual-auto-off` | `ReceiverApp` | не создавать виртуальный монитор сам |
| `airplay-on`, `airplay-key` | `AirPlayHost` | AirPlay включён; ключ AirPlay |
| `holyrics-token` | `HolyricsQrWindow` | токен API Holyrics |
| `log.txt`, `log.old.txt` | `Log.toFile` | журнал этого и прошлого запуска |
| `tls/` (`tls-key.der`, `tls-cert.der`) | `TlsIdentity.loadOrCreate` | ключ и сертификат экрана |
| `files/` | `FileStore` | файлы показа по SHA-256 |
| `convert/` | `Conversions` | рабочая папка перевода в PDF |
| `bible/` | `BibleStore` | модули MyBible для веб-пульта |
| `updates/` | `Updater`, `DesktopUpdates` | `update-settings.json`, `seen-version`, установщики, `update-install.cmd`/`.sh` |
| `pult/` | `DesktopPlatform` и общий пульт | данные пульта: `prefs/*.properties`, `bible/`, `converted/`, `demo/`, `overlay-samples/` и др. |
| `pult-cache/` | `DesktopPlatform` | `convert-<nanoTime>/` (удаляется после перевода) |
| `operator-browser/` | `OperatorLauncher` | профиль Chromium — только при пульте в браузере (запасной путь) |

Вне папки данных: `~/.deixis/sender-tokens.json` (команда `send`), `<tmp>/deixis-preview` (`FileStore` превью пульта, `PlatformUi.kt`), `<tmp>/deixis-display-*.log` (Windows, удаляется), кадры `--snapshots`.

---

## 9. Сборка и установщики

`desktop/build.gradle.kts`:

- `application`, `mainClass = app.deixis.desktop.MainKt`, `applicationName = deixis`; JVM: UTF-8 для файлов и консоли, на Маке `--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED`. Java 17 (байт-код), CI собирает на Temurin 21.
- Зависимости: `core`, coroutines-swing, vlcj, PDFBox, imageio webp/tiff, Apache POI (ooxml, scratchpad), sqlite-jdbc, FFmpeg bytedeco + javacpp **только платформы сборки** (`javacppPlatform`), Compose Desktop (runtime, foundation, ui, animation, `desktop-jvm-<composePlatform>`), slf4j-nop.
- Версия: задача `writeVersion` пишет `deixis-version.txt` из `DEIXIS_VERSION` (без `v`, по умолчанию `0.1.0`); `ReceiverApp.APP_VERSION` читает ресурс `/deixis-version.txt`.
- Проверки окон — `-Ddeixis.gui=1` (каждая в своём процессе, `forkEvery = 1`); `-Ddeixis.outputsPng`, `-Ddeixis.trayPng` — кадры в PNG.

`.github/workflows/release.yml`, задача `desktop` (Windows `windows-2022` → `msi`, macOS → `dmg`, Linux `ubuntu-latest` → `deb`):

1. `APP_VERSION` для пакета: версия выпуска без суффикса; на Маке `0.Y.Z` → `1.0.(Y*100+Z)` (macOS не принимает ведущий ноль). Внутрь программы идёт настоящая версия (`DEIXIS_VERSION` при `:desktop:installDist`).
2. VLC 3.0.21: Windows — `libvlc.dll`, `libvlccore.dll`, `plugins/` в `content/vlc`, `-Djna.library.path=$APPDIR\..\vlc`; macOS — `lib/` и `plugins/` из `VLC.app` (arm64-образ), `-Djna.library.path=$APPDIR/../vlc/lib`; Linux — зависимости пакета `libvlc5, vlc-plugin-base, vlc-plugin-video-output`, плюс LibreOffice (impress, writer, calc) и шрифты Carlito, Caladea, Liberation.
3. Windows: `windows-display/build.ps1` → `content/deixis-display`, `--java-options '-Ddeixis.display=$APPDIR\..\deixis-display'`; WiX 3 для `msi`.
4. `jpackage --type <msi|dmg|deb> --name Deixis --app-version $APP_VERSION --input desktop/build/install/deixis/lib --main-jar desktop.jar --main-class app.deixis.desktop.MainKt --java-options -Dfile.encoding=UTF-8 --app-content … --dest out`; значки из `desktop/packaging/`; Windows `--win-menu --win-shortcut --win-dir-chooser --win-upgrade-uuid 6f3c1a52-…`; macOS `--mac-package-name Deixis` и `--add-exports` для `com.apple.eawt`; Linux `--linux-shortcut --linux-package-name deixis`.

`.github/workflows/desktop-smoke.yml` (при изменениях `desktop/`, `pult/`, `core/`) — программа как в установщике (`jpackage --type app-image`), запуск `receive --role both --data <tmp>`:

| Система | Что проверяется |
|---|---|
| Windows | окно «Deixis — пульт» открыто; мониторов ≥ 2; в журнале «зал — на виртуальном мониторе»; повторный запуск; в конце `deixis-display.exe remove` |
| macOS | в журнале «Окно пульта: открыто» (иначе — `jstack`) |
| Linux (Xorg + dummy, «повтор» на `DUMMY1`) | «Окно пульта: открыто», «зал — на виртуальном мониторе», `Monitors: 3`; после `pkill -TERM` — `Monitors: 2` (монитор убран хуком завершения). Запуск через `installDist`, не `jpackage` |

Отдельный `windows-display.yml` собирает драйвер (не разбирался подробно — не проверено).
