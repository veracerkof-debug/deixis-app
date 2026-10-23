#!/bin/sh
# Проверка запуска на эмуляторе: приложение открывается и живёт 20 секунд; журнал падения и снимок экрана — в smoke/.
mkdir -p smoke
status=0
for app in tv phone; do
  pkg="app.deixis.$app"
  apk=$(ls android/$app/build/outputs/apk/debug/*universal*.apk | head -1)
  echo "=== $pkg: $apk"
  adb install -r "$apk" || status=1
  adb logcat -c
  adb shell am start -W -n "$pkg/$pkg.MainActivity"
  sleep 20
  adb exec-out screencap -p > "smoke/$app.png"
  adb logcat -d > "smoke/$app-logcat.txt"
  if adb shell pidof "$pkg" > /dev/null; then echo "=== $pkg работает"; else echo "=== $pkg НЕ РАБОТАЕТ"; status=1; fi
  echo "--- падения $pkg:"
  adb logcat -d -b crash | tail -80
  grep -E "AndroidRuntime|FATAL|$pkg" "smoke/$app-logcat.txt" | tail -60
  adb shell am force-stop "$pkg"
done
exit $status
