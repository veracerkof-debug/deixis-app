"""Названия кнопок пульта на всех языках — для сценариев снимков и запуска на эмуляторе.

Сценарии пишутся по-русски («Настройки»), а пульт показывается на языке системы эмулятора. По Strings.kt
русское название превращается в список: русский, узбекский, английский.
"""
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
_STRINGS = os.path.join(_HERE, "..", "android", "phone", "src", "main", "kotlin", "app", "deixis", "phone", "Strings.kt")
_T = re.compile(r't\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)', re.S)
_cache = None


def _table():
    global _cache
    if _cache is None:
        _cache = {}
        try:
            text = open(_STRINGS, encoding="utf-8").read()
        except OSError:
            text = ""
        for ru, uz, en in _T.findall(text):
            _cache.setdefault(ru, [ru, uz, en])
    return _cache


def names(ru):
    """Все варианты названия; «А|B» — уже готовый список."""
    if "|" in ru:
        return ru.split("|")
    return _table().get(ru, [ru])
