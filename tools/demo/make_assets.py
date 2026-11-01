"""Демо-материалы Deixis (телефон и веб-демо): лекция (PPTX → PDF через LibreOffice → страницы JPEG), конспект Word
(страницы — свой перевод DOCX из ядра), фото и видео.

Всё рисуется здесь же, без чужих картинок: python-pptx, Pillow, numpy, LibreOffice, pdftoppm, ffmpeg.
Запуск: python3 tools/demo/make_assets.py  (результат — tools/demo/media/)
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np

import art
from PIL import Image, ImageDraw, ImageFilter
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION
from pptx.enum.text import PP_ALIGN
from pptx.util import Emu, Pt

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "media")
FONT = os.path.join(HERE, "..", "..", "core", "src", "main", "resources", "web", "NotoSans-SemiBold.ttf")

INK = RGBColor(0x1B, 0x23, 0x2E)
MUTED = RGBColor(0x5B, 0x66, 0x73)
ACCENT = RGBColor(0x1F, 0x6F, 0x8B)
WARM = RGBColor(0xD9, 0x82, 0x3B)
PAPER = RGBColor(0xFA, 0xF8, 0xF4)


def lecture(path, art_dir):
    """Лекция: тёмная обложка с картой района, вопросы карточками, диаграмма с выводом, таблица, финал на фото."""
    prs = Presentation()
    prs.slide_width, prs.slide_height = Emu(12192000), Emu(6858000)
    W, H = prs.slide_width, prs.slide_height
    blank = prs.slide_layouts[6]

    def slide(color=PAPER):
        s = prs.slides.add_slide(blank)
        bg = s.background.fill
        bg.solid()
        bg.fore_color.rgb = color
        return s

    def text(s, x, y, w, h, lines, size, color=INK, bold=False, align=PP_ALIGN.LEFT, space=6):
        tb = s.shapes.add_textbox(Emu(x), Emu(y), Emu(w), Emu(h))
        tf = tb.text_frame
        tf.word_wrap = True
        for i, line in enumerate(lines):
            p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            p.alignment = align
            p.space_after = Pt(space)
            r = p.add_run()
            r.text = line
            r.font.size = Pt(size)
            r.font.bold = bold
            r.font.color.rgb = color
            r.font.name = "Noto Sans"
        return tb

    def box(s, x, y, w, h, color, shape=1):
        r = s.shapes.add_shape(shape, Emu(x), Emu(y), Emu(w), Emu(h))
        r.fill.solid()
        r.fill.fore_color.rgb = color
        r.line.fill.background()
        r.shadow.inherit = False
        return r

    def kicker(s, label, y=520000, color=ACCENT):
        box(s, m, y + 90000, 360000, 60000, color)
        text(s, m + 460000, y - 40000, W - 2 * m, 400000, [label], 16, color, bold=True)

    m = 760000
    WHITE = RGBColor(0xFF, 0xFF, 0xFF)
    SOFT = RGBColor(0xB8, 0xC4, 0xCF)

    # 1. Обложка: тёмная половина с заголовком, справа — карта района с кругом «15 минут».
    s = slide(INK)
    s.shapes.add_picture(os.path.join(art_dir, "map.jpg"), Emu(W // 2), 0, Emu(W - W // 2), Emu(H))
    box(s, W // 2 - 60000, 0, 60000, H, WARM)
    text(s, m, 1100000, W // 2 - m - 300000, 500000, ["УРБАНИСТИКА · ЛЕКЦИЯ 3"], 16, RGBColor(0xF2, 0xB2, 0x7A), bold=True)
    text(s, m, 1600000, W // 2 - m - 300000, 2600000, ["Город пятнадцати минут"], 48, WHITE, bold=True)
    text(s, m, 4700000, W // 2 - m - 300000, 1200000, ["Как расстояние до школы, врача и магазина меняет жизнь района"], 20, SOFT)

    # 2. Три вопроса — карточки с номерами.
    s = slide()
    kicker(s, "С ЧЕГО НАЧАТЬ")
    text(s, m, 900000, W - 2 * m, 800000, ["Три вопроса жителя"], 40, INK, bold=True)
    cards = [("1", "Дойти пешком", "До всего нужного — магазин, школа, врач — за четверть часа?", ACCENT),
             ("2", "Идти безопасно", "Тротуары, свет вечером, удобные переходы", WARM),
             ("3", "Есть куда выйти", "Парк, площадь, двор, где хочется остаться", RGBColor(0x52, 0x96, 0x5C))]
    cw = (W - 2 * m - 2 * 300000) // 3
    for i, (n, t, d, c) in enumerate(cards):
        x = m + i * (cw + 300000)
        box(s, x, 2050000, cw, 3900000, WHITE, 5)
        box(s, x, 2050000, cw, 90000, c)
        circ = box(s, x + 380000, 2500000, 760000, 760000, c, 9)
        circ.text_frame.text = n
        para = circ.text_frame.paragraphs[0]
        para.alignment = PP_ALIGN.CENTER
        para.runs[0].font.size = Pt(30)
        para.runs[0].font.bold = True
        para.runs[0].font.color.rgb = WHITE
        text(s, x + 380000, 3500000, cw - 600000, 600000, [t], 22, INK, bold=True)
        text(s, x + 380000, 4150000, cw - 600000, 1600000, [d], 17, MUTED)

    # 3. Диаграмма и вывод крупной цифрой.
    s = slide()
    kicker(s, "ЦИФРЫ")
    text(s, m, 900000, W - 2 * m, 800000, ["Сколько поездок делают пешком"], 40, INK, bold=True)
    data = CategoryChartData()
    data.categories = ["Окраина", "Спальный район", "Центр", "Квартал 15 минут"]
    data.add_series("Доля поездок пешком, %", (12, 21, 38, 54))
    cwid = int((W - 2 * m) * 0.64)
    gf = s.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Emu(m), Emu(1800000), Emu(cwid), Emu(4400000), data)
    ch = gf.chart
    ch.has_legend = False
    ch.has_title = False
    ch.plots[0].has_data_labels = True
    ch.plots[0].data_labels.font.size = Pt(20)
    ch.plots[0].data_labels.font.bold = True
    ch.plots[0].data_labels.font.color.rgb = INK
    ch.plots[0].gap_width = 55
    ch.series[0].format.fill.solid()
    ch.series[0].format.fill.fore_color.rgb = ACCENT
    pt = ch.series[0].points[3]
    pt.format.fill.solid()
    pt.format.fill.fore_color.rgb = WARM
    ch.category_axis.tick_labels.font.size = Pt(15)
    ch.category_axis.tick_labels.font.color.rgb = MUTED
    ch.category_axis.format.line.fill.background()
    ch.value_axis.visible = False
    ch.value_axis.has_major_gridlines = False
    sx = m + cwid + 300000
    box(s, sx, 2000000, W - m - sx, 3800000, INK, 5)
    text(s, sx + 300000, 2300000, W - m - sx - 600000, 1300000, ["×4,5"], 66, RGBColor(0xF2, 0xB2, 0x7A), bold=True)
    text(s, sx + 300000, 3700000, W - m - sx - 600000, 1800000, ["во столько раз чаще ходят пешком в квартале 15 минут, чем на окраине"], 18, SOFT)
    text(s, m, 6280000, W - 2 * m, 400000, ["Условные данные для примера"], 12, MUTED)

    # 4. Таблица.
    s = slide()
    kicker(s, "НОРМЫ")
    text(s, m, 900000, W - 2 * m, 800000, ["Что должно быть рядом"], 40, INK, bold=True)
    rows = [("Место", "Пешком", "Как часто"), ("Магазин", "5 минут", "каждый день"), ("Школа", "10 минут", "каждый день"),
            ("Поликлиника", "15 минут", "раз в месяц"), ("Парк", "10 минут", "каждую неделю")]
    tbl = s.shapes.add_table(len(rows), 3, Emu(m), Emu(1900000), Emu(W - 2 * m), Emu(4000000)).table
    for i, row in enumerate(rows):
        for j, v in enumerate(row):
            c = tbl.cell(i, j)
            c.text = v
            c.fill.solid()
            c.fill.fore_color.rgb = INK if i == 0 else (WHITE if i % 2 else RGBColor(0xF1, 0xEE, 0xE8))
            c.margin_left = c.margin_right = Emu(240000)
            for p in c.text_frame.paragraphs:
                for r in p.runs:
                    r.font.size = Pt(22)
                    r.font.bold = i == 0 or j == 1
                    r.font.name = "Noto Sans"
                    r.font.color.rgb = WHITE if i == 0 else (ACCENT if j == 1 else INK)

    # 5. Итог — на фото гор, текст белым.
    s = slide(INK)
    s.shapes.add_picture(os.path.join(art_dir, "final.jpg"), 0, 0, Emu(W), Emu(H))
    text(s, m, 2100000, W - 2 * m, 1700000, ["Город удобен, когда до нужного — шаги, а не пробки"], 44, WHITE, bold=True)
    text(s, m, 4100000, W - 2 * m, 600000, ["Вопросы и обсуждение"], 24, RGBColor(0xF2, 0xB2, 0x7A), bold=True)
    prs.save(path)


def report(path):
    import docx
    from docx.shared import Pt as DPt
    d = docx.Document()
    d.add_heading("Отчёт по практике", 0)
    d.add_paragraph("Студент 3 курса, направление «Градостроительство». Тема: доступность школ и поликлиник в районе.")
    d.add_heading("Что сделано", 1)
    for line in ("Обследованы 12 кварталов и 46 маршрутов пешком.", "Замерено время до школы, поликлиники и парка.", "Составлена карта мест, куда трудно дойти пешком."):
        d.add_paragraph(line, style="List Bullet")
    d.add_heading("Итог", 1)
    t = d.add_table(rows=4, cols=3)
    t.style = "Light Grid Accent 1"
    for r, row in enumerate((("Место", "Среднее время", "Норма"), ("Школа", "11 минут", "до 10"), ("Поликлиника", "19 минут", "до 15"), ("Парк", "8 минут", "до 10"))):
        for c, v in enumerate(row):
            t.cell(r, c).text = v
    p = d.add_paragraph("Условные данные для примера.")
    p.runs[0].font.size = DPt(9)
    d.save(path)


def konspekt(tmp):
    """Демо-документ Word с телефона (make_docx.py): страницы рисует свой перевод DOCX из ядра, как на телефоне."""
    root = os.path.abspath(os.path.join(HERE, "..", ".."))
    src = os.path.join(root, "android", "phone", "src", "main", "assets", "demo", "Конспект. Город 15 минут.docx")
    out = os.path.join(tmp, "konspekt")
    env = dict(os.environ, LC_ALL="C.UTF-8", DEIXIS_SKIP_ANDROID="1")
    subprocess.run([os.path.join(root, "gradlew"), "-q", ":core:test", "--tests", "*DocxTest.rendersPages", "--rerun",
                    f"-Ddeixis.docxPng={out}", f"-Ddeixis.docxFile={src}"], cwd=root, env=env, check=True)
    pages = sorted(f for f in os.listdir(out) if f.startswith("docx-page-"))
    for i, f in enumerate(pages):
        Image.open(os.path.join(out, f)).convert("RGB").save(os.path.join(OUT, f"konspekt-{i}.jpg"), quality=85)


def final_photo(src, dst):
    """Фон последнего слайда: закат, слева затемнён под белый текст."""
    img = np.asarray(Image.open(src).convert("RGB"), dtype=float) / 255
    h, w = img.shape[:2]
    shade = np.clip(1 - np.linspace(0, 1, w) * 1.1, 0, 1)[None, :, None] * 0.62 + 0.18
    Image.fromarray(((img * (1 - shade)) * 255).astype(np.uint8)).save(dst, quality=86)


# Демо-набор телефона: файл → имя в библиотеке. Тексты песен, Писание и темы — в Controller.seedDemo.
PHONE = os.path.join(HERE, "..", "..", "android", "phone", "src", "main", "assets", "demo")


def main():
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        art_dir = os.path.join(tmp, "art")
        os.makedirs(art_dir)
        art.sunset(os.path.join(art_dir, "sunset.jpg"))
        art.morning(os.path.join(art_dir, "morning.jpg"))
        art.night(os.path.join(art_dir, "night.jpg"))
        art.lights(os.path.join(art_dir, "lights.jpg"))
        art.city_map(os.path.join(art_dir, "map.jpg"), w=960, h=1080)
        final_photo(os.path.join(art_dir, "sunset.jpg"), os.path.join(art_dir, "final.jpg"))
        art.lights_video(os.path.join(art_dir, "lights.mp4"))
        pptx = os.path.join(tmp, "lecture.pptx")
        lecture(pptx, art_dir)
        soffice = shutil.which("soffice") or shutil.which("libreoffice")
        if not soffice:
            sys.exit("Нужен LibreOffice, чтобы перевести лекцию в PDF")
        subprocess.run([soffice, "--headless", f"-env:UserInstallation=file://{tmp}/profile", "--convert-to", "pdf", "--outdir", tmp, pptx],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.copy(os.path.join(tmp, "lecture.pdf"), os.path.join(OUT, "lecture.pdf"))
        shutil.copy(pptx, os.path.join(OUT, "lecture.pptx"))
        subprocess.run(["pdftoppm", "-jpeg", "-jpegopt", "quality=86", "-scale-to-x", "1920", "-scale-to-y", "-1",
                        os.path.join(tmp, "lecture.pdf"), os.path.join(tmp, "p")], check=True)
        for f in os.listdir(OUT):
            if f.startswith("lecture-") and f.endswith(".jpg"):
                os.remove(os.path.join(OUT, f))
        pages = sorted(f for f in os.listdir(tmp) if f.startswith("p-") and f.endswith(".jpg"))
        for i, f in enumerate(pages):
            shutil.copy(os.path.join(tmp, f), os.path.join(OUT, f"lecture-{i}.jpg"))
        print("страниц:", len(pages))
        # Документ для перевода на компьютере: одна страница отчёта.
        docx = os.path.join(tmp, "report.docx")
        report(docx)
        subprocess.run([soffice, "--headless", f"-env:UserInstallation=file://{tmp}/profile", "--convert-to", "pdf", "--outdir", tmp, docx],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["pdftoppm", "-jpeg", "-jpegopt", "quality=86", "-scale-to-x", "1240", "-scale-to-y", "-1", "-f", "1", "-l", "1",
                        os.path.join(tmp, "report.pdf"), os.path.join(tmp, "r")], check=True)
        shutil.copy(os.path.join(tmp, sorted(f for f in os.listdir(tmp) if f.startswith("r-"))[0]), os.path.join(OUT, "report-0.jpg"))
        if os.environ.get("DEIXIS_SKIP_KONSPEKT") != "1":
            konspekt(tmp)
        # Веб-версия.
        for src, dst in (("sunset.jpg", "mountains.jpg"), ("morning.jpg", "morning.jpg"), ("night.jpg", "night.jpg"),
                         ("lights.jpg", "lights.jpg"), ("lights.mp4", "clip.mp4")):
            shutil.copy(os.path.join(art_dir, src), os.path.join(OUT, dst))
        # Телефон: демо-файлы под понятными именами; прежнее «Демо-видео» заменено видеофоном.
        os.makedirs(PHONE, exist_ok=True)
        for old in ("Демо-видео.mp4",):
            if os.path.exists(os.path.join(PHONE, old)):
                os.remove(os.path.join(PHONE, old))
        for src, dst in (("sunset.jpg", "Горы на закате.jpg"), ("morning.jpg", "Утро в горах.jpg"), ("night.jpg", "Звёздная ночь.jpg"),
                         ("lights.jpg", "Тёплые огни.jpg"), ("lights.mp4", "Огни. Видеофон.mp4")):
            shutil.copy(os.path.join(art_dir, src), os.path.join(PHONE, dst))
        shutil.copy(pptx, os.path.join(PHONE, "Лекция. Город 15 минут.pptx"))
        shutil.copy(os.path.join(tmp, "lecture.pdf"), os.path.join(PHONE, "Город пятнадцати минут.pdf"))
    for f in sorted(os.listdir(OUT)):
        print(f, os.path.getsize(os.path.join(OUT, f)))


if __name__ == "__main__":
    main()
