"""Картинки и видео демо-набора Deixis: всё рисуется здесь, без чужих фото и картинок.

Пейзажи — хребты по шуму (сложение синусоид разной частоты с острыми гребнями), воздушная дымка
к горизонту, туман, отражение в воде, звёзды и Млечный Путь; световой фон — размытые огни.
Видео — те же огни, плывущие по кругу: 12 секунд, последний кадр переходит в первый без шва.
"""
import math
import subprocess

import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080


def _grad(h, stops):
    """Вертикальный переход цвета по точкам [(доля высоты, (r,g,b))] → массив h×3 в 0..1."""
    y = np.linspace(0, 1, h)
    pos = np.array([s[0] for s in stops])
    cols = np.array([s[1] for s in stops], dtype=float) / 255
    return np.stack([np.interp(y, pos, cols[:, k]) for k in range(3)], axis=1)


def _ridge(w, rng, base, amp, sharp=0.6, octaves=6, scale=1.0):
    """Высота хребта по x (в пикселях от верха): гребни острые, склоны пологие."""
    x = np.linspace(0, 1, w)
    v = np.zeros(w)
    a, f = 1.0, 1.6 * scale
    for _ in range(octaves):
        ph = rng.uniform(0, 2 * math.pi)
        s = np.sin(x * f * 2 * math.pi + ph)
        v += a * ((1 - np.abs(s)) * sharp + s * (1 - sharp))
        a *= 0.5
        f *= 2.1
    v = (v - v.min()) / (np.ptp(v) + 1e-9)
    return base - v * amp


def _layer_mask(h, w, top):
    yy = np.arange(h)[:, None]
    return (yy >= top[None, :]).astype(float)


def _soft(mask, r):
    return np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r)), dtype=float) / 255


def _glow(h, w, cx, cy, r, power=2.0):
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.hypot(xx - cx, yy - cy) / r
    return np.exp(-d ** power)[..., None]


def _grain(img, rng, amount=0.012):
    return np.clip(img + rng.normal(0, amount, img.shape[:2])[..., None], 0, 1)


def _vignette(img, strength=0.35):
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
    return img * (1 - strength * np.clip(d - 0.55, 0, 1) ** 1.5)[..., None]


def _save(img, path, quality=86):
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(path, quality=quality, optimize=True, progressive=True)


def _mountains(img, rng, layers, haze, horizon, w, h):
    """Слои хребтов от дальнего к ближнему: дальние светлее и ближе к цвету неба."""
    n = len(layers)
    for i, layer in enumerate(layers):
        base, amp, color, sharp = layer[:4]
        top = _ridge(w, rng, base * h, amp * h, sharp, scale=0.8 + i * 0.35)
        mask = _soft(_layer_mask(h, w, top), 1.2)[..., None]
        far = layer[4] if len(layer) > 4 else 1 - i / max(1, n - 1)
        col = np.array(color, dtype=float) / 255
        col = col * (1 - far * 0.55) + np.array(haze) / 255 * far * 0.55
        # Чуть светлее к гребню — как свет на склоне.
        yy = np.arange(h)[:, None]
        shade = np.clip((yy - top[None, :]) / (h * 0.25), 0, 1)[..., None]
        layer = col * (1.08 - 0.18 * shade)
        img = img * (1 - mask) + layer * mask
    return img


def sunset(path, seed=7, lake=True):
    """Горы на закате: огненное небо, солнце у хребта, дымка, озеро с отражением."""
    rng = np.random.default_rng(seed)
    w, h = W, H
    horizon = 0.66
    sky = _grad(h, [(0, (24, 26, 64)), (0.28, (92, 48, 102)), (0.45, (214, 96, 84)), (0.58, (250, 170, 96)), (0.66, (255, 214, 150)), (1, (255, 214, 150))])
    img = np.broadcast_to(sky[:, None, :], (h, w, 3)).copy()
    sun_x, sun_y = w * 0.62, h * 0.47
    img = img + _glow(h, w, sun_x, sun_y, h * 0.42, 1.4) * (np.array([1, 0.85, 0.55]) - img) * 0.75
    img = img + _glow(h, w, sun_x, sun_y, h * 0.055, 6) * (np.array([1, 0.97, 0.88]) - img)
    # Облака: рваные вытянутые пряди, снизу подсвечены солнцем, выше — холодные.
    yy, xx = np.mgrid[0:h, 0:w]
    small = rng.random((h // 90, w // 40))
    noise = np.asarray(Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(18)), dtype=float) / 255
    noise = (noise - noise.min()) / (np.ptp(noise) + 1e-9)
    for _ in range(6):
        cy = rng.uniform(0.1, 0.38) * h
        cx = rng.uniform(0.1, 0.9) * w
        bend = 18 * np.sin(xx / rng.uniform(140, 260) + rng.uniform(0, 6))
        band = np.exp(-(((xx - cx) / rng.uniform(380, 700)) ** 4 + ((yy - cy - bend) / rng.uniform(12, 30)) ** 2))
        band = band * np.clip(noise * 1.6 - 0.35, 0, 1)
        lit = np.clip((cy / h - 0.1) / 0.3, 0, 1)
        tint = np.array([1.0, 0.66, 0.5]) * lit + np.array([0.5, 0.36, 0.6]) * (1 - lit)
        img = img * (1 - band[..., None] * 0.6) + tint * band[..., None] * 0.6
    img = np.clip(img, 0, 1)
    layers = [
        (0.50, 0.10, (150, 92, 124), 0.75),
        (0.56, 0.12, (108, 64, 104), 0.7),
        (0.62, 0.11, (70, 42, 78), 0.65),
        (0.68, 0.08, (40, 26, 50), 0.6),
    ]
    img = _mountains(img, rng, layers, (250, 170, 120), horizon, w, h)
    if lake:
        line = int(h * 0.74)
        refl = img[line - (h - line):line][::-1].copy()
        # Рябь: строки сдвигаются по x, отражение темнее и синее.
        out = refl.copy()
        for r in range(out.shape[0]):
            shift = int(6 * math.sin(r * 0.9) * (r / out.shape[0]) + rng.normal(0, 1.2))
            out[r] = np.roll(refl[r], shift, axis=0)
        out = np.asarray(Image.fromarray((out * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.2)), dtype=float) / 255
        depth = np.linspace(0, 1, out.shape[0])[:, None, None]
        out = out * (0.78 - 0.3 * depth) + np.array([0.05, 0.04, 0.12]) * depth
        # Дорожка солнца на воде.
        yy = np.arange(out.shape[0])[:, None]
        xx = np.arange(w)[None, :]
        path_ = np.exp(-((xx - sun_x) / (40 + yy * 0.9)) ** 2) * np.exp(-yy / (out.shape[0] * 0.9))
        streaks = (np.sin(yy * 1.7 + rng.uniform(0, 6)) > 0.55) * path_
        out = out + (path_ * 0.3 + streaks * 0.35)[..., None] * np.array([1, 0.8, 0.5])
        img[line:] = out[: h - line]
        img[line - 1:line + 1] = img[line - 1:line + 1] * 0.85
    img = _vignette(img, 0.45)
    _save(_grain(img, rng, 0.006), path)


def morning(path, seed=11):
    """Утро в горах: прохладное небо, тёплый край у горизонта, туман между хребтами."""
    rng = np.random.default_rng(seed)
    w, h = W, H
    sky = _grad(h, [(0, (96, 142, 196)), (0.35, (168, 196, 222)), (0.55, (238, 222, 206)), (1, (238, 222, 206))])
    img = np.broadcast_to(sky[:, None, :], (h, w, 3)).copy()
    img = img + _glow(h, w, w * 0.3, h * 0.5, h * 0.5, 1.3) * (np.array([1, 0.93, 0.8]) - img) * 0.6
    img = np.clip(img, 0, 1)
    bases = [0.42, 0.5, 0.58, 0.67, 0.78, 0.9]
    cols = [(150, 172, 192), (114, 140, 166), (82, 112, 138), (56, 84, 106), (34, 58, 74), (18, 34, 44)]
    for i, (b, c) in enumerate(zip(bases, cols)):
        img = _mountains(img, rng, [(b, 0.12 - i * 0.008, c, 0.55, max(0.0, 0.8 - i * 0.2))], (220, 226, 232), 0.5, w, h)
        # Туман у подножия каждого хребта: светлая размытая полоса.
        yy = np.arange(h)[:, None]
        fog = np.exp(-((yy - (b + 0.05) * h) / (h * 0.035)) ** 2)
        wav = 0.55 + 0.45 * np.sin(np.linspace(0, 6 * math.pi, w) + rng.uniform(0, 6))[None, :] * rng.uniform(0.3, 0.8)
        fog = (fog * wav)[..., None] * max(0.0, 0.5 - i * 0.09)
        img = img * (1 - fog) + np.array([0.95, 0.95, 0.96]) * fog
    img = _vignette(img, 0.3)
    _save(_grain(img, rng, 0.008), path)


def night(path, seed=23):
    """Звёздная ночь: глубокое небо, Млечный Путь по диагонали, свечение у горизонта, тёмные горы и вода."""
    rng = np.random.default_rng(seed)
    w, h = W, H
    sky = _grad(h, [(0, (5, 8, 22)), (0.45, (14, 22, 52)), (0.66, (40, 46, 92)), (1, (40, 46, 92))])
    img = np.broadcast_to(sky[:, None, :], (h, w, 3)).copy()
    # Млечный Путь: полоса по диагонали из облачного шума.
    yy, xx = np.mgrid[0:h, 0:w]
    d = (xx * 0.42 - yy + h * 0.15) / (h * 0.22)
    small = rng.random((h // 8, w // 8))
    cloud = np.asarray(Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(26)), dtype=float) / 255
    band = np.exp(-d ** 2) * (0.35 + 0.9 * cloud)
    img = img + band[..., None] * np.array([0.42, 0.40, 0.55]) * 0.55
    img = img + np.exp(-d ** 2 * 6)[..., None] * np.array([0.9, 0.75, 0.6]) * 0.12
    # Звёзды: много мелких, гуще в полосе, немного ярких с ореолом.
    n = 5200
    sx = rng.integers(0, w, n)
    sy = rng.integers(0, int(h * 0.7), n)
    keep = rng.random(n) < (0.35 + 0.65 * np.exp(-((sx * 0.42 - sy + h * 0.15) / (h * 0.22)) ** 2))
    sx, sy = sx[keep], sy[keep]
    b = rng.power(6, len(sx)) * 0.9
    stars = np.zeros((h, w))
    stars[sy, sx] = b
    big = np.zeros((h, w))
    bi = rng.choice(len(sx), 70, replace=False)
    big[sy[bi], sx[bi]] = 1
    halo = np.asarray(Image.fromarray((big * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)), dtype=float) / 255 * 14
    star_col = np.where(rng.random((h, w, 1)) < 0.5, np.array([1, 0.95, 0.85]), np.array([0.85, 0.9, 1]))
    img = img + (stars + np.clip(halo, 0, 1) * 0.7)[..., None] * star_col
    img = img + _glow(h, w, w * 0.3, h * 0.72, h * 0.45, 1.6) * np.array([0.35, 0.25, 0.35]) * 0.35
    img = np.clip(img, 0, 1)
    layers = [(0.6, 0.12, (34, 38, 70), 0.75), (0.66, 0.1, (20, 22, 44), 0.7), (0.72, 0.07, (8, 10, 22), 0.6)]
    img = _mountains(img, rng, layers, (60, 64, 110), 0.7, w, h)
    line = int(h * 0.8)
    refl = img[line - (h - line):line][::-1].copy()
    refl = np.asarray(Image.fromarray((refl * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.6)), dtype=float) / 255
    img[line:] = refl[: h - line] * 0.55
    img = _vignette(img, 0.4)
    _save(_grain(img, rng, 0.01), path)


def _bokeh_sprites(rng, n, w, h, warm=True):
    pal = [(255, 196, 120), (255, 150, 90), (250, 214, 160), (230, 120, 140), (255, 236, 200)] if warm else \
        [(150, 190, 255), (190, 160, 255), (120, 220, 230), (230, 230, 255)]
    parts = []
    for _ in range(n):
        r = rng.uniform(14, 90)
        col = np.array(pal[rng.integers(len(pal))], dtype=float) / 255
        size = int(r * 2 + 12)
        yy, xx = np.mgrid[0:size, 0:size] - size / 2
        dist = np.hypot(xx, yy) / r
        # Диск с мягким краем и светлым ободком — как огонь не в фокусе.
        soft = rng.uniform(0.04, 0.25)
        disc = np.clip((1.0 - dist) / soft, 0, 1) * (0.55 + 0.45 * (1 - dist.clip(0, 1)) ** 0.5)
        rim = np.exp(-((dist - 0.93) / 0.06) ** 2) * rng.uniform(0.0, 0.25)
        a = np.asarray(Image.fromarray(((disc * 0.75 + rim) * 255).clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(rng.uniform(1, 6))), dtype=float) / 255
        parts.append(dict(
            sprite=a[..., None] * col, size=size, x=rng.uniform(0, w), y=rng.uniform(0, h),
            ax=rng.uniform(20, 120), ay=rng.uniform(30, 160), ph=rng.uniform(0, 2 * math.pi), alpha=rng.uniform(0.18, 0.55),
            k=int(rng.integers(1, 3)),
        ))
    return parts


def _bokeh_frame(bg, parts, t, w, h):
    img = bg.copy()
    for p in parts:
        cx = p["x"] + p["ax"] * math.sin(2 * math.pi * t * p["k"] + p["ph"])
        cy = p["y"] - p["ay"] * math.sin(2 * math.pi * t + p["ph"] * 0.7)
        s = p["size"]
        x0, y0 = int(cx - s / 2), int(cy - s / 2)
        xa, ya, xb, yb = max(0, x0), max(0, y0), min(w, x0 + s), min(h, y0 + s)
        if xa >= xb or ya >= yb:
            continue
        spr = p["sprite"][ya - y0:yb - y0, xa - x0:xb - x0]
        twinkle = 0.85 + 0.15 * math.sin(2 * math.pi * t * 2 + p["ph"])
        img[ya:yb, xa:xb] = img[ya:yb, xa:xb] + spr * p["alpha"] * twinkle
    return np.clip(img, 0, 1)


def _bokeh_bg(w, h):
    bg = np.broadcast_to(_grad(h, [(0, (22, 12, 26)), (0.6, (48, 22, 30)), (1, (70, 34, 26))])[:, None, :], (h, w, 3)).copy()
    bg = bg + _glow(h, w, w * 0.7, h * 0.85, h * 0.7, 1.4) * np.array([0.35, 0.16, 0.06])
    return np.clip(bg, 0, 1)


def lights(path, seed=31):
    """Мягкий свет: тёплые огни не в фокусе на тёмном — фон для текста."""
    rng = np.random.default_rng(seed)
    w, h = W, H
    parts = _bokeh_sprites(rng, 70, w, h)
    img = _bokeh_frame(_bokeh_bg(w, h), parts, 0.13, w, h)
    img = _vignette(img, 0.4)
    _save(_grain(img, rng, 0.008), path)


def lights_video(path, seconds=12, fps=30, w=1280, h=720, seed=31):
    """Огни плывут по кругу: последний кадр переходит в первый, видео можно ставить фоном под песню."""
    rng = np.random.default_rng(seed)
    parts = _bokeh_sprites(rng, 46, w, h)
    for p in parts:
        p["x"] *= 1
    bg = _vignette(_bokeh_bg(w, h), 0.4)
    n = seconds * fps
    proc = subprocess.Popen([
        "ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-",
        "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-crf", "27", "-preset", "slow",
        "-movflags", "+faststart", "-an", path,
    ], stdin=subprocess.PIPE)
    for i in range(n):
        frame = _bokeh_frame(bg, parts, i / n, w, h)
        proc.stdin.write((frame * 255).astype(np.uint8).tobytes())
    proc.stdin.close()
    if proc.wait() != 0:
        raise RuntimeError("ffmpeg не собрал видео")


def city_map(path, w=1200, h=1080, seed=5):
    """Карта района для обложки лекции: кварталы, улицы, парк, река и круг «15 минут пешком» от дома."""
    from PIL import ImageDraw
    rng = np.random.default_rng(seed)
    img = Image.new("RGB", (w, h), (238, 234, 226))
    d = ImageDraw.Draw(img)
    # Река по кривой.
    pts = [(x, int(h * 0.78 + 60 * math.sin(x / 170))) for x in range(-20, w + 40, 20)]
    d.line(pts, fill=(170, 204, 220), width=70)
    # Кварталы сеткой с лёгким разбросом.
    step = 92
    for gx in range(-1, w // step + 2):
        for gy in range(-1, h // step + 2):
            x0, y0 = gx * step + 14, gy * step + 14
            x1, y1 = x0 + step - 22, y0 + step - 22
            cy = (y0 + y1) / 2
            if abs(cy - (h * 0.78 + 60 * math.sin(((x0 + x1) / 2) / 170))) < 60:
                continue
            r = rng.random()
            col = (176, 206, 160) if r < 0.12 else (214, 206, 194) if r < 0.6 else (204, 196, 186)
            d.rounded_rectangle([x0, y0, x1, y1], radius=6, fill=col)
    # Главные улицы.
    for y in (int(h * 0.36), int(h * 0.58)):
        d.line([(0, y), (w, y)], fill=(250, 248, 244), width=16)
    for x in (int(w * 0.32), int(w * 0.66)):
        d.line([(x, 0), (x, h)], fill=(250, 248, 244), width=16)
    cx, cy, r = int(w * 0.5), int(h * 0.46), int(h * 0.34)
    over = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    od.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(31, 111, 139, 46), outline=(31, 111, 139, 230), width=6)
    img = Image.alpha_composite(img.convert("RGBA"), over)
    d = ImageDraw.Draw(img)
    # Места: школа, врач, магазин, парк — цветные точки; дом — в центре.
    for (px, py), col in zip([(0.32, 0.25), (0.7, 0.36), (0.42, 0.66), (0.62, 0.62), (0.26, 0.5)],
                             [(217, 130, 59), (196, 72, 72), (31, 111, 139), (82, 150, 92), (217, 130, 59)]):
        x, y = int(px * w), int(py * h)
        d.ellipse([x - 16, y - 16, x + 16, y + 16], fill=(255, 255, 255))
        d.ellipse([x - 11, y - 11, x + 11, y + 11], fill=col)
    d.ellipse([cx - 22, cy - 22, cx + 22, cy + 22], fill=(27, 35, 46))
    d.ellipse([cx - 9, cy - 9, cx + 9, cy + 9], fill=(255, 255, 255))
    img.convert("RGB").save(path, quality=88)
