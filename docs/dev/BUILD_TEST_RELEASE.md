# Сборка, проверка и выпуск Deixis

Справочник разработчика: что нужно для сборки, какие команды запускать, что делает каждый workflow
в `.github/workflows/`, как устроен выпуск и обновление приложений. Всё сверено с файлами репозитория;
если что-то не удалось проверить чтением, это помечено «не проверено». Значения секретов здесь не приводятся
и нигде не печатаются — только имена.

Связанные документы: [../../README.md](../../README.md), [../../ARCHITECTURE.md](../../ARCHITECTURE.md)
(раздел «Как перепроверяем»), [KNOWLEDGE.md](KNOWLEDGE.md), [../../CHANGELOG.md](../../CHANGELOG.md).

---

## 1. Требования и настройка сборки

### 1.1. Инструменты

| Что | Где задано | Примечание |
|---|---|---|
| JDK 17 или новее | `README.md`; байт-код `JVM_17` во всех модулях | CI и выпуск работают на Temurin **21** (`actions/setup-java`, `java-version: 21`). Для установщиков нужен `jpackage` из JDK. |
| Gradle 8.14.4 | `gradle/wrapper/gradle-wrapper.properties` | Только через `./gradlew`. |
| Android Gradle Plugin 8.11.1 | `build.gradle.kts` (`buildscript`) | Подключается, только если собираются Android-модули (см. 1.2). |
| Android SDK | `local.properties` или `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `compileSdk = 36`, `targetSdk = 35`, `minSdk = 26` (Android 8). |
| VLC 3 | `README.md` | Видео на компьютере (vlcj → libvlc). В установщики Windows и macOS VLC 3.0.21 вкладывается сам (`VLC_VERSION` в `release.yml`); пакет `.deb` зависит от `libvlc5`, `vlc-plugin-base`, `vlc-plugin-video-output`. |
| Python 3 | `tools/*.py` | Скрипты из раздела 5; некоторым нужны Pillow, numpy, python-pptx, LibreOffice, ffmpeg. |
| Visual Studio 2022 + WDK | `windows-display/build.ps1` | Только для драйвера виртуального монитора Windows. |

### 1.2. Android-модули и `DEIXIS_SKIP_ANDROID`

`settings.gradle.kts` всегда включает `:diag`, `:core`, `:desktop`. Модули `:android:receiver`, `:android:tv`,
`:android:phone` включаются, только если одновременно:

- есть Android SDK — существует `local.properties` **или** задана `ANDROID_HOME` **или** `ANDROID_SDK_ROOT`;
- **не** задана переменная `DEIXIS_SKIP_ANDROID` (любое значение отключает Android).

То же условие стоит в `build.gradle.kts`: без Android-модулей AGP и репозиторий Google Maven не подключаются,
поэтому ядро и приложение для компьютера собираются без Android SDK и без доступа к Google Maven.
Задания CI для ядра и компьютера ставят `DEIXIS_SKIP_ANDROID: "1"`.

Репозитории: первым идёт зеркало Maven Central от Google
(`https://maven-central.storage-download.googleapis.com/maven2/`), потому что основной адрес часто отвечает 429;
`repositoriesMode = FAIL_ON_PROJECT_REPOS` — модули своих репозиториев не объявляют.

### 1.3. Локаль: `LC_ALL=C.UTF-8`

Имена файлов с кириллицей (демо-набор `android/phone/src/main/assets/demo/…`, папки Holyrics в тестах)
читаются верно, только если JVM запущена в UTF-8-локали. Под локалью `C` они превращаются в `?????`.

- `:core:test` сам выставляет тестовому процессу `LC_ALL=C.UTF-8` и `LANG=C.UTF-8` (`core/build.gradle.kts`).
- `:desktop:test` и остальные задачи этого **не** делают. В контейнере или CI с локалью `C` запускайте
  Gradle так: `LC_ALL=C.UTF-8 ./gradlew …` (так же сделано в Linux-задании `desktop-smoke.yml`).
  См. также `KNOWLEDGE.md`.

### 1.4. `gradle.properties`

```
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
kotlin.code.style=official
android.useAndroidX=true
android.nonTransitiveRClass=true
```

Демону Gradle отдаётся 3 ГБ, сборка параллельная, с кешем задач.

### 1.5. Ключевые зависимости (`gradle/libs.versions.toml`)

| Библиотека | Версия | Где |
|---|---|---|
| Kotlin (jvm, android, serialization, compose plugin) | 2.4.21 | все модули |
| kotlinx-coroutines | 1.11.0 | все |
| kotlinx-serialization-json | 1.12.0 | diag, core |
| Ktor (server CIO, websockets, partial-content, auto-head; client CIO) | 3.6.0 | core |
| JmDNS | 3.6.4 | core (поиск в сети) |
| BouncyCastle `bcprov-jdk18on` | 1.84 | core (Ed25519/X25519 для AirPlay) |
| ZXing core | 3.5.4 | core (QR) |
| sqlite-jdbc | 3.53.4.0 | desktop; тесты core |
| vlcj | 4.12.1 | desktop (видео) |
| bytedeco FFmpeg / JavaCPP | 7.1-1.5.11 / 1.5.11 | desktop (декодер трансляции; классификатор платформы сборки) |
| Compose Multiplatform (desktop) | 1.8.2 | desktop (общий пульт `pult/`) |
| PDFBox | 3.0.8 | desktop |
| Apache POI (ooxml, scratchpad) | 5.4.1 | desktop (PPTX/PPT → PDF без LibreOffice) |
| TwelveMonkeys imageio-webp/tiff | 3.15.3 | desktop |
| slf4j-nop | 2.0.20 | desktop |
| libvlc-all (Android) | 3.7.7 | android/receiver |
| Compose BOM (Android) | 2025.06.00 | android/receiver |
| activity-compose | 1.10.1 | android/phone, android/tv |

### 1.6. Модули

| Модуль | Тип | Что внутри |
|---|---|---|
| `:diag` | JVM-библиотека | журнал и диагностика |
| `:core` | JVM-библиотека | протокол, сервер приёмника, форматы, Библия, AirPlay, обновления; вшивает `CHANGELOG.md` как ресурс `changelog.md` |
| `:desktop` | приложение (`application`) | Deixis для компьютера; подключает исходники `pult/src/main/kotlin`; главный класс `app.deixis.desktop.MainKt`, имя запуска `deixis` |
| `:android:receiver` | Android-библиотека | экран на Android; флаг `BuildConfig.AIRPLAY` (выключается `-Pdeixis.store=play`) |
| `:android:phone` | Android-приложение `app.deixis.phone` | универсальная версия (пульт и экран), подключает `pult/src/main/kotlin` |
| `:android:tv` | Android-приложение `app.deixis.tv` | Deixis TV, только экран |
| `pult/` | не модуль, общий каталог исходников | пульт на Compose для телефона и компьютера |
| `windows-display/` | C++ (MSBuild, WDK) | драйвер виртуального монитора Deixis Display и помощник `deixis-display.exe` |

---

## 2. Команды

### 2.1. Модульные тесты

```sh
./gradlew :diag:test
./gradlew :core:test                                  # сам ставит LC_ALL=C.UTF-8 тестам
LC_ALL=C.UTF-8 ./gradlew :desktop:test                # окна без -Ddeixis.gui=1 пропускаются
./gradlew :android:phone:testDebugUnitTest            # нужен Android SDK
./gradlew :android:receiver:testDebugUnitTest         # задача есть, но тестов в модуле нет (0 файлов)
```

Как в CI (ядро и компьютер, без Android):

```sh
DEIXIS_SKIP_ANDROID=1 ./gradlew :diag:test :core:test :desktop:test :desktop:distZip -Ddeixis.outputsPng=$PWD/build/frames --stacktrace
```

Полезные системные свойства тестов (передаются через `-D…` в команду Gradle):

| Свойство | Модуль | Что делает |
|---|---|---|
| `deixis.gui=1` | desktop | включает проверки настоящих окон; каждая проверка — в своём процессе (`forkEvery = 1`) |
| `deixis.outputsPng=папка` | desktop | сохраняет кадры раскладок мониторов, пульта, подложки в PNG |
| `deixis.trayPng=папка` | desktop | PNG значка в трее |
| `deixis.dumpStream=файл` | core | сохраняет поток зрителя (fMP4) |
| `deixis.render=папка`, `deixis.renderFile=файл` | core | слайды PPTX в PNG (с `renderFile` включается вывод теста в консоль) |
| `deixis.pptDump`, `deixis.docxPng`, `deixis.docxFile` | core | ручная проверка PPT/DOCX |
| `deixis.compat` | core, desktop, phone | путь к `testdata/compat`; выставляется сборкой сама |

Тест `:core:test` по умолчанию падает по таймауту 120 с (`junit.jupiter.execution.timeout.default`).
Сводка упавших тестов из XML-отчётов: `python3 tools/test-failures.py`.

### 2.2. Проверки окон (GUI)

Классы из задания `desktop-gui` в `.github/workflows/ci.yml`:

`DesktopWindowTest`, `KeyActionTest` (в файле `DesktopWindowTest.kt`), `DesktopUpdatesTest`, `VideoFitGuiTest`,
`BackdropGuiTest`, `OverlayGuiTest`, `ScreenCaptureTest`, `MacVirtualScreenTest` (в файле `ScreenCaptureTest.kt`),
`PultWindowTest`. Отдельно и **последним** — `OverlayWindowGuiTest`: окно поверх всех и щелчок сквозь него
не должны влиять на остальные проверки.

Linux (нужен `xvfb-run`; для видео — `vlc` и `ffmpeg`):

```sh
tests=(--tests '*DesktopWindowTest*' --tests '*KeyActionTest*' --tests '*DesktopUpdatesTest*' --tests '*VideoFitGuiTest*' \
       --tests '*BackdropGuiTest*' --tests '*OverlayGuiTest*' --tests '*ScreenCaptureTest*' --tests '*MacVirtualScreenTest*' --tests '*PultWindowTest*')
DEIXIS_SKIP_ANDROID=1 LC_ALL=C.UTF-8 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :desktop:test -Ddeixis.gui=1 "${tests[@]}"
DEIXIS_SKIP_ANDROID=1 LC_ALL=C.UTF-8 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :desktop:test -Ddeixis.gui=1 --tests '*OverlayWindowGuiTest*'
```

На macOS и Windows — те же команды без `xvfb-run`. Без экрана проверки помечаются пропущенными
(`assumeTrue(deixis.gui == "1" && !headless)`); CI считает пропуск `DesktopWindowTest` ошибкой.
`MacVirtualScreenTest` осмыслен только на macOS (CGVirtualDisplay).

### 2.3. Приложение для компьютера

```sh
DEIXIS_SKIP_ANDROID=1 ./gradlew :desktop:installDist      # → desktop/build/install/deixis/
./gradlew :desktop:distZip                                # → desktop/build/distributions/*.zip
DEIXIS_VERSION=0.10.33 ./gradlew :desktop:installDist     # версия внутри программы (иначе 0.1.0)
```

Запуск (`desktop/build/install/deixis/bin/deixis`; на Windows — `deixis.bat`):

```sh
bin/deixis                                     # = receive: приёмник; при первом запуске — выбор роли
bin/deixis receive --role both --data /tmp/deixis-data
bin/deixis receive --windowed --screen 2 --name "Зал"
bin/deixis list --seconds 5                    # приёмники в сети
bin/deixis send --to 192.168.1.23 --pin 1234 film.mkv
bin/deixis send --to 127.0.0.1:47820 --pin 1234 --text "Проверка связи"
bin/deixis dlna                                # телевизоры DLNA в сети
bin/deixis help
```

Ключи `receive` (`desktop/.../Main.kt`): `--name`, `--port`, `--screen N`, `--windowed`, `--operator` /
`--no-operator`, `--role remote|screen|both`, `--data ПАПКА` (по умолчанию `~/.deixis/receiver`; там же
`log.txt`), `--snapshots ПАПКА`. Если задан любой из `--screen`, `--windowed`, `--operator`, `--no-operator`,
окно выбора роли не показывается. `--data` и `--snapshots` в тексте `help` не описаны.
Полный список `send` (`--bible`, `--stream`, `--lower`, `--ticker`, `--mode`, `--video` …) — в `USAGE` в `Main.kt`.

### 2.4. Android

```sh
./gradlew :android:phone:assembleDebug :android:tv:assembleDebug
./gradlew :android:phone:assembleRelease :android:tv:assembleRelease      # R8 + shrinkResources
./gradlew -Pdeixis.store=play :android:phone:assembleRelease              # без приёма AirPlay (сборка для Google Play)
```

- Выход: `android/{phone,tv}/build/outputs/apk/{debug,release}/`. Включены ABI-сплиты: `armeabi-v7a`,
  `arm64-v8a`, `x86`, `x86_64` и общий `universal`.
- Версия: `DEIXIS_VERSION` (см. раздел 6), без неё `0.1.0`.
- Подпись release: если задан `DEIXIS_KEYSTORE_FILE` (существующий файл) — ключ из
  `DEIXIS_KEYSTORE_PASSWORD`, `DEIXIS_KEY_ALIAS`, `DEIXIS_KEY_PASSWORD`, схемы v1+v2; иначе release
  подписывается отладочным ключом (годится только для проверки на эмуляторе).
- Правила R8: `android/proguard-deixis.pro`.

### 2.5. Веб-демо

```sh
python3 tools/demo/make_assets.py     # материалы → tools/demo/media/ (нужны python-pptx, Pillow, numpy, LibreOffice, pdftoppm, ffmpeg)
python3 tools/demo/build.py           # → build/demo/ (только стандартная библиотека)
python3 -m http.server -d build/demo  # посмотреть локально (не часть репозитория, просто пример)
```

Публикует workflow `demo.yml` (раздел 3.6).

### 2.6. Снимки интерфейса и проверка на эмуляторе

Нужен запущенный эмулятор или устройство (`adb`), команды из корня репозитория:

```sh
./gradlew :android:phone:assembleDebug && ./tools/android-shots.sh [имя:вкладка ...]    # → shots/, shots/view/
./gradlew :android:phone:assembleRelease :android:tv:assembleRelease :android:tv:assembleDebug :desktop:installDist
DEIXIS_CLI=$PWD/desktop/build/install/deixis/bin/deixis ./tools/android-smoke.sh         # → smoke/
APK_DIR=out ./tools/android-smoke.sh                                                      # проверить готовые APK выпуска
```

Снимки из CI: `git fetch origin refs/ci/ui-shots` (раздел 3.4). Кадры раскладок компьютера:
`-Ddeixis.outputsPng=папка` (артефакт `frames` в CI).

---

## 3. Workflows (`.github/workflows/`)

Общее: все задания на Ubuntu/macOS/Windows используют `actions/checkout@v5`, Temurin 21,
`gradle/actions/setup-gradle@v4` (кроме `demo.yml` и `windows-display.yml`).
Эмулятор Android везде: `reactivecircus/android-emulator-runner@v2`, `x86_64`, `target: default`,
`-no-window -gpu swiftshader_indirect -noaudio -no-boot-anim`, анимации выключены, KVM открывается правилом udev.

### 3.1. `ci.yml` — CI

- **Триггеры:** каждый `push` и `pull_request` (без фильтра путей). `concurrency: ci-<ref>`, прежний запуск отменяется.
- **Секреты:** не используются.

| Задание | ОС | Что делает | Артефакты |
|---|---|---|---|
| `core-desktop` «Ядро и компьютер» | ubuntu-latest, `DEIXIS_SKIP_ANDROID=1` | `:diag:test :core:test :desktop:test :desktop:distZip -Ddeixis.outputsPng=build/frames`; при падении — `tools/test-failures.py` | `frames` (всегда), `test-reports` (при падении), `deixis-desktop` (zip) |
| `desktop-gui` «Окна компьютера» | матрица macOS / Windows / Linux, `fail-fast: false` | ставит VLC и ffmpeg (apt / choco / brew), запускает GUI-классы (2.2), затем `OverlayWindowGuiTest`; ошибка, если `DesktopWindowTest` пропущен | `gui-reports-<ОС>` (при падении) |
| `android` «Android (телефон и ТВ)» | ubuntu-latest (SDK раннера) | `:android:phone:testDebugUnitTest :android:phone:assembleDebug :android:tv:assembleDebug --continue` | `deixis-android` (debug APK) |

Таймауты не заданы (по умолчанию GitHub — 360 мин).

### 3.2. `smoke.yml` — Android smoke

- **Триггеры:** `workflow_dispatch`; `push` по путям `android/**`, `core/src/main/**`, `pult/**`,
  `.github/workflows/smoke.yml`, `tools/android-smoke.sh`.
- **Задание `smoke`:** матрица API 26, 30, 33 (Android 8, 11, 13), `timeout-minutes: 40`.
  Сборка `:android:phone:assembleRelease :android:tv:assembleRelease :android:tv:assembleDebug :desktop:installDist`
  (без ключа — release с отладочной подписью), затем на эмуляторе
  `DEIXIS_CLI=…/bin/deixis ./tools/android-smoke.sh`:
  - ставит universal-APK, запускает `app.deixis.tv/.MainActivity`, `app.deixis.phone/.MainActivity`,
    `app.deixis.phone/.ScreenActivity`, ждёт 20 с, проверяет, что процесс жив, снимает экран;
  - у телефона проходит вкладки «Библия», «Библиотека», «Сетлисты», «Устройства», «Показ», «Настройки» и «Назад»
    (названия на любом языке через `tools/ui_names.py`);
  - у ТВ `net_check`: `adb forward 47820`, `/info` по HTTP, затем по TLS-порту (`tlsPort`), затем `deixis send`
    с PIN, найденным на экране, — слайд «Проверка связи»; для сравнения то же на debug-сборке без R8;
  - собирает logcat и буфер падений; любая ошибка — код выхода 1.
- **Артефакты:** `android-smoke-<api>` (папка `smoke/`, всегда).
- **Секреты:** нет.

### 3.3. `desktop-smoke.yml` — запуск программы для компьютера

- **Триггеры:** `workflow_dispatch`; `push` по путям `desktop/**`, `pult/**`, `core/**`,
  `.github/workflows/desktop-smoke.yml`.
- **Секреты:** нет. Программа собирается `jpackage --type app-image` так же, как для установщика.

**Задание `run`** (матрица Windows на `windows-2022`, macOS на `macos-latest`; `timeout-minutes: 30`):

- Сборка `DEIXIS_SKIP_ANDROID=1 ./gradlew :desktop:installDist`.
- *Windows:* `windows-display/build.ps1` собирает драйвер и помощник; `out` кладётся в app-image как
  `deixis-display` с `-Ddeixis.display=$APPDIR\..\deixis-display`. Запуск `out\Deixis\Deixis.exe receive --role both --data …`
  без консоли (как из «Пуск»), ожидание 60 с, затем:
  - список видимых окон через `EnumWindows` — ошибка, если нет заголовка `Deixis — (пульт|remote|pult)`;
  - `Screen.AllScreens.Count` должно быть ≥ 2 («Виртуального монитора нет в системе»);
  - в `log.txt` должна быть строка `зал — на виртуальном мониторе`;
  - повторный запуск той же командой (должен открыть пульт работающей программы), журнал — в `log-after.txt`;
  - уборка: процессы останавливаются, `deixis-display.exe remove`.
  Снимок экрана — `smoke/windows.png`.
- *macOS:* `out/Deixis.app/Contents/MacOS/Deixis receive --role both --data …`, ожидание 60 с, `screencapture`;
  проверка строки `Окно пульта: открыто` в `log.txt`; если её нет — `jstack` всех процессов Deixis в журнал задания.
- **Артефакты:** `desktop-smoke-Windows`, `desktop-smoke-macOS` (`smoke/`, всегда).

**Задание `linux`** (`ubuntu-24.04`, `timeout-minutes: 30`):

- Ставит `xserver-xorg-core`, `xserver-xorg-video-dummy`, `x11-xserver-utils`, `openbox`, `wmctrl`;
  запускает `Xorg :5` с `tools/smoke/xorg-dummy.conf` (драйвер `dummy`, `VideoRam 256000`, режим 1920x1080,
  `Virtual 8192 4096`: выход `DUMMY0` с монитором и свободные `DUMMY1…15`) и `openbox`.
- Проектор «повтором»: `DUMMY1` включается 1920x1080 в позиции `0x0`, поверх `DUMMY0`.
- С `DISPLAY=:5` и `LC_ALL=C.UTF-8` запускает `bin/deixis receive --role both --data …`, ждёт 30 с, проверяет:
  `Окно пульта: открыто` и `зал — на виртуальном мониторе` в журнале, `Monitors: 3` в `xrandr --listmonitors`
  (Deixis включил свободный выход как виртуальный монитор и вывел зал туда).
- `pkill -TERM`, 8 с, затем `Monitors: 2`: виртуальный монитор убран при выходе.
- **Артефакт:** `desktop-smoke-Linux` (журнал, `monitors.txt`, `monitors-after.txt`).

### 3.4. `ui-shots.yml` — снимки интерфейса

- **Триггеры:** `workflow_dispatch` с вводом `extra` («имя:вкладка» через пробел); `push` по путям
  `android/phone/**`, `android/receiver/**`, `.github/workflows/ui-shots.yml`, `tools/android-shots.sh`.
- **Права:** `contents: write`. **Секрет:** `GITHUB_TOKEN` (как `TOKEN`).
- **Задание `shots`** (`timeout-minutes: 40`): `:android:phone:assembleDebug`, эмулятор API 33,
  `./tools/android-shots.sh <extra>` — сцены телефона, экрана 9:16, планшета 2560x1600, режим «Пульт Holyrics»,
  профили и дополнительные сцены.
- **Результат:** уменьшенные JPEG из `shots/view` принудительно пушатся в служебную ссылку `refs/ci/ui-shots`
  (не ветка, перезаписывается каждым запуском). Полные PNG — артефакт `ui-shots`.

### 3.5. `release.yml` — выпуск

**Триггеры:** `push` метки `v*`; `workflow_dispatch` с вводами:

| Ввод | По умолчанию | Смысл |
|---|---|---|
| `version` | `""` | версия, например `0.10.34` (с `v` или без) |
| `publish` | `false` | опубликовать выпуск; без галочки собираются те же файлы, но выпуска нет (проверка) |

`env`: `VLC_VERSION=3.0.21`; `RELEASE_VERSION` = ввод `version`, иначе имя метки; `PUBLISH` = true для метки
или при `publish`. Права: `contents: write`.

**Секреты (только имена):** `DEIXIS_KEYSTORE_BASE64`, `DEIXIS_KEYSTORE_PASSWORD`, `DEIXIS_KEY_ALIAS`,
`DEIXIS_KEY_PASSWORD`; `GITHUB_TOKEN` (неявно в `softprops/action-gh-release`).
Создать ключ и секреты на Windows помогает `tools/create-signing-key.bat` (раздел 5).

Задания:

1. **`android` «APK телефона и ТВ»** (ubuntu, 30 мин).
   - `DEIXIS_VERSION=${RELEASE_VERSION#v}` в окружение.
   - Ключ: `DEIXIS_KEYSTORE_BASE64` декодируется во временный `.jks` (`DEIXIS_KEYSTORE_FILE`). Нет секрета и
     `PUBLISH=true` — ошибка; нет секрета без публикации — предупреждение, APK подписаны отладочным ключом.
   - «Проверка ключа»: `keytool -list` с паролем и с алиасом; понятная ошибка (лишний пробел в пароле,
     пароль не подходит, нет алиаса — ожидается `deixis`), значения не печатаются.
   - Сборка: `:core:test :android:phone:testReleaseUnitTest :android:phone:assembleRelease :android:tv:assembleRelease --continue`
     (сначала тесты, в том числе совместимость данных прошлых версий).
   - Переименование в `out/`: `Deixis-<v>-<abi>.apk` и `Deixis-TV-<v>-<abi>.apk`, где `abi` ∈
     `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, `all` (из `universal`). Временный ключ удаляется.
   - Артефакт `android`.
2. **`smoke` «Проверка APK на Android N»** (needs `android`, матрица API 26 и 33, 40 мин): скачивает артефакт
   `android` в `apk/` и запускает `tools/android-smoke.sh` с `APK_DIR=apk` — проверяются ровно файлы выпуска
   (`Deixis-TV-*-all.apk`, `Deixis-[0-9]*-all.apk`). `DEIXIS_CLI` здесь не задан, поэтому показ с пульта по PIN
   не проверяется (только запуск, вкладки, `/info` по HTTP и TLS). Артефакты `release-smoke-<api>`.
3. **`desktop` «Установщик …»** (матрица; таймаут не задан):
   - Windows `windows-2022` → `.msi` (на этом образе есть Driver Kit); macOS `macos-latest` → `.dmg`;
     Linux `ubuntu-latest` → `.deb`.
   - `APP_VERSION` для пакета: версия без хвоста после `-`, по умолчанию `0.1.0`; на macOS `0.Y.Z` превращается
     в `1.0.(Y*100+Z)` (macOS не принимает версию с нулём в начале). Внутрь программы идёт настоящая версия
     через `DEIXIS_VERSION` при `:desktop:installDist`.
   - Windows: VLC `win64.zip` (`libvlc.dll`, `libvlccore.dll`, `plugins`) → `content/vlc`,
     `-Djna.library.path=$APPDIR\..\vlc`; WiX 3 (ставится через choco, если нет); драйвер
     `windows-display/build.ps1` → `content/deixis-display` и `-Ddeixis.display=…`; `--win-menu --win-shortcut
     --win-dir-chooser --win-upgrade-uuid 6f3c1a52-8d0b-4c1e-9a77-2b5e4d1c9f10`, значок `desktop/packaging/deixis.ico`.
   - macOS: VLC `arm64.dmg` → `content/vlc/lib`, `plugins`; `--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED`,
     `deixis.icns`. Файл `.dmg` — для Apple Silicon.
   - Linux: `--linux-package-deps` (libvlc5, плагины VLC, LibreOffice Impress/Writer/Calc, шрифты Carlito,
     Caladea, Liberation), `deixis.png`, `--linux-shortcut`.
   - Артефакты `desktop-Windows`, `desktop-macOS`, `desktop-Linux`.
4. **`release` «Релиз»** — выполняется, если `android` и `smoke` успешны **и** это метка или `publish`.
   Успех `desktop` не обязателен (Mac-машины GitHub иногда не выдаются — выпуск выйдет без `.dmg`).
   - Скачивает только `android` и `desktop-*` (снимки проверки в выпуск не попадают).
   - «Что нового»: из `CHANGELOG.md` берутся строки `- …` раздела `## <v>` (`awk '/^## /{p=($2==v)} p && /^- /'`).
     Раздела нет — ошибка «В CHANGELOG.md нет раздела «## V»». Тело выпуска (`notes.md`): «Что нового в V»,
     затем постоянный текст про обновление и выбор файлов (all / arm64-v8a / armeabi-v7a, TV, msi/dmg/deb).
   - `SHA256SUMS.txt` — `sha256sum` всех файлов.
   - `release.json` — массив из одного объекта в формате ответа GitHub API `GET /repos/…/releases`:
     `tag_name`, `prerelease: false`, `draft: false`, `body`, `assets[]` с `name`, `size`,
     `browser_download_url` (`https://github.com/$GITHUB_REPOSITORY/releases/download/v<v>/<name>`) и
     `digest: "sha256:…"`.
   - `softprops/action-gh-release@v2`: метка `v<v>` на `github.sha`, имя `Deixis v<v>`, `prerelease: false`,
     файлы `files/*`, тело `notes.md`. Параметр `repository` не задан — выпуск публикуется в тот же
     репозиторий, где запущен workflow (`$GITHUB_REPOSITORY`). Это и есть `veracerkof-debug/deixis-app`, где
     выпуски ищут приложения (`GitHubReleases.REPO` в `core/.../Updates.kt`): репозиторий переименован из
     `practice-app-2`, старое имя GitHub перенаправляет (проверено: `301 → deixis-app`). Поэтому `origin`
     рабочей копии и ссылки с `practice-app-2` ведут туда же.

#### Как приложения обновляются по выпускам

- **`core/.../Updates.kt`** — общая логика.
  - Сеть только по действию оператора: режим `MANUAL` («Проверить») или `AUTO` (при запуске и раз в сутки).
  - `GitHubReleases`: сначала `https://api.github.com/repos/<REPO>/releases?per_page=30`; при `IOException`
    (в том числе лимит 60 запросов в час, ответ 403/429) — `https://github.com/<REPO>/releases/latest/download/release.json`,
    это обычная загрузка без лимита API. Поэтому `release.json` повторяет формат ответа API.
  - Отбрасываются черновики и метки, которые не являются версией; берётся самая новая стабильная
    (`prerelease` не предлагается). Версии сравниваются по числам (`parseVersion` убирает `v` и хвост после `-`/`+`).
  - `pickAsset`: телефон — `Deixis-<v>-<abi>.apk`, ТВ — `Deixis-TV-<v>-<abi>.apk` по первому подходящему ABI
    устройства, иначе `all`; Windows — `.msi`, Mac — `.dmg`, Linux — `.deb`. **Имена файлов в `release.yml` и
    `pickAsset` должны совпадать.**
  - Загрузка в `<данные>/updates/update-<v>-<имя>.part`; проверяются размер и SHA-256 (из `digest` ассета или из
    `SHA256SUMS.txt`); без суммы установка отменяется. Сбой загрузки ничего не трогает.
  - «Что нового» в окне обновления — строки `- …` из `body` выпусков между текущей и новой версией;
    после установки — из вшитого `CHANGELOG.md` (`takeWhatsNew`, файл `seen-version`).
  - Пульт управляет обновлением приёмника (ТВ, компьютер) через `GET/POST /api/update`.
- **`desktop/.../DesktopUpdates.kt`** — цель по ОС. Windows: скрипт `update-install.cmd` ждёт выхода,
  `msiexec /i … /passive /norestart`, затем перезапуск `Deixis.exe`. Mac: скрипт монтирует `.dmg`, заменяет
  `Deixis.app` (при сбое возвращает старую), перезапускает; если программа не в доступной для записи папке —
  открывает образ для ручного переноса. Linux: `.deb` открывается `xdg-open` (установщик системы).
  С пульта установка не начинается, пока что-то показывается; в режиме «Автоматически» скачанное ставится при
  выходе (`installOnExit`), без перезапуска. Запуск из сборки разработчика (`installDist`) не определяется как
  установленная программа (`appPath() == null`).
- **`android/receiver/.../ApkUpdates.kt`** — версия из `versionName`, ABI из `Build.SUPPORTED_ABIS`.
  Перед установкой `verify`: тот же пакет, `versionCode` не меньше, тот же ключ подписи. Установка через
  `PackageInstaller`; на Android 12+ без вопроса, если прошлую версию ставил сам Deixis. Версия из Google Play
  (`com.android.vending`) не обновляется. Нужно разрешение «Установка неизвестных приложений».
  `PackageReplacedReceiver` после обновления снова открывает экран, если устройство было экраном.

### 3.6. `demo.yml` — веб-демо на GitHub Pages

- **Триггер:** только `workflow_dispatch`. Права: `contents: read`, `pages: write`, `id-token: write`;
  окружение `github-pages`; `concurrency: pages`. В настройках репозитория Pages → Source должно быть
  «GitHub Actions».
- **Задание `deploy`:** `python3 tools/demo/build.py` → `build/demo`; затем `gh release list/download`
  (`GH_TOKEN = github.token`) по последнему выпуску **этого же репозитория** и короткие копии APK:
  `tv.apk` (TV armeabi-v7a), `tv64.apk` (TV arm64-v8a), `deixis.apk` (arm64-v8a), `deixis32.apk` (armeabi-v7a) —
  встроенные браузеры ТВ плохо переносят перенаправления GitHub. Публикация: `configure-pages@v5`,
  `upload-pages-artifact@v3`, `deploy-pages@v4`. Если в репозитории ещё нет выпуска, шаг APK упадёт.
- Таймаут не задан. Адрес демо в `README.md`: `https://veracerkof-debug.github.io/deixis-app/`.

### 3.7. `windows-display.yml` — виртуальный монитор Windows

- **Триггеры:** `push` по путям `windows-display/**`, `.github/workflows/windows-display.yml`;
  `workflow_dispatch`; `workflow_call` (вызывающих workflow в репозитории нет).
- **Задание `build`** (`windows-2022`, таймаут не задан, секретов нет):
  1. `./windows-display/build.ps1`: MSBuild собирает `driver/DeixisDisplay.vcxproj` (Release x64), `cl`
     собирает помощник `installer/deixis-display.cpp`, `Inf2Cat` делает каталог для 10_X64/RS5/VB.
     Подпись — **одноразовым самоподписанным** сертификатом `CN=Deixis Display` на 10 лет
     (`New-SelfSignedCertificate`, ключ неэкспортируемый): экспорт `DeixisDisplay.cer`, `signtool sign /fd sha256`
     для `.dll`, `.cat`, `deixis-display.exe`, после чего закрытый ключ удаляется. Результат — `windows-display/out/`.
  2. Подготовка: папка журнала драйвера `C:\ProgramData\Deixis\display-log` с правом записи для всех,
     дампы `WUDFHost.exe` в `C:\dumps`, проверщик WDF (`VerifierOn`) для службы `DeixisDisplay`.
  3. `deixis-display.exe install <inf> <cer> --log helper.log`; код выхода 0 и строка `драйвер: установлен`
     в журнале помощника; `deixis-display.exe status`.
  4. До 30 с ждёт монитор с производителем `DXS` в `WmiMonitorID`. Нет — печатает код проблемы устройства,
     `pnputil`, журнал драйвера, разбор дампа `cdb` с символами и падает «Windows не увидела монитор Deixis».
  5. `deixis-display.exe remove` (код 0), список мониторов после удаления.
- **Артефакт:** `deixis-display` (`windows-display/out/`).

---

## 4. Тесты: что есть

| Модуль | Файлов | `@Test` | Основное |
|---|---|---|---|
| `diag` | 1 | 2 | `DiagnosticsTest` |
| `core` | 40 | ~222 | протокол и сквозной показ (`ProtocolTest`, `EndToEndTest`, `ViewerTest`, `WebRemoteTest`, `RelayTest`, `StreamTest`, `UploadErrorTest`); сеть и защита (`DiscoveryTest`, `PeersTest`, `PairingTest`, `TlsTest`, `SecureLinkTest`, `LocalAddressTest`, `LocalOperatorTest`, `DlnaTest`, `ConnectCardTest`); форматы (`PptTest`, `PptxTest`, `DocxTest`, `FileStoreTest`); текст и раскладки (`TextLayoutTest`, `AspectTest`, `VideoFitTest`, `OutputsTest`, `OverlaysTest`, `OverlayMotionTest`, `CountdownTest`); Библия и песни (`BibleTest`, `BibleSearchTest`, `DefaultBiblesTest`, `HolyricsTest`, `HolyricsBibleTest`, `SongTextTest`, `SongFormatsTest`, `TestBibles` — помощник); AirPlay (`AirPlayTest`, `AirPlayServerTest`, `FairPlayTest`); обновления (`UpdatesTest`); совместимость (`CompatTest`) |
| `desktop` | 21 | ~63 | окна и GUI (раздел 2.2); раскладки и мониторы (`OutputsDesktopTest`, `AspectDesktopTest`, `XrandrTest`, `OverlayLayoutTest`, `OverlayMotionDesktopTest`); отрисовка (`SlideRendererTest`, `PdfPagesTest`, `LogoPictureTest`, `LogoStyleDesktopTest`, `TrayIconTest`); `OfficeConverterTest`, `LowLatencyDecoderTest`, `DesktopUpdatesTest`, `CompatDesktopTest` |
| `android/phone` | 6 | 13 | `StringsTest` (переводы), `ContrastTest`, `ProfilesTest`, `StoreReadTest`, `HolyricsImportTest`, `CompatLibraryTest` |
| `android/receiver`, `android/tv`, `pult` | 0 | 0 | нет модульных тестов (пульт проверяется через тесты компьютера и `tools/android-smoke.sh`) |

Инструментальных тестов Android (`androidTest`) нет; поведение на устройстве проверяют эмуляторные
скрипты (`smoke.yml`, `release.yml`, `ui-shots.yml`).

### Совместимость с данными прошлых версий

- `testdata/compat/` — файлы, записанные **кодом самих выпусков**: `library-0.7.0.json`, `library-0.8.0.json`,
  `library-0.9.2.json` (библиотека телефона), `tokens-*.json` (сопряжённые пульты), `monitors-0.9.2.json`
  (мониторы компьютера). Руками не правятся.
- Путь передаётся тестам свойством `deixis.compat`; их читают `CompatTest` (core), `CompatDesktopTest` (desktop),
  `CompatLibraryTest` (phone). Если не читается — CI и выпуск (`:core:test`, `:android:phone:testReleaseUnitTest`)
  не проходят.
- Новые образцы: `tools/compat-fixtures.sh vX.Y.Z` (раздел 5) после выпуска, где менялись сохраняемые данные.

---

## 5. `tools/`

| Скрипт | Назначение | Запуск |
|---|---|---|
| `android-smoke.sh` | запуск APK на эмуляторе: ТВ, телефон, «Стать экраном»; проход вкладок пульта; сеть экрана (`/info` HTTP и TLS, показ по PIN через `DEIXIS_CLI`); logcat и падения → `smoke/` | `[APK_DIR=папка] [DEIXIS_CLI=…/bin/deixis] ./tools/android-smoke.sh` из корня, при подключённом `adb` |
| `android-shots.sh` | снимки пульта: телефон, 9:16, планшет, Holyrics, профили; PNG → `shots/`, JPEG → `shots/view/` (ставит Pillow через pip) | `./tools/android-shots.sh [имя:вкладка …]` после `:android:phone:assembleDebug` |
| `ui_names.py` | модуль: названия кнопок по-русски → [ru, uz, en] из `pult/src/main/kotlin/app/deixis/phone/Strings.kt`; `«А\|B»` — готовый список | импортируется из двух скриптов выше (`sys.path.insert(0, "tools")`) |
| `test-failures.py` | сводка упавших тестов из `**/build/test-results/**/TEST-*.xml` (`::error::`, начало стека, вывод); всегда код 0 | `python3 tools/test-failures.py` из корня |
| `compat-fixtures.sh` | образцы данных выпуска для `testdata/compat`: `git worktree` на метке, временный модуль `fixgen` из `testdata/compat/generator`, классы данных из `Model.kt` того выпуска | `tools/compat-fixtures.sh v0.10.0` |
| `app-icons.py` | значки программы для компьютера (дельта как у Android) → `desktop/packaging/` | `python3 tools/app-icons.py` (Pillow) |
| `overlay-samples.py` | картинки примеров слоя «Поверх показа» → `core/src/main/resources/overlay-samples` | `python3 tools/overlay-samples.py` (Pillow) |
| `create-signing-key.bat` | Windows: `keytool -genkeypair` ключа `deixis-release.jks` (алиас `deixis`, RSA 4096) в «Документах», base64 в буфер обмена, подсказка по четырём секретам `DEIXIS_*` и страница секретов GitHub | двойной щелчок; нужен Temurin JDK в `C:\Program Files\Eclipse Adoptium` |
| `demo/build.py` | веб-демо: страницы из `core/src/main/resources/web` + `shim.js`, `hub.js`, `app.html`, `shell.html` → `build/demo/` | `python3 tools/demo/build.py` |
| `demo/make_assets.py` | демо-материалы (лекция PPTX→PDF→JPEG, конспект, фото, видео) → `tools/demo/media/`; рисует `art.py` | `python3 tools/demo/make_assets.py` (python-pptx, Pillow, numpy, LibreOffice, pdftoppm, ffmpeg) |
| `demo/make_docx.py` | демо-документ Word → `android/phone/src/main/assets/demo/Конспект. Город 15 минут.docx` | `python3 tools/demo/make_docx.py` из корня (путь относительный) |
| `bible/make_modules.py` | встроенные модули Библии MyBible (RST, KJV) из JSON scrollmapper/bible_databases → `core/src/main/resources/bible/` | `python3 tools/bible/make_modules.py папка_с_json` |
| `smoke/xorg-dummy.conf` | конфигурация Xorg с драйвером `dummy` для Linux-задания `desktop-smoke.yml` | `sudo Xorg :5 -config $PWD/tools/smoke/xorg-dummy.conf -noreset &` |

---

## 6. Версии и журнал изменений

- **Одна версия для всех приложений**, пробных сборок нет (`prerelease: false`). Метка — `vX.Y.Z`.
- **Android:** `DEIXIS_VERSION` (без `v`) → `versionName`; `versionCode = X*10000 + Y*100 + Z` (не меньше 1),
  в `android/phone/build.gradle.kts` и `android/tv/build.gradle.kts`. Без переменной — `0.1.0` / 1.
- **Компьютер:** задача `writeVersion` в `desktop/build.gradle.kts` пишет `DEIXIS_VERSION` (или `0.1.0`) в ресурс
  `deixis-version.txt`; программа читает его как `ReceiverApp.APP_VERSION`. Версия пакета `jpackage` —
  `APP_VERSION` из `release.yml` (на macOS `1.0.N`, см. 3.5).
- **`CHANGELOG.md`:** разделы `## X.Y.Z` (сейчас верхний — `## 0.10.33`, всего 38 разделов), под ними строки `- …`.
  - `release.yml` берёт в описание выпуска **только строки, начинающиеся с `- `**, из раздела, где второе слово
    заголовка в точности равно версии. Продолжения пункта на следующей строке в выпуск не попадут.
  - Файл вшит в `core` как `changelog.md`; приложения разбирают его `parseChangelog` (заголовки `## v?X.Y.Z`)
    и показывают «Что нового» после обновления даже без сети.
  - Раздел пишется **до** запуска выпуска, иначе задание `release` падает.

---

## 7. Чек-листы

### Перед отправкой в основную ветку (`ccr-7f6c4278-1t09zq`)

- [ ] `./gradlew :diag:test :core:test` (с Android SDK можно добавить `DEIXIS_SKIP_ANDROID=1`, чтобы не тянуть AGP).
- [ ] `LC_ALL=C.UTF-8 ./gradlew :desktop:test` — если менялись `desktop/` или `pult/`.
- [ ] GUI-проверки (2.2) под `xvfb-run` — если менялись окна, мониторы, слой, видео.
- [ ] С Android SDK: `./gradlew :android:phone:testDebugUnitTest :android:phone:assembleDebug :android:tv:assembleDebug`.
- [ ] Новые строки — во всех трёх языках (`StringsTest`), цвета/размеры — из токенов, новый жест — в `DESIGN.md`.
- [ ] Менялся формат сохраняемых данных — `CompatTest`/`CompatDesktopTest`/`CompatLibraryTest` зелёные.
- [ ] Значимое решение — строкой в `DECISIONS.md`; изменение для пользователей — строкой `- …` в будущий раздел `CHANGELOG.md`.
- [ ] Коммит и сообщение автору; через 5 минут без добавлений — push в `ccr-7f6c4278-1t09zq` (см. `CLAUDE.md`).

### Выпуск

1. Push в основную ветку → зелёный **CI** (`ci.yml`: ядро и компьютер, окна на трёх ОС, Android).
2. Зелёный **Android smoke** (`smoke.yml`, API 26/30/33) — запускается сам при изменениях `android/**`,
   `core/src/main/**`, `pult/**`; иначе — вручную.
3. Зелёный **«Запуск программы для компьютера»** (`desktop-smoke.yml`, Windows/macOS/Linux) — сам при изменениях
   `desktop/**`, `pult/**`, `core/**`; иначе — вручную. При изменениях `windows-display/**` — ещё
   **«Виртуальный монитор Windows»**.
4. В `CHANGELOG.md` есть раздел `## X.Y.Z` со строками `- …`.
5. Actions → **Release** → Run workflow: `version = X.Y.Z`, галочка `publish`. (Или push метки `vX.Y.Z`.)
   При желании сначала без `publish` — проверочная сборка тех же файлов.
6. Дождаться: `android` → `smoke` (API 26, 33) → `release`. Если упал только `desktop` (часто macOS), выпуск
   выйдет без соответствующего установщика — решить, перезапускать ли.
7. Actions → **Demo** → Run workflow: демо обновится и возьмёт APK нового выпуска.
8. Проверить выпуск: файлы `Deixis-X.Y.Z-{all,arm64-v8a,armeabi-v7a,x86,x86_64}.apk`,
   `Deixis-TV-X.Y.Z-…apk`, `.msi`, `.dmg`, `.deb`, `SHA256SUMS.txt`, `release.json`;
   `release.json` открывается по `…/releases/latest/download/release.json`, в нём `tag_name: vX.Y.Z` и у
   каждого файла `digest`; в приложении «Настройки → Обновления» → «Проверить» видит новую версию
   (это проверяет и то, что выпуск лёг в репозиторий `GitHubReleases.REPO`).
9. Если менялись сохраняемые данные — `tools/compat-fixtures.sh vX.Y.Z` и коммит новых файлов в `testdata/compat/`.

---

## Несоответствия, замеченные при сверке

- ~~Репозиторий выпусков~~ — не несоответствие: `practice-app-2` переименован в `deixis-app`, GitHub перенаправляет.
- ~~`ARCHITECTURE.md` о сборке установщиков на каждое изменение~~ — раздел помечен как цель, с перечнем того, что работает.
- `:android:receiver:testDebugUnitTest` существует, но тестов в модуле нет; `ci.yml` его и `:android:tv` тесты не запускает.
- ~~`:desktop:test` не задаёт `LC_ALL=C.UTF-8`~~ — задаёт с 0.10.34, как `:core:test`.
- `DesktopUpdatesTest` в списке GUI-проверок, хотя `deixis.gui` не требует (запускается и в обычном `:desktop:test`).
- `help` программы не упоминает `--data` и `--snapshots`.
- `windows-display.yml` объявляет `workflow_call`, но ни один workflow его не вызывает (`release.yml` и
  `desktop-smoke.yml` собирают драйвер сами через `build.ps1`, без проверки установки).
- В `release.yml` проверка APK идёт без `DEIXIS_CLI`, поэтому показ с пульта по PIN на APK выпуска не проверяется
  (проверяется в `smoke.yml` на сборке из исходников).
- ~~`smoke.yml` не срабатывает на изменения `diag/**` и `desktop/**`~~ — пути добавлены.
- Описание выпуска берёт только строки `- …`, а `parseChangelog` в приложении — все непустые строки раздела:
  многострочный пункт в выпуске и в приложении будет выглядеть по-разному.
