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
        # Превью файлов и страниц: в демо — файлы демо-приёмника, QR рисует библиотека витрины.
        ("const fileUrl = (ref) => LOCAL[ref.sha256] || ",
         "const fileUrl = (ref) => LOCAL[ref.sha256] || window.DeixisDemoUrl(`/view/file/${ref.sha256}`) || "),
        ("const pageUrl = (ref, page, w) => ",
         "const pageUrl = (ref, page, w) => window.DeixisDemoUrl(`/view/page/${ref.sha256}/${page}`) || "),
        ('"/remote/keepawake.mp4"', '"assets/keepawake.mp4"'),
        # Витрина (shell.html) показывает примеры, вызывая пульт напрямую.
        ("</script>\n</body>", """// Витрина симулятора вызывает пульт напрямую: пример становится элементом библиотеки и уходит в зал.
window.showItem = (x) => {
  const type = { pdf: "file", image: "photo", video: "video" }[x.kind];
  const it = D.items.find((i) => i.ref && i.ref.sha256 === x.ref.sha256) || addItem({ id: newId(), type, title: x.ref.name, ref: x.ref, pages: x.pages });
  S.tab = "show"; S.open = it.id; render();
  goLive({ src: srcOf(it), idx: x.index || 0 });
};
window.showText = (text, title) => goLive({ src: { id: null, title: title || t.tText, type: "text", slides: splitSlides(text).map((s) => ({ text: s })), style: null }, idx: 0 });
window.bibleSheet = () => A.tab({ id: "bible" });
</script>
</body>"""),
    ], "remote.html")
    view = open(os.path.join(WEB, "view.html"), encoding="utf-8").read()
    view = patch(view, [
        ('url("/view/assets/', 'url("assets/'),
        ('const url = (path) => path + (path.includes("?") ? "&" : "?") + "k=" + encodeURIComponent(key);',
         'const url = (path) => window.DeixisDemoUrl ? window.DeixisDemoUrl(path, key) : path;'),
    ], "view.html")
    open(os.path.join(OUT, "remote.html"), "w", encoding="utf-8").write(inject(remote))
    open(os.path.join(OUT, "view.html"), "w", encoding="utf-8").write(inject(view))
    for f in ("GFSDidot-Regular.ttf", "NotoSans-SemiBold.ttf", "GFSDidot-OFL.txt", "NotoSans-OFL.txt", "remote-icon.png", "keepawake.mp4"):
        shutil.copy(os.path.join(WEB, f), os.path.join(OUT, "assets", f))
    for f in ("shell.html", "sim.html", "app.html", "hub.js", "shim.js"):
        shutil.copy(os.path.join(HERE, f), os.path.join(OUT, f))
    shutil.copytree(os.path.join(HERE, "media"), os.path.join(OUT, "media"),
                    ignore=shutil.ignore_patterns("*.pptx", "*.pdf"))
    # Логотипы примеров «Поверх показа» — картинки приложения, без копии в демо.
    samples = os.path.join(ROOT, "core", "src", "main", "resources", "overlay-samples")
    for f in os.listdir(samples):
        shutil.copy(os.path.join(samples, f), os.path.join(OUT, "media", f))
    # Обычные страницы с <!doctype> (claude.ai добавляет его к главной странице сам):
    # index.html — симулятор приложения, webremote.html — веб-пульт для iPhone.
    for src, dst in (("sim.html", "index.html"), ("shell.html", "webremote.html")):
        body = open(os.path.join(HERE, src), encoding="utf-8").read()
        open(os.path.join(OUT, dst), "w", encoding="utf-8").write(
            '<!doctype html>\n<html lang="ru">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            '<link rel="icon" href="assets/remote-icon.png">\n</head>\n<body>\n' + body + '\n</body>\n</html>\n')
    for d, _, fs in os.walk(OUT):
        for f in fs:
            print(os.path.relpath(os.path.join(d, f), OUT))


if __name__ == "__main__":
    main()
