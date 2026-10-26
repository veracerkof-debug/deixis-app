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
