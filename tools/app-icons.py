#!/usr/bin/env python3
"""Значок Deixis для компьютера — тот же, что у приложения на телефоне (android/phone/.../ic_launcher.xml):
белая дельта-контур на чёрном. Поле 108×108, треугольник M54,26 L80,80 L28,80 с вырезом M54,38 L37,74 L71,74.
На Mac и Windows — чёрный скруглённый квадрат с полями, как принято у значков этих систем."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "desktop/packaging")
OUTER = [(54, 26), (80, 80), (28, 80)]
INNER = [(54, 38), (37, 74), (71, 74)]
VISIBLE = 72
CROP = (108 - VISIBLE) / 2


def icon(size, inset=0.1, radius=0.225):
    S = 4
    n = size * S
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = round(n * inset)
    d.rounded_rectangle((m, m, n - m - 1, n - m - 1), radius=round((n - 2 * m) * radius), fill=(0, 0, 0, 255))
    # Android показывает от поля 108 только середину 72×72 (маска значка): дельта занимает ту же долю.
    k = (n - 2 * m) / VISIBLE

    def pt(p):
        return (m + (p[0] - CROP) * k, m + (p[1] - CROP) * k)

    d.polygon([pt(p) for p in OUTER], fill=(255, 255, 255, 255))
    d.polygon([pt(p) for p in INNER], fill=(0, 0, 0, 255))
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    big = icon(1024)
    big.save(os.path.join(OUT, "deixis.png"))
    big.save(os.path.join(OUT, "deixis.icns"))
    # Значок окон и трея, пока программа работает (и без установщика).
    icon(256).save(os.path.join(ROOT, "desktop/src/main/resources/app-icon.png"))
    # Windows: мелкие размеры рисуются отдельно, а не уменьшаются из большого — края чётче.
    sizes = [16, 24, 32, 48, 64, 128, 256]
    imgs = [icon(s, inset=0.1 if s >= 64 else 0.02) for s in sizes]
    imgs[-1].save(os.path.join(OUT, "deixis.ico"), sizes=[(s, s) for s in sizes], append_images=imgs[:-1])
