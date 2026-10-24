#!/bin/sh
# Проверка на эмуляторе: приложения ставятся, открываются, живут 20 секунд; у пульта — проход по всем вкладкам
# и настройкам касаниями, как у человека. Журналы падений и снимки экранов — в smoke/.
# APK_DIR — папка с готовыми APK выпуска (проверяются ровно те файлы, что уйдут в выпуск);
# без неё берутся сборки android/*/build/outputs.
mkdir -p smoke
status=0

apk_for() {
  if [ -n "$APK_DIR" ]; then
    if [ "$1" = tv ]; then ls "$APK_DIR"/Deixis-TV-*-all.apk | head -1; else ls "$APK_DIR"/Deixis-[0-9]*-all.apk | head -1; fi
  else
    ls android/$1/build/outputs/apk/release/*universal*.apk | head -1
  fi
}

alive() { adb shell pidof "$1" > /dev/null; }

# Касание элемента по тексту или описанию (так Compose показывает их системе).
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml > smoke/ui.xml
  xy=$(python3 - "$1" smoke/ui.xml <<'PY'
import re, sys
import xml.etree.ElementTree as ET
want, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if want in (n.get("text"), n.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -z "$xy" ]; then echo "=== нет на экране: «$1»"; return 1; fi
  adb shell input tap $xy
  sleep 3
}

walk_phone() {
  pkg=app.deixis.phone
  n=0
  for t in "Библия" "Библиотека" "Сетлисты" "Устройства" "Показ" "Настройки"; do
    n=$((n + 1))
    if ! tap "$t"; then status=1; continue; fi
    adb exec-out screencap -p > "smoke/phone-walk-$n.png"
    if ! alive $pkg; then echo "=== $pkg упал после «$t»"; status=1; return; fi
    echo "=== «$t» открыт"
  done
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  alive $pkg || { echo "=== $pkg упал после «Назад»"; status=1; }
}

for target in tv:MainActivity phone:MainActivity phone:ScreenActivity; do
  app=${target%%:*}
  activity=${target#*:}
  pkg="app.deixis.$app"
  apk=$(apk_for $app)
  echo "=== $pkg: $apk"
  adb install -r "$apk" || status=1
  adb logcat -c
  adb shell am start -W -n "$pkg/$pkg.$activity"
  sleep 20
  adb exec-out screencap -p > "smoke/$app-$activity.png"
  if alive "$pkg"; then echo "=== $pkg/$activity работает"; else echo "=== $pkg/$activity НЕ РАБОТАЕТ"; status=1; fi
  [ "$target" = phone:MainActivity ] && walk_phone
  adb logcat -d > "smoke/$app-$activity-logcat.txt"
  echo "--- падения $pkg:"
  crash=$(adb logcat -d -b crash | grep -A40 "Process: $pkg")
  if [ -n "$crash" ]; then echo "$crash" | head -80; status=1; fi
  grep -E "AndroidRuntime|FATAL|DeixisTV" "smoke/$app-$activity-logcat.txt" | tail -40
  adb shell am force-stop "$pkg"
done
exit $status
