#!/usr/bin/env bash
# Образцы данных выпуска для проверки совместимости (testdata/compat): их записывает код самого выпуска.
#   tools/compat-fixtures.sh v0.10.0
# Делается после каждого выпуска, где менялись сохраняемые данные: библиотека телефона, пульты, мониторы.
set -euo pipefail
tag="$1"; ver="${tag#v}"
root="$(cd "$(dirname "$0")/.." && pwd)"
wt="$(mktemp -d)/wt"
git -C "$root" worktree add -q "$wt" "$tag"
trap 'git -C "$root" worktree remove --force "$wt"' EXIT
mkdir -p "$wt/fixgen/src/main/kotlin/app/deixis/phone"
cp "$root/testdata/compat/generator/build.gradle.kts" "$wt/fixgen/"
sed "s/__VERSION__/$ver/g" "$root/testdata/compat/generator/Fix.kt" > "$wt/fixgen/src/main/kotlin/app/deixis/phone/Fix.kt"
# Классы данных выпуска как есть, без хранилища (оно зависит от Android).
awk '/^\/\*\* Хранилище библиотеки/{exit} {print}' "$wt/android/phone/src/main/kotlin/app/deixis/phone/Model.kt" | grep -v "^import android" \
  > "$wt/fixgen/src/main/kotlin/app/deixis/phone/OldModel.kt"
echo 'include(":fixgen")' >> "$wt/settings.gradle.kts"
(cd "$wt" && DEIXIS_SKIP_ANDROID=1 ./gradlew -q :fixgen:run --args="$root/testdata/compat")
ls "$root/testdata/compat" | grep "$ver"
