#!/usr/bin/env python3
"""Короткая сводка упавших проверок из XML-отчётов Gradle: имя, сообщение, начало следа и вывод теста.
Журнал CI тонет в стеке Gradle, а отчёты-архивы не всегда можно скачать."""
import glob
import sys
import xml.etree.ElementTree as ET

# Консоль Windows в CI — cp1252: без этого русские сообщения роняют сводку.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

found = 0
for path in sorted(glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True)):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for f in list(case.iter("failure")) + list(case.iter("error")):
            found += 1
            print(f"::error::{case.get('classname')}.{case.get('name')}: {(f.get('message') or '')[:300]}")
            lines = [l for l in (f.text or "").splitlines() if "app.deixis" in l or not l.strip().startswith("at ")]
            print("\n".join(lines[:25]))
    out = root.findtext("system-out") or ""
    if found and out.strip():
        print("--- вывод", path)
        print(out.strip()[-3000:])
print(f"Упавших проверок: {found}")
sys.exit(0)
