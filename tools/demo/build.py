"""Собирает веб-демо Deixis в build/demo/: настоящие страницы пульта и экрана с демо-приёмником вместо сети.

Страницы берутся из core/src/main/resources/web без копирования кода руками: сюда кладутся их копии,
в которые первой вставлен shim.js, а адреса приёмника заменены на файлы демо.
Запуск: python3 tools/demo/build.py  (материалы — python3 tools/demo/make_assets.py)
"""
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
WEB = os.path.join(ROOT, "core", "src", "main", "resources", "web")
OUT = os.path.join(ROOT, "build", "demo")


def patch(text, pairs, name):
    for old, new in pairs:
        if old not in text:
            raise SystemExit(f"{name}: не найдено «{old}» — страница изменилась, демо нужно поправить")
        text = text.replace(old, new)
    return text


def inject(text):
    # Вставка до любого скрипта страницы: она подменяет WebSocket и fetch.
    return text.replace("<style>", '<script src="shim.js"></script>\n<style>', 1)


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(os.path.join(OUT, "assets"))
    remote = open(os.path.join(WEB, "remote.html"), encoding="utf-8").read()
    remote = patch(remote, [
        ('href="/remote/icon.png"', 'href="assets/remote-icon.png"'),
        ('<link rel="manifest" href="/remote/manifest.webmanifest">\n', ""),
        ('url("/view/assets/', 'url("assets/'),
        ("`/view?k=", "`view.html?k="),
        ('"/remote/keepawake.mp4"', '"assets/keepawake.mp4"'),
    ], "remote.html")
    view = open(os.path.join(WEB, "view.html"), encoding="utf-8").read()
    view = patch(view, [
        ('url("/view/assets/', 'url("assets/'),
        ('const url = (path) => path + (path.includes("?") ? "&" : "?") + "k=" + encodeURIComponent(key);',
         'const url = (path) => window.DeixisDemoUrl ? window.DeixisDemoUrl(path) : path;'),
    ], "view.html")
    open(os.path.join(OUT, "remote.html"), "w", encoding="utf-8").write(inject(remote))
    open(os.path.join(OUT, "view.html"), "w", encoding="utf-8").write(inject(view))
    for f in ("GFSDidot-Regular.ttf", "NotoSans-SemiBold.ttf", "GFSDidot-OFL.txt", "NotoSans-OFL.txt", "remote-icon.png", "keepawake.mp4"):
        shutil.copy(os.path.join(WEB, f), os.path.join(OUT, "assets", f))
    for f in ("shell.html", "hub.js", "shim.js"):
        shutil.copy(os.path.join(HERE, f), os.path.join(OUT, f))
    shutil.copytree(os.path.join(HERE, "media"), os.path.join(OUT, "media"),
                    ignore=shutil.ignore_patterns("*.pptx", "*.pdf"))
    for d, _, fs in os.walk(OUT):
        for f in fs:
            print(os.path.relpath(os.path.join(d, f), OUT))


if __name__ == "__main__":
    main()
