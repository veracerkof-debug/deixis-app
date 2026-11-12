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
# Названия на любом из языков пульта (эмулятор может быть на английском): переводы — из Strings.kt.
sys.path.insert(0, "tools"); import ui_names
wants, path = ui_names.names(sys.argv[1]), sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if n.get("text") in wants or n.get("content-desc") in wants:
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

# Сеть экрана на настоящем APK выпуска (после R8): сервер отвечает, шифрованный вход отвечает, пульт с компьютера
# (DEIXIS_CLI) вводит PIN с экрана и показывает слайд. Ломается молча только здесь — поэтому проверяется отдельно.
net_check() {
  adb forward tcp:47820 tcp:47820 > /dev/null
  info=$(curl -s --max-time 10 http://127.0.0.1:47820/info)
  if [ -z "$info" ]; then echo "=== ТВ: сервер не отвечает"; status=1; return; fi
  echo "=== ТВ /info: $info"
  tp=$(echo "$info" | sed -n 's/.*"tlsPort":\([0-9]*\).*/\1/p')
  if [ -n "$tp" ] && [ "$tp" != 0 ]; then
    adb forward tcp:$tp tcp:$tp > /dev/null
    if curl -sk --max-time 10 "https://127.0.0.1:$tp/info" | grep -q '"id"'; then echo "=== ТВ: шифрованный вход отвечает"
    else
      echo "=== ТВ: шифрованный вход НЕ отвечает"; status=1
      curl -vk --max-time 10 "https://127.0.0.1:$tp/info" 2>&1 | tail -20
      adb logcat -d | grep -iE "deixis|ssl|tls|conscrypt" | grep -iE "exception|error|fail" | tail -20
    fi
  fi
  [ -n "$DEIXIS_CLI" ] && [ -x "$DEIXIS_CLI" ] || { echo "=== ТВ: показ с пульта не проверен (нет DEIXIS_CLI)"; return; }
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  # Экран пишет PIN с пробелами между цифрами («1 2 3 4»).
  pin=$(adb shell cat /sdcard/ui.xml | grep -oE 'text="[0-9]( ?[0-9]){3}"' | head -1 | tr -cd '0-9')
  if [ -z "$pin" ]; then echo "=== ТВ: PIN на экране не найден — показ с пульта не проверен"; return; fi
  if "$DEIXIS_CLI" send --to 127.0.0.1:47820 --pin "$pin" --text "Проверка связи"; then
    sleep 2
    adb exec-out screencap -p > smoke/tv-shown.png
    echo "=== ТВ: пульт подключился по PIN и показал слайд"
  else
    echo "=== ТВ: показ с пульта НЕ удался"; status=1
  fi
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
  if [ "$target" = tv:MainActivity ] && alive "$pkg"; then
    net_check
    # Для сравнения: та же проверка на сборке без R8 (отладочной), если она есть.
    dbg=$(ls android/tv/build/outputs/apk/debug/*universal*.apk 2>/dev/null | head -1)
    if [ -z "$APK_DIR" ] && [ -n "$dbg" ]; then
      echo "=== ТВ без R8 (для сравнения): $dbg"
      adb uninstall "$pkg" > /dev/null; adb install "$dbg" > /dev/null
      adb shell am start -W -n "$pkg/$pkg.$activity" > /dev/null; sleep 15
      tp=$(curl -s --max-time 10 http://127.0.0.1:47820/info | sed -n 's/.*"tlsPort":\([0-9]*\).*/\1/p')
      if [ -z "$tp" ] || [ "$tp" = 0 ]; then echo "=== ТВ без R8: сервер не ответил или нет шифрованного входа"
      else
        adb forward tcp:$tp tcp:$tp > /dev/null
        if curl -sk --max-time 10 "https://127.0.0.1:$tp/info" | grep -q '"id"'; then echo "=== ТВ без R8: шифрованный вход отвечает"; else echo "=== ТВ без R8: шифрованный вход тоже НЕ отвечает"; fi
      fi
      adb uninstall "$pkg" > /dev/null; adb install "$apk" > /dev/null
      adb shell am start -W -n "$pkg/$pkg.$activity" > /dev/null; sleep 5
    fi
  fi
  adb logcat -d > "smoke/$app-$activity-logcat.txt"
  echo "--- падения $pkg:"
  crash=$(adb logcat -d -b crash | grep -A40 "Process: $pkg")
  if [ -n "$crash" ]; then echo "$crash" | head -80; status=1; fi
  grep -E "AndroidRuntime|FATAL|DeixisTV" "smoke/$app-$activity-logcat.txt" | tail -40
  adb shell am force-stop "$pkg"
done
exit $status
