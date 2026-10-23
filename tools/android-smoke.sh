#!/bin/sh
# Проверка запуска на эмуляторе: приложение открывается и живёт 20 секунд; журнал падения и снимок экрана — в smoke/.
mkdir -p smoke
status=0
for target in tv:MainActivity phone:MainActivity phone:ScreenActivity; do
  app=${target%%:*}
  activity=${target#*:}
  pkg="app.deixis.$app"
  apk=$(ls android/$app/build/outputs/apk/release/*universal*.apk | head -1)
  echo "=== $pkg: $apk"
  adb install -r "$apk" || status=1
  adb logcat -c
  adb shell am start -W -n "$pkg/$pkg.$activity"
  sleep 20
  adb exec-out screencap -p > "smoke/$app-$activity.png"
  adb logcat -d > "smoke/$app-$activity-logcat.txt"
  if adb shell pidof "$pkg" > /dev/null; then echo "=== $pkg/$activity работает"; else echo "=== $pkg/$activity НЕ РАБОТАЕТ"; status=1; fi
  echo "--- падения $pkg:"
  adb logcat -d -b crash | tail -80
  grep -E "AndroidRuntime|FATAL|DeixisTV|$pkg" "smoke/$app-$activity-logcat.txt" | tail -60
  adb shell am force-stop "$pkg"
done
exit $status
