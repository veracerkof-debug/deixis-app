#!/usr/bin/env python3
"""Картинки для примеров слоя «Поверх показа»: логотипы с прозрачным фоном. Рисуются этим скриптом
(шрифты проекта, без чужой графики); результат лежит в core/src/main/resources/overlay-samples."""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "core/src/main/resources/overlay-samples")
SANS = os.path.join(ROOT, "core/src/main/resources/web/NotoSans-SemiBold.ttf")
MARK = os.path.join(ROOT, "core/src/main/resources/web/GFSDidot-Regular.ttf")
S = 4  # рисуем крупнее и уменьшаем: ровные края


def save(img, name, size):
    img.resize(size, Image.LANCZOS).save(os.path.join(OUT, name), optimize=True)


def deixis():
    w, h = 720, 240
    img = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Знак: треугольник «Δ» в круге — «показ».
    r = h * S // 2 - 8 * S
    cx, cy = h * S // 2, h * S // 2
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, 235))
    t = r * 0.62
    d.polygon([(cx, cy - t), (cx + t * 0.92, cy + t * 0.62), (cx - t * 0.92, cy + t * 0.62)], fill=(16, 20, 24, 255))
    f = ImageFont.truetype(MARK, 120 * S)
    d.text((h * S + 24 * S, h * S // 2), "DEIXIS", font=f, fill=(255, 255, 255, 240), anchor="lm")
    save(img, "deixis.png", (w, h))


def live():
    w, h = 520, 160
    img = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w * S - 1, h * S - 1), radius=h * S // 2, fill=(229, 72, 77, 240))
    d.ellipse((50 * S, 50 * S, 110 * S, 110 * S), fill=(255, 255, 255, 255))
    f = ImageFont.truetype(SANS, 72 * S)
    d.text((140 * S, h * S // 2), "В ЭФИРЕ", font=f, fill=(255, 255, 255, 255), anchor="lm")
    save(img, "live.png", (w, h))


def spring():
    w, h = 560, 320
    img = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w * S - 1, h * S - 1), radius=36 * S, fill=(24, 35, 58, 220), outline=(255, 212, 59, 255), width=6 * S)
    # Три лепестка — просто цветные круги.
    for i, c in enumerate([(255, 212, 59), (61, 139, 253), (229, 72, 77)]):
        x = (w - 182) // 2 * S + i * 56 * S
        d.ellipse((x, 60 * S, x + 70 * S, 130 * S), fill=c + (255,))
    big = ImageFont.truetype(SANS, 64 * S)
    small = ImageFont.truetype(SANS, 40 * S)
    d.text((w * S // 2, 190 * S), "Весенний вечер", font=big, fill=(255, 255, 255, 255), anchor="mm")
    d.text((w * S // 2, 258 * S), "2026", font=small, fill=(255, 212, 59, 255), anchor="mm")
    save(img, "spring.png", (w, h))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    deixis(); live(); spring()
