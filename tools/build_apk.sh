#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# build_apk.sh — собрать APK и автоматически положить его в Download.
#
# ПРАВИЛО ПРОЕКТА: КАЖДАЯ новая версия или правка — это НОВЫЙ apk-файл.
# Никогда не перезаписываем старый: версия берётся из versionName в
# app/build.gradle, файл кладётся как
#     /storage/emulated/0/Download/CodeMobile-<versionName>.apk
# Если такой файл уже есть (ту же версию собирают дважды) — он не трогается,
# скрипт ругается: сначала подними versionName.
#
# Использование:
#   bash tools/build_apk.sh            # assembleDebug → Download
#   bash tools/build_apk.sh --release  # assembleRelease → Download
#   bash tools/build_apk.sh --no-copy  # только собрать, никуда не класть
#   bash tools/build_apk.sh --offline  # только из кэша Gradle (без сети)
#   bash tools/build_apk.sh --allow-existing  # не ругаться, если файл уже в Download
#   bash tools/build_apk.sh --keep=5   # и подчистить Download, оставив
#                                      #   5 последних версий (по умолчанию не чистит)
#
# На первой сборке нужна сеть (Gradle тянет плагин Android и зависимости),
# поэтому по умолчанию Gradle НЕ в offline-режиме. Ускорить повторную сборку
# без сети: --offline.
#
# Коды выхода: 0 — ок, 1 — ошибка.
# =============================================================================
set -uo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$(readlink -f "$0" 2>/dev/null || echo "$0")")/.." && pwd)"
GRADLE_TASK="assembleDebug"
APK_SUB="debug"
DO_COPY=1
KEEP=0
GRADLE_FLAGS=""      # по умолчанию с сетью: на первой сборке Gradle качает плагин и зависимости
ALLOW_EXISTING=0     # --allow-existing: не падать, если файл с этой версией уже в Download

for a in "$@"; do
    case "$a" in
        --release)  GRADLE_TASK="assembleRelease"; APK_SUB="release" ;;
        --debug)    GRADLE_TASK="assembleDebug";   APK_SUB="debug" ;;
        --no-copy)  DO_COPY=0 ;;
        --offline)  GRADLE_FLAGS="--offline" ;;
        --allow-existing) ALLOW_EXISTING=1 ;;
        --keep=*)   KEEP="${a#--keep=}" ;;
        -h|--help)  sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "неизвестный флаг: $a (см. --help)" >&2; exit 2 ;;
    esac
done

DL_DIR="${DL_DIR:-/storage/emulated/0/Download}"

# --- версия из build.gradle ------------------------------------------------
VERSION="$(grep -oE "versionName[[:space:]]+'[^']+'" "$ROOT/app/build.gradle" \
    | head -n1 | sed -E "s/.*'([^']+)'.*/\1/")"
if [ -z "$VERSION" ]; then
    echo "[XX] не нашёл versionName в app/build.gradle" >&2
    exit 1
fi

OUT="$ROOT/app/build/outputs/apk/$APK_SUB/app-$APK_SUB.apk"
DEST="$DL_DIR/CodeMobile-$VERSION.apk"

echo "== Сборка CodeMobile $VERSION ($GRADLE_TASK)"
( cd "$ROOT" && ./gradlew ":app:$GRADLE_TASK" $GRADLE_FLAGS -q )
RC=$?
if [ "$RC" != 0 ] || [ ! -f "$OUT" ]; then
    echo "[XX] сборка не удалась (code $RC) или нет $OUT" >&2
    exit 1
fi
echo "  [ok] $OUT"

# --- перенос в Download (новый файл на каждую версию) ----------------------
if [ "$DO_COPY" = 1 ]; then
    if [ ! -d "$DL_DIR" ]; then
        echo "[XX] нет доступа к $DL_DIR — выполните: termux-setup-storage" >&2
        exit 1
    fi
    if [ -f "$DEST" ]; then
        if [ "$ALLOW_EXISTING" = 1 ]; then
            echo "  [= ] $DEST уже существует — оставляю как есть"
        else
            echo "[!!] $DEST уже существует — НЕ перезаписываю." >&2
            echo "     Подними versionName в app/build.gradle и собери снова." >&2
            exit 1
        fi
    else
        cp "$OUT" "$DEST" || { echo "[XX] не удалось скопировать в $DEST" >&2; exit 1; }
        echo "  [ok] $DEST"
    fi
fi

# --- необязательная чистка Download ---------------------------------------
if [ "${KEEP:-0}" -gt 0 ] 2>/dev/null; then
    echo "== Чистка Download: оставляю $KEEP последних версий"
    mapfile -t ALL < <(ls -1 "$DL_DIR"/CodeMobile-*.apk 2>/dev/null | sort -V)
    n=${#ALL[@]}
    if [ "$n" -gt "$KEEP" ]; then
        for ((i = 0; i < n - KEEP; i++)); do
            rm -f "${ALL[$i]}" && echo "  [rm] ${ALL[$i]##*/}"
        done
    else
        echo "  [= ] всего $n — чистить нечего"
    fi
fi

echo "== Готово: CodeMobile $VERSION"
