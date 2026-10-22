# ΔΕΙΞΙΣ · Deixis

Презентации, слайды, видео, фото и текст на любом экране: Android TV, мобильный проектор, интерактивная доска, компьютер. Телефон — пульт и дублёр показа: файл до 1 ГБ отправляется на экран и играется там, а всё остальное показывается демонстрацией экрана телефона. Зрители без приложения смотрят показ в браузере по QR-коду.

Приложение светское. Для церквей есть дополнительный раздел: Библия из модулей MyBible, а позже песни и сетлисты.

- **Deixis** — Android-телефон: библиотека, предпросмотр, управление, вывод по кабелю.
- **Deixis TV** — Android TV, приставки, проекторы и доски на Android.
- **Deixis для компьютера** — Windows, macOS, Linux.

Без рекламы, аналитики и трекеров. Код открытый, лицензия GPL-3.0-or-later (см. `LICENSE` и `NOTICE`).

| Документ | О чём |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | приложения, технологии, сеть, форматы, стабильность, проверка, этапы |
| [SCREENS.md](SCREENS.md) | экраны и роли |
| [DESIGN.md](DESIGN.md) | дизайн-код |
| [DECISIONS.md](DECISIONS.md) | журнал решений |
| [prototype/index.html](prototype/index.html) | кликабельный прототип; `#tv` в адресе — экран приёмника |

## Сборка и запуск

Нужна Java 17 или новее. Для видео на компьютере нужен установленный VLC 3.

```sh
./gradlew :core:test :desktop:test      # тесты
./gradlew :desktop:installDist          # приложение для компьютера
desktop/build/install/deixis/bin/deixis                     # приёмник во весь экран (выход — Ctrl+Q)
desktop/build/install/deixis/bin/deixis list                # приёмники в сети
desktop/build/install/deixis/bin/deixis send --to 192.168.1.23 --pin 1234 film.mkv
```

Android-приложения собираются там, где есть Android SDK (Android Studio или CI):

```sh
./gradlew :android:phone:assembleDebug :android:tv:assembleDebug
```

Готовые APK и архив для компьютера CI выкладывает в артефакты каждой сборки на вкладке Actions.

Сделано при помощи Claude Code (Anthropic).
