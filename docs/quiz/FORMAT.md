# Формат файла викторины `*.deixis-quiz.json`

Версия формата: **1**. Модель — `core/.../quiz/Quiz.kt`, проверка — `QuizValidator`, проверка ответов — `grade()`
и `QuizRun` (`QuizGrading.kt`). Для нейросетей есть короткая инструкция — [FOR_AI.md](FOR_AI.md).

## Принципы

- Файл — **данные**, не код. Скриптов, HTML и ссылок на сеть в нём нет и быть не может: такой файл безопасно
  открыть и показать на телефонах зрителей.
- UTF-8, обычный JSON. Расширение — `.deixis-quiz.json`.
- **Совместимость.** Новые необязательные поля версию не меняют: старый Deixis их пропускает (проверка пишет
  предупреждение «неизвестное поле»). `version` растёт только при несовместимой смене; файл более новой версии
  отвергается понятной ошибкой, а не читается наполовину.
- Размеры: файл ≤ 32 МБ, картинка ≤ 2 МБ, вопросов ≤ 200, вариантов 2–8 (см. константы `QuizValidator`).

## Структура

| Поле | Тип | Заметки |
|---|---|---|
| `format` | строка | всегда `"deixis-quiz"` |
| `version` | число | 1 |
| `title` | строка | обязательно, до 200 знаков |
| `language` | строка | `ru` / `uz` / `en`, язык содержимого |
| `description` | строка | |
| `settings` | объект | `passPercent`, `shuffleQuestions`, `shuffleOptions`, `showExplanations`, `defaultTimeLimitSec` |
| `assets` | объект | id → `{ "mime": "image/png", "data": "<base64>" }`; png, jpeg, webp, gif (SVG нельзя) |
| `questions` | массив | обязательно, 1–200 |

Вопрос: `id`, `type`, `text`, `image` (id из `assets`), `points`, `timeLimitSec`, `explanation`, `options`, `answer`, `scoring`.
Вариант: `id`, `text`, `correct`, `image`.

## Типы вопросов

| Тип | Что нужно | Ответ ученика |
|---|---|---|
| `single` | 2–8 вариантов, один `correct` | один id варианта |
| `multiple` | 2–8 вариантов, один и больше `correct` | набор id |
| `truefalse` | `answer: true/false` | `"true"` или `"false"` |

Названы заранее, но пока не работают (проверка отвечает `unsupported_type`): `fill` (пропуски), `order`,
`match`, `input`, `poll`, `wordcloud`. Добавление типа — новое значение `type` и свои поля у вопроса, без смены `version`.

## Очки

`single` и `truefalse`: `points` за верный ответ, иначе 0. `multiple` при `scoring: "all"` (по умолчанию) —
`points` только за точный набор; при `"partial"` — `points × (верных выбрано − неверных выбрано) / верных всего`,
округляя вниз, не меньше 0. Чужие id в ответе игнорируются. Баллы за скорость — в живой игре (шаг 4 плана).

## Коды замечаний проверки

Ошибки (файл не открывается): `file_too_big`, `bad_json`, `bad_structure`, `wrong_format`, `newer_version`,
`bad_version`, `empty_title`, `title_too_long`, `bad_time`, `bad_percent`, `bad_id`, `bad_mime`, `bad_base64`,
`asset_too_big`, `assets_too_big`, `no_questions`, `too_many_questions`, `duplicate_id`, `empty_text`,
`text_too_long`, `bad_points`, `missing_asset`, `no_answer`, `bad_option_count`, `no_correct`, `many_correct`,
`bad_scoring`, `unsupported_type`, `unknown_type`.
Предупреждения (файл открывается): `unknown_key`, `ignored_options`, `one_correct`, `all_correct`.
У каждого замечания есть путь вида `questions[2].options[1].text`.
