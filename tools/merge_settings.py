#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
merge_settings.py — неразрушающая раскатка дизайна в settings.json code-server.

Берёт значения из «сида» (tools/settings.seed.json) и МЕРЖИТ их в живой
settings.json: ключи сида перекрывают одноимённые, всё остальное в живом файле
(настройки пользователя, расширения и т.д.) остаётся нетронутым. Старый файл
сохраняется в settings.json.bak-install.

Умеет читать JSONC (комментарии // и /* */, висячие запятые) — родной формат
settings.json VS Code.

Использование:
  python3 tools/merge_settings.py <seed.json> <target.json>
  python3 tools/merge_settings.py --dry <seed.json> <target.json>

Код возврата: 0 — ок, 1 — ошибка.
"""
import json
import os
import re
import shutil
import sys

# ── мини-парсер JSONC ────────────────────────────────────────────────────────
def strip_jsonc(text: str) -> str:
    out = []
    i, n = 0, len(text)
    in_str = False
    esc = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            i += 2
            while i < n and text[i] not in "\r\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    s = "".join(out)
    s = re.sub(r",(\s*[}\]])", r"\1", s)  # висячие запятые
    return s


def load_jsonc(path: str):
    with open(path, "r", encoding="utf-8") as f:
        return json.loads(strip_jsonc(f.read()))


def deep_merge(base: dict, over: dict) -> dict:
    """over перекрывает base; словари мержатся рекурсивно."""
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            deep_merge(base[k], v)
        else:
            base[k] = v
    return base


def main() -> int:
    args = [a for a in sys.argv[1:]]
    dry = "--dry" in args
    args = [a for a in args if a != "--dry"]
    if len(args) != 2:
        print(__doc__)
        return 1
    seed_path, target_path = args

    if not os.path.isfile(seed_path):
        print(f"[XX] нет сида: {seed_path}")
        return 1
    try:
        seed = load_jsonc(seed_path)
    except Exception as e:  # noqa: BLE001
        print(f"[XX] сид не читается ({e}): {seed_path}")
        return 1

    live = {}
    if os.path.isfile(target_path):
        try:
            live = load_jsonc(target_path)
        except Exception as e:  # noqa: BLE001
            print(f"[!!] живой settings.json повреждён ({e}) — делаю бэкап и пишу заново")
            if not dry:
                shutil.copy2(target_path, target_path + ".bak-corrupt")
            live = {}
    if not isinstance(live, dict):
        live = {}

    merged = deep_merge(dict(live), seed)
    if merged == live:
        print("[= ] settings.json — уже соответствует дизайну, изменений нет")
        return 0

    pretty = json.dumps(merged, indent=4, ensure_ascii=False) + "\n"
    if dry:
        print(f"[dry] обновил бы {target_path} "
              f"({len(live)} → {len(merged)} ключей верхнего уровня)")
        return 0

    os.makedirs(os.path.dirname(target_path) or ".", exist_ok=True)
    if os.path.isfile(target_path):
        shutil.copy2(target_path, target_path + ".bak-install")
    with open(target_path, "w", encoding="utf-8") as f:
        f.write(pretty)
    print(f"[ok] дизайн применён: {target_path} "
          f"(бэкап: {os.path.basename(target_path)}.bak-install)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
