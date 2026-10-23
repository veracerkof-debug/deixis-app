"""Встроенные модули Библии Deixis в формате MyBible: Синодальный перевод (RST) и King James Version (KJV).

Тексты — общественное достояние (Синодальный перевод 1876 г., KJV в редакции 1769 г.); источник JSON —
собрание scrollmapper/bible_databases (MIT): formats/json/RusSynodal.json и formats/json/KJV.json.
В модули идут только 66 канонических книг. Только стандартная библиотека Python.
Запуск: python3 tools/bible/make_modules.py папка_с_json  (результат — core/src/main/resources/bible/)
"""
import json
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "core", "src", "main", "resources", "bible")

# Цвета групп книг, как в модулях MyBible: Закон, история, учительные, пророки, Евангелия, Деяния, послания, Откровение.
LAW, HISTORY, WISDOM, MAJOR, MINOR = "#ccccff", "#ffcc99", "#66ff99", "#ff9fb4", "#ffff99"
GOSPEL, ACTS, PAUL, GENERAL, REV = "#ff6600", "#00ffff", "#ffff00", "#00ff99", "#ff7c80"

# Номер MyBible, ключ в JSON, русские полное и короткое имя, английские полное и короткое имя, цвет.
BOOKS = [
    (10, "Genesis", "Бытие", "Быт", "Genesis", "Gen", LAW),
    (20, "Exodus", "Исход", "Исх", "Exodus", "Exod", LAW),
    (30, "Leviticus", "Левит", "Лев", "Leviticus", "Lev", LAW),
    (40, "Numbers", "Числа", "Чис", "Numbers", "Num", LAW),
    (50, "Deuteronomy", "Второзаконие", "Втор", "Deuteronomy", "Deut", LAW),
    (60, "Joshua", "Иисус Навин", "Нав", "Joshua", "Josh", HISTORY),
    (70, "Judges", "Судьи", "Суд", "Judges", "Judg", HISTORY),
    (80, "Ruth", "Руфь", "Руф", "Ruth", "Ruth", HISTORY),
    (90, "I Samuel", "1-я Царств", "1Цар", "1 Samuel", "1Sam", HISTORY),
    (100, "II Samuel", "2-я Царств", "2Цар", "2 Samuel", "2Sam", HISTORY),
    (110, "I Kings", "3-я Царств", "3Цар", "1 Kings", "1Kgs", HISTORY),
    (120, "II Kings", "4-я Царств", "4Цар", "2 Kings", "2Kgs", HISTORY),
    (130, "I Chronicles", "1-я Паралипоменон", "1Пар", "1 Chronicles", "1Chr", HISTORY),
    (140, "II Chronicles", "2-я Паралипоменон", "2Пар", "2 Chronicles", "2Chr", HISTORY),
    (150, "Ezra", "Ездра", "Езд", "Ezra", "Ezra", HISTORY),
    (160, "Nehemiah", "Неемия", "Неем", "Nehemiah", "Neh", HISTORY),
    (190, "Esther", "Есфирь", "Есф", "Esther", "Esth", HISTORY),
    (220, "Job", "Иов", "Иов", "Job", "Job", WISDOM),
    (230, "Psalms", "Псалтирь", "Пс", "Psalms", "Ps", WISDOM),
    (240, "Proverbs", "Притчи", "Прит", "Proverbs", "Prov", WISDOM),
    (250, "Ecclesiastes", "Екклесиаст", "Еккл", "Ecclesiastes", "Eccl", WISDOM),
    (260, "Song of Solomon", "Песнь песней", "Песн", "Song of Songs", "Song", WISDOM),
    (290, "Isaiah", "Исаия", "Ис", "Isaiah", "Isa", MAJOR),
    (300, "Jeremiah", "Иеремия", "Иер", "Jeremiah", "Jer", MAJOR),
    (310, "Lamentations", "Плач Иеремии", "Плач", "Lamentations", "Lam", MAJOR),
    (330, "Ezekiel", "Иезекииль", "Иез", "Ezekiel", "Ezek", MAJOR),
    (340, "Daniel", "Даниил", "Дан", "Daniel", "Dan", MAJOR),
    (350, "Hosea", "Осия", "Ос", "Hosea", "Hos", MINOR),
    (360, "Joel", "Иоиль", "Иоил", "Joel", "Joel", MINOR),
    (370, "Amos", "Амос", "Ам", "Amos", "Amos", MINOR),
    (380, "Obadiah", "Авдий", "Авд", "Obadiah", "Obad", MINOR),
    (390, "Jonah", "Иона", "Ион", "Jonah", "Jonah", MINOR),
    (400, "Micah", "Михей", "Мих", "Micah", "Mic", MINOR),
    (410, "Nahum", "Наум", "Наум", "Nahum", "Nah", MINOR),
    (420, "Habakkuk", "Аввакум", "Авв", "Habakkuk", "Hab", MINOR),
    (430, "Zephaniah", "Софония", "Соф", "Zephaniah", "Zeph", MINOR),
    (440, "Haggai", "Аггей", "Агг", "Haggai", "Hag", MINOR),
    (450, "Zechariah", "Захария", "Зах", "Zechariah", "Zech", MINOR),
    (460, "Malachi", "Малахия", "Мал", "Malachi", "Mal", MINOR),
    (470, "Matthew", "От Матфея", "Мф", "Matthew", "Matt", GOSPEL),
    (480, "Mark", "От Марка", "Мк", "Mark", "Mark", GOSPEL),
    (490, "Luke", "От Луки", "Лк", "Luke", "Luke", GOSPEL),
    (500, "John", "От Иоанна", "Ин", "John", "John", GOSPEL),
    (510, "Acts", "Деяния", "Деян", "Acts", "Acts", ACTS),
    (520, "Romans", "Римлянам", "Рим", "Romans", "Rom", PAUL),
    (530, "I Corinthians", "1-е Коринфянам", "1Кор", "1 Corinthians", "1Cor", PAUL),
    (540, "II Corinthians", "2-е Коринфянам", "2Кор", "2 Corinthians", "2Cor", PAUL),
    (550, "Galatians", "Галатам", "Гал", "Galatians", "Gal", PAUL),
    (560, "Ephesians", "Ефесянам", "Еф", "Ephesians", "Eph", PAUL),
    (570, "Philippians", "Филиппийцам", "Флп", "Philippians", "Phil", PAUL),
    (580, "Colossians", "Колоссянам", "Кол", "Colossians", "Col", PAUL),
    (590, "I Thessalonians", "1-е Фессалоникийцам", "1Фес", "1 Thessalonians", "1Thess", PAUL),
    (600, "II Thessalonians", "2-е Фессалоникийцам", "2Фес", "2 Thessalonians", "2Thess", PAUL),
    (610, "I Timothy", "1-е Тимофею", "1Тим", "1 Timothy", "1Tim", PAUL),
    (620, "II Timothy", "2-е Тимофею", "2Тим", "2 Timothy", "2Tim", PAUL),
    (630, "Titus", "Титу", "Тит", "Titus", "Titus", PAUL),
    (640, "Philemon", "Филимону", "Флм", "Philemon", "Phlm", PAUL),
    (650, "Hebrews", "Евреям", "Евр", "Hebrews", "Heb", PAUL),
    (660, "James", "Иакова", "Иак", "James", "Jas", GENERAL),
    (670, "I Peter", "1-е Петра", "1Пет", "1 Peter", "1Pet", GENERAL),
    (680, "II Peter", "2-е Петра", "2Пет", "2 Peter", "2Pet", GENERAL),
    (690, "I John", "1-е Иоанна", "1Ин", "1 John", "1John", GENERAL),
    (700, "II John", "2-е Иоанна", "2Ин", "2 John", "2John", GENERAL),
    (710, "III John", "3-е Иоанна", "3Ин", "3 John", "3John", GENERAL),
    (720, "Jude", "Иуды", "Иуд", "Jude", "Jude", GENERAL),
    (730, "Revelation of John", "Откровение", "Откр", "Revelation", "Rev", REV),
]

SPACES = re.compile(r"\s+")
TITLE = re.compile(r"\s*<title[^>]*>(.*?)</title>\s*", re.S)

# Пустой стих в источнике, текст которого слит с предыдущим: (книга, глава, стих) → начало его текста.
SPLITS = {("Psalms", 114, 9): "Буду ходить"}


def clean_rst(t):
    t = SPACES.sub(" ", t).strip()
    t = re.sub(r"<note>(.*?)</note>", r"<n>\1</n>", t, flags=re.S)  # примечание MyBible: на слайд не идёт
    t = re.sub(r"(\w)- (\w)", r"\1-\2", t)  # разорванный дефис: «что- нибудь», «Беф- Авен»
    t = re.sub(r" -(?= |$)", " —", t)  # дефис вместо тире
    return t


def clean_kjv(t):
    t = SPACES.sub(" ", t).strip()
    return re.sub(r"(?<=\w)–(?=\w)", "-", t)  # «Beth–el» → «Beth-el», как в печатной KJV


def load(path):
    with open(path, encoding="utf-8") as f:
        return {b["name"]: b for b in json.load(f)["books"]}


def verses(books, clean, russian):
    rows = []
    for number, key, *_ in BOOKS:
        chapters = sorted(books[key]["chapters"], key=lambda c: c["chapter"])
        moved = None  # надписание псалма, попавшее в конец предыдущей главы
        for ch in chapters:
            vs = sorted(ch["verses"], key=lambda v: v["verse"])
            for i, v in enumerate(vs):
                text = clean(v["text"])
                if not text and (key, ch["chapter"], v["verse"]) in SPLITS:
                    prev = rows[-1]
                    head, sep, tail = prev[3].partition(SPLITS[key, ch["chapter"], v["verse"]])
                    if not sep:
                        raise SystemExit(f"не найдено начало стиха {key} {ch['chapter']}:{v['verse']}")
                    rows[-1] = (*prev[:3], head.strip())
                    text = sep + tail
                if i == 0 and moved:
                    if not text.startswith(moved):
                        text = f"{moved} {text}"
                    moved = None
                if m := TITLE.search(text):
                    if i == len(vs) - 1 and TITLE.sub("", text).strip():
                        moved = m.group(1).strip()
                    text = TITLE.sub(" ", text).strip()
                if russian and number == 230 and v["verse"] == 1:
                    text = text[:1].upper() + text[1:]  # «псалом Давида.» → «Псалом Давида.»
                if not text:
                    raise SystemExit(f"пустой стих: {key} {ch['chapter']}:{v['verse']}")
                rows.append((number, ch["chapter"], v["verse"], text))
    return rows


def write(path, info, books, rows):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE info (name TEXT, value TEXT);
        CREATE TABLE books (book_number NUMERIC, short_name TEXT, long_name TEXT, book_color TEXT);
        CREATE TABLE verses (book_number NUMERIC, chapter NUMERIC, verse NUMERIC, text TEXT);
    """)
    db.executemany("INSERT INTO info VALUES (?, ?)", info)
    db.executemany("INSERT INTO books VALUES (?, ?, ?, ?)", books)
    db.executemany("INSERT INTO verses VALUES (?, ?, ?, ?)", rows)
    db.execute("CREATE UNIQUE INDEX verses_index ON verses (book_number, chapter, verse)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"{os.path.relpath(path, ROOT)}: {len(books)} книг, {len(rows)} стихов, {os.path.getsize(path)} байт")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Запуск: python3 tools/bible/make_modules.py папка_с_RusSynodal.json_и_KJV.json")
    src = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    source = "scrollmapper/bible_databases (MIT), https://github.com/scrollmapper/bible_databases"
    write(
        os.path.join(OUT, "RST.SQLite3"),
        [
            ("description", "Синодальный перевод"),
            ("language", "ru"),
            ("chapter_string", "Глава"),
            ("chapter_string_ps", "Псалом"),
            ("russian_numbering", "true"),
            ("detailed_info", f"Синодальный перевод (1876), общественное достояние. 66 канонических книг. Текст: {source}."),
        ],
        [(n, rs, rl, color) for n, _, rl, rs, _, _, color in BOOKS],
        verses(load(os.path.join(src, "RusSynodal.json")), clean_rst, russian=True),
    )
    write(
        os.path.join(OUT, "KJV.SQLite3"),
        [
            ("description", "King James Version"),
            ("language", "en"),
            ("chapter_string", "Chapter"),
            ("chapter_string_ps", "Psalm"),
            ("russian_numbering", "false"),
            ("detailed_info", f"King James Version (1769), public domain. 66 canonical books. Text: {source}."),
        ],
        [(n, es, el, color) for n, _, _, _, el, es, color in BOOKS],
        verses(load(os.path.join(src, "KJV.json")), clean_kjv, russian=False),
    )


if __name__ == "__main__":
    main()
