#!/bin/sh
# Снимки интерфейса пульта на эмуляторе для проверки вида: телефон, вертикальный экран в зале, планшет.
# PNG — в shots/, уменьшенные JPEG — в shots/view/ (их CI кладёт в refs/ci/ui-shots).
mkdir -p shots/view
pkg=app.deixis.phone
apk=$(ls android/phone/build/outputs/apk/debug/*universal*.apk 2>/dev/null | head -1)
[ -z "$apk" ] && apk=$(ls android/phone/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$apk" || exit 1
python3 -m pip install --quiet --user pillow > /dev/null 2>&1

# Касание элемента по тексту или описанию (так Compose показывает их системе); с номером — n-й такой.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml > shots/ui.xml
  xy=$(python3 - "$1" "${2:-1}" shots/ui.xml <<'PY'
import re, sys
import xml.etree.ElementTree as ET
want, nth, path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
k = 0
for n in root.iter("node"):
    if want in (n.get("text"), n.get("content-desc")) or (n.get("content-desc") or "").startswith(want + ":"):
        k += 1
        if k == nth:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)
  if [ -z "$xy" ]; then echo "=== нет на экране: «$1»"; return 1; fi
  adb shell input tap $xy
  sleep 2
}

# Касание элемента ниже края: список листается вверх, пока элемент не появится.
tap_scroll() {
  size=$(adb shell wm size | tail -1 | grep -o "[0-9]*x[0-9]*")
  w=${size%x*}; h=${size#*x}
  for i in 1 2 3 4 5 6; do
    tap "$1" 2>/dev/null | grep -q "нет на экране" || return 0
    adb shell input swipe $((w / 2)) $((h * 70 / 100)) $((w / 2)) $((h * 40 / 100)) 400; sleep 1
  done
  echo "=== нет на экране: «$1»"; return 1
}

# Удержание элемента (меню элемента в сетлисте и библиотеке).
hold() {
  tap_xy "$1" || return 1
  adb shell input swipe $xy $xy 900
  sleep 2
}

tap_xy() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml > shots/ui.xml
  xy=$(python3 - "$1" shots/ui.xml <<'PY'
import re, sys
import xml.etree.ElementTree as ET
want, path = sys.argv[1], sys.argv[2]
root = ET.parse(path).getroot()
for n in root.iter("node"):
    if want in (n.get("text"), n.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$xy" ] || { echo "=== нет на экране: «$1»"; return 1; }
}

shot() {
  adb exec-out screencap -p > "shots/$1.png"
  python3 - "shots/$1.png" "shots/view/$1.jpg" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
k = 1000 / max(im.size)
im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS).save(sys.argv[2], "JPEG", quality=80)
PY
  echo "=== снимок $1"
}

open_app() {
  adb shell am force-stop $pkg
  adb shell am start -W -n "$pkg/$pkg.MainActivity" "$@" > /dev/null
  sleep 6
}

# Песня из демо-сетлиста: раскрыть, слайд 1 — в предпросмотр, «В зал», слайд 2 — в предпросмотр.
show_song() {
  tap "Коль славен наш Господь в Сионе" || return
  tap "Слайд 1" && tap "В зал" && tap "Слайд 2"
}

scene() {
  name=$1; shift
  open_app "$@"
  show_song
  shot "$name"
}

scene phone-16x9
# Оформление во время показа: «Вид».
tap "Вид" && shot phone-style
# Редактор песни: удержание песни в сетлисте → «Править слайды».
open_app
hold "Коль славен наш Господь в Сионе" && tap "Править слайды" && shot phone-song-editor
adb shell input swipe 500 1600 500 600 300; sleep 1; shot phone-song-editor-2
# «Показать» — «+» рядом с поиском: файлы, текст, отсчёт, слой.
open_app
tap "Показать" && shot phone-show-menu
# Презентация листается: «Сейчас» меняет страницу по «Дальше», предпросмотр — по касанию плитки.
open_app
if tap_scroll "Город пятнадцати минут.pdf"; then
  tap "Слайд 1" && tap "В зал" && shot phone-pdf-1
  tap "Дальше" && shot phone-pdf-2
  tap "Слайд 3" && shot phone-pdf-3
fi
# Документ Word из демо: переведён на телефоне, страницы видны в плитках и в предпросмотре.
open_app
if tap_scroll "Конспект. Город 15 минут.docx"; then
  tap "Слайд 1" && shot phone-docx-1
  tap "Слайд 2" && shot phone-docx-2
fi
# Второй экран (как кабель или Миракаст): эмулятор рисует его окном поверх основного.
open_app
show_song
adb shell settings put global overlay_display_devices 1280x720/240
sleep 8
shot phone-cast
# Пульт снова впереди: касания идут ему, а не окну зала.
tap "Дальше" && shot phone-cast-next
adb shell dumpsys activity activities | grep -E "CastActivity|Display #" | head -12
adb logcat -d | grep -iE "AndroidRuntime|deixis|CastActivity|BackgroundActivity|Abort background" | tail -30
# Пульт свёрнут: вывод на втором экране остаётся.
adb shell input keyevent KEYCODE_HOME
sleep 8
shot phone-cast-background
# Библиотека: у фото, видео и PDF — есть ли файл на экране.
open_app
tap "Библиотека" && shot phone-library
adb shell settings put global overlay_display_devices none
sleep 2
scene phone-screen-9x16 --ef deixis.test.ratio 0.5625
adb shell wm size 2560x1600
adb shell wm density 320
scene tablet-16x9
scene tablet-screen-9x16 --ef deixis.test.ratio 0.5625
adb shell wm size reset
adb shell wm density reset
# Дополнительные сцены — из аргументов: имя:вкладка (например library:Библиотека).
for s in "$@"; do
  open_app
  tap "${s#*:}" && shot "${s%%:*}"
done
exit 0
