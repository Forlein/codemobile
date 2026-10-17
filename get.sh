#!/data/data/com.termux/files/usr/bin/sh
# =============================================================================
#  CodeMobile — универсальный установщик-загрузчик (get.sh).
#  VS Code + ИИ-ассистент OpenCode в кармане. by 4rlein
#
#  Зачем он нужен: скачать и поставить проект может ЛЮБОЙ пользователь —
#  без git, без python, без предварительной подготовки. Скрипту достаточно
#  любого из: curl / wget / python3 / node. Если ничего нет — он сам поставит
#  curl пакетным менеджером (pkg/apt) и продолжит.
#
#  Что делает:
#    1. проверяет, что это Termux (иначе — понятная инструкция, что скачать);
#    2. выбирает, чем качать (curl → wget → python3 → node), при необходимости
#       доставляет curl, python, node, unzip (можно отключить: --no-deps);
#    3. скачивает архив проекта: сначала GitHub, затем зеркала-прокси
#       (ghproxy / gh-proxy / ghfast / jsDelivr) — автоперебор при сбое;
#    4. распаковывает в ~/.local/share/codemobile/src;
#    5. запускает install.sh из распакованных исходников, передавая ему
#       все оставшиеся флаги (--yes, --no-apk, --build, …).
#
#  Быстрый запуск (любой из вариантов):
#    pkg install -y curl
#    sh -c "$(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/get.sh)"
#
#    # через зеркало (если GitHub тормозит/недоступен):
#    sh -c "$(curl -fsSL https://cdn.jsdelivr.net/gh/Forlein/codemobile@main/get.sh)"
#
#    # вручную скачанным файлом:
#    sh get.sh -y
#
#  Флаги:
#    -y, --yes         передать install.sh: без вопросов, всё по умолчанию
#    --repo OWNER/REPO переопределить репозиторий (по умолчанию Forlein/codemobile)
#    --branch NAME     ветка/тег (по умолчанию main)
#    --src DIR         куда распаковать исходники
#    --mirror URL      добавить зеркало (можно указывать несколько раз)
#    --direct          только GitHub, без зеркал
#    --zip             скачать zip-архив вместо tar.gz
#    --update          перекачать, даже если исходники уже на месте
#    --no-deps         не доставлять пакеты (только скачать и запустить)
#    --no-run          только скачать исходники, install.sh не запускать
#    --print-url       показать адреса загрузки и выйти (ничего не качая)
#    --                дальше идут только флаги install.sh
#    -h, --help
#
#  Любой неизвестный флаг пробрасывается в install.sh как есть
#  (например: sh get.sh -y --no-apk --build).
#
#  Коды выхода: 0 — ок, 1 — ошибка скачивания/распаковки, 2 — неверный флаг.
# =============================================================================
set -u

# ── Значения по умолчанию (переопределяются флагами и переменными окружения) ──
GITHUB_REPO="${CODEMOBILE_REPO:-Forlein/codemobile}"
GITHUB_BRANCH="${CODEMOBILE_BRANCH:-main}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
SRC_DIR="${CODEMOBILE_SRC:-$HOME_DIR/.local/share/codemobile/src}"
SRC_EXPLICIT=0
USE_ZIP=0
FORCE_UPDATE=0
WITH_DEPS=1
DO_RUN=1
PRINT_URL=0
USE_MIRRORS=1
EXTRA_MIRRORS="${CODEMOBILE_MIRRORS:-}"

# Публичные зеркала-прокси GitHub (порядок = порядок перебора).
# Переопределить списком через запятую: CODEMOBILE_MIRRORS="https://a/,https://b/"
MIRROR_LIST="https://ghproxy.net/,https://gh-proxy.com/,https://ghfast.top/"

# Что нужно качать/ставить в самом минимуме
DEPS_ESSENTIAL="curl python nodejs-lts unzip"
DEPS_OPTIONAL="git tar gzip"

usage() {
    sed -n '3,48p' "$0" 2>/dev/null | sed 's/^# \{0,1\}//' || cat <<'USAGE'
CodeMobile get.sh — скачать и поставить проект одной командой (без git).

  sh get.sh              скачать и запустить установщик
  sh get.sh -y           скачать и поставить всё без вопросов
  sh get.sh --no-run     только скачать исходники
  sh get.sh --print-url  показать адреса загрузки
  sh get.sh --help
USAGE
}

# ── Цвета/вывод (POSIX: никаких $'…' и bash-массивов) ────────────────────────
if [ -t 1 ]; then
    ESC=$(printf '\033')
    R="${ESC}[31m"; G="${ESC}[32m"; Y="${ESC}[33m"; C="${ESC}[36m"
    B="${ESC}[1m"; DIM="${ESC}[2m"; N="${ESC}[0m"
else
    R=""; G=""; Y=""; C=""; B=""; DIM=""; N=""
fi
say()  { printf '%s\n' "$*"; }
ok()   { printf '  %s[ok]%s %s\n' "$G" "$N" "$*"; }
warn() { printf '  %s[!!]%s %s\n' "$Y" "$N" "$*"; }
err()  { printf '  %s[XX]%s %s\n' "$R" "$N" "$*"; }
info() { printf '  %s[--]%s %s\n' "$DIM" "$N" "$*"; }
hr()   { printf '%s\n' "────────────────────────────────────────────────────────────"; }

banner() {
    cat <<'ART'
   ____          _      __  __        _     _ _
  / ___|___   __| | ___|  \/  | ___  | |__ (_) | ___
 | |   / _ \ / _` |/ _ \ |\/| |/ _ \ | '_ \| | |/ _ \
 | |__| (_) | (_| |  __/ |  | | (_) || |_) | | |  __/
  \____\___/ \__,_|\___|_|  |_|\___(_)_.__/|_|_|\___|
ART
    printf '%s     установщик-загрузчик · VS Code + OpenCode в кармане%s\n\n' "$DIM" "$N"
}

# ── Разбор флагов ────────────────────────────────────────────────────────────
# Флаги самого get.sh разбираем, остальные копим в PASS (для install.sh).
PASS=""
want_pass() {                            # want_pass <флаг> [значение]
    PASS="$PASS $1${2:+ $2}"
}

while [ $# -gt 0 ]; do
    a="$1"
    case "$a" in
        -y|--yes)   want_pass "$a" ;;
        --repo)     shift; [ $# -gt 0 ] || { err "--repo требует значение"; exit 2; }; GITHUB_REPO="$1" ;;
        --branch)   shift; [ $# -gt 0 ] || { err "--branch требует значение"; exit 2; }; GITHUB_BRANCH="$1" ;;
        --src)      shift; [ $# -gt 0 ] || { err "--src требует путь"; exit 2; }
                    SRC_DIR="$1"; SRC_EXPLICIT=1 ;;
        --mirror)   shift; [ $# -gt 0 ] || { err "--mirror требует URL"; exit 2; }
                    EXTRA_MIRRORS="${EXTRA_MIRRORS:+$EXTRA_MIRRORS,}$1" ;;
        --direct)   USE_MIRRORS=0 ;;
        --zip)      USE_ZIP=1 ;;
        --update)   FORCE_UPDATE=1 ;;
        --no-deps)  WITH_DEPS=0 ;;
        --no-run)   DO_RUN=0 ;;
        --print-url) PRINT_URL=1 ;;
        --)         shift; while [ $# -gt 0 ]; do want_pass "$1"; shift; done; break ;;
        -h|--help)  usage; exit 0 ;;
        -*)         want_pass "$a" ;;    # неизвестный — отдадим install.sh
        *)          want_pass "$a" ;;
    esac
    shift
done

# ── Адреса загрузки: прямые + через зеркала-прокси ───────────────────────────
# list_raw <путь-в-репо>   → адреса одиночного файла (для get.sh/install.sh)
# list_archive              → адреса архива проекта (tar.gz или zip)
list_raw() {
    _p="$1"
    printf '%s\n' "https://raw.githubusercontent.com/$GITHUB_REPO/$GITHUB_BRANCH/$_p"
    [ "$USE_MIRRORS" = 1 ] || return 0
    printf '%s\n' "https://cdn.jsdelivr.net/gh/$GITHUB_REPO@$GITHUB_BRANCH/$_p"
    print_mirror_prefixed "$_p"
}

print_mirror_prefixed() {
    _p="$1"
    printf '%s\n' "$MIRROR_LIST" | tr ',' '\n' | while IFS= read -r m; do
        [ -n "$m" ] || continue
        printf '%shttps://raw.githubusercontent.com/%s/%s/%s\n' "$m" "$GITHUB_REPO" "$GITHUB_BRANCH" "$_p"
        printf '%shttps://github.com/%s/raw/%s/%s\n' "$m" "$GITHUB_REPO" "$GITHUB_BRANCH" "$_p"
    done
    printf '%s\n' "$EXTRA_MIRRORS" | tr ',' '\n' | while IFS= read -r m; do
        [ -n "$m" ] || continue
        printf '%shttps://raw.githubusercontent.com/%s/%s/%s\n' "$m" "$GITHUB_REPO" "$GITHUB_BRANCH" "$_p"
    done
}

list_archive() {
    if [ "$USE_ZIP" = 1 ]; then
        printf '%s\n' "https://codeload.github.com/$GITHUB_REPO/zip/refs/heads/$GITHUB_BRANCH"
        printf '%s\n' "https://github.com/$GITHUB_REPO/archive/refs/heads/$GITHUB_BRANCH.zip"
    else
        printf '%s\n' "https://codeload.github.com/$GITHUB_REPO/tar.gz/refs/heads/$GITHUB_BRANCH"
        printf '%s\n' "https://github.com/$GITHUB_REPO/archive/refs/heads/$GITHUB_BRANCH.tar.gz"
    fi
    [ "$USE_MIRRORS" = 1 ] || return 0
    # те же адреса, но через прокси
    if [ "$USE_ZIP" = 1 ]; then
        _tail="archive/refs/heads/$GITHUB_BRANCH.zip"
    else
        _tail="archive/refs/heads/$GITHUB_BRANCH.tar.gz"
    fi
    for m in $(printf '%s' "$MIRROR_LIST,$EXTRA_MIRRORS" | tr ',' ' '); do
        [ -n "$m" ] || continue
        printf '%shttps://github.com/%s/%s\n' "$m" "$GITHUB_REPO" "$_tail"
    done
}

if [ "$PRINT_URL" = 1 ]; then
    say "${B}install.sh:${N}"
    list_raw install.sh
    say ""
    say "${B}архив проекта:${N}"
    list_archive
    exit 0
fi

# ── Загрузчик: curl → wget → python3 → node ──────────────────────────────────
DL=""
pick_dl() {
    if   command -v curl    >/dev/null 2>&1; then DL=curl
    elif command -v wget    >/dev/null 2>&1; then DL=wget
    elif command -v python3 >/dev/null 2>&1; then DL=python3
    elif command -v node    >/dev/null 2>&1; then DL=node
    else DL=""
    fi
}

pkg_install() {                          # pkg_install <пакеты…>
    if command -v pkg >/dev/null 2>&1; then
        pkg install -y "$@" || return 1
    elif command -v apt >/dev/null 2>&1; then
        apt install -y "$@" || return 1
    else
        return 1
    fi
}

ensure_dl() {
    pick_dl
    [ -n "$DL" ] && return 0
    warn "нет ни curl, ни wget, ни python3, ни node — доставлю curl…"
    pkg_install curl || true
    pick_dl
    [ -n "$DL" ] && return 0
    err "не удалось получить загрузчик. Поставь вручную: pkg install curl"
    return 1
}

# download URL FILE — тихо/с полоской прогресса, с ретраями
download() {
    _url="$1"; _file="$2"
    rm -f "$_file" 2>/dev/null
    case "$DL" in
        curl)
            if [ -t 1 ]; then
                curl -fL --progress-bar --retry 3 --retry-delay 2 \
                     --connect-timeout 20 --max-time 900 -o "$_file" "$_url"
            else
                curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 \
                     --max-time 900 -o "$_file" "$_url"
            fi ;;
        wget)
            wget -O "$_file" --tries=3 --timeout=30 "$_url" ;;
        python3)
            python3 - "$_url" "$_file" <<'PY'
import sys, urllib.request
url, out = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": "CodeMobile-get/1.0"})
with urllib.request.urlopen(req, timeout=60) as r, open(out, "wb") as f:
    while True:
        chunk = r.read(65536)
        if not chunk:
            break
        f.write(chunk)
PY
            ;;
        node)
            node -e '
const fs = require("fs");
const [ url, out ] = process.argv.slice(1);
fetch(url, { redirect: "follow" }).then(async r => {
  if (!r.ok) { console.error("HTTP " + r.status); process.exit(1); }
  const buf = Buffer.from(await r.arrayBuffer());
  fs.writeFileSync(out, buf);
}).catch(e => { console.error(String(e)); process.exit(1); });
' "$_url" "$_file" ;;
        *) return 1 ;;
    esac
}

# файл скачался целиком (а не HTML-ошибка зеркала)?
looks_valid() {                          # looks_valid <файл> [zip|tar]
    _f="$1"; _kind="${2:-tar}"
    [ -s "$_f" ] || return 1
    _sz=$(wc -c <"$_f" 2>/dev/null | tr -d ' ')
    [ -n "$_sz" ] && [ "$_sz" -gt 2048 ] || return 1
    if [ "$_kind" = zip ]; then
        command -v unzip >/dev/null 2>&1 && unzip -tqq "$_f" >/dev/null 2>&1
        return $?
    fi
    if command -v gzip >/dev/null 2>&1; then gzip -t "$_f" >/dev/null 2>&1; return $?; fi
    if command -v tar  >/dev/null 2>&1; then tar -tzf "$_f" >/dev/null 2>&1; return $?; fi
    return 0
}

# try_download FILE LISTFILE — перебирает адреса, пока какой-то не сработает
try_download() {
    _dest="$1"; _list="$2"; _kind="$3"
    _n=0
    while IFS= read -r u; do
        [ -n "$u" ] || continue
        _n=$((_n + 1))
        info "пробую: $u"
        if download "$u" "$_dest" && looks_valid "$_dest" "$_kind"; then
            _sz=$(du -h "$_dest" 2>/dev/null | cut -f1)
            ok "скачано (${_sz:-?})"
            return 0
        fi
        warn "не вышло — пробую следующее зеркало…"
    done <"$_list"
    return 1
}

# ── Распаковка архива в SRC_DIR ──────────────────────────────────────────────
extract_into() {                         # extract_into <архив> <куда> <zip|tar>
    _arc="$1"; _dest="$2"; _kind="$3"
    _tmp=$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/codemobile.XXXXXX") || return 1
    if [ "$_kind" = zip ]; then
        unzip -qq "$_arc" -d "$_tmp" || { err "не распаковался zip"; rm -rf "$_tmp"; return 1; }
    else
        tar -xzf "$_arc" -C "$_tmp" || { err "не распаковался tar.gz"; rm -rf "$_tmp"; return 1; }
    fi
    # в архиве один каталог верхнего уровня: repo-branch
    _top=""
    for d in "$_tmp"/*/; do
        [ -d "$d" ] || continue
        _top="$d"; break
    done
    [ -n "$_top" ] || { err "в архиве нет каталога проекта"; rm -rf "$_tmp"; return 1; }
    [ -f "$_top/install.sh" ] || warn "в архиве нет install.sh — репозиторий/ветка верные?"

    mkdir -p "$_dest" || { rm -rf "$_tmp"; return 1; }
    # копируем содержимое, сохраняя уже существующие локальные правки
    cp -R "$_top". "$_dest"/ 2>/dev/null || cp -rf "$_top". "$_dest"/ || {
        err "не скопировалось в $_dest"; rm -rf "$_tmp"; return 1; }
    rm -rf "$_tmp"
    return 0
}

# ── Зависимости: python/node/unzip (+ необязательные) ────────────────────────
install_deps() {
    need=""
    for p in $DEPS_ESSENTIAL; do
        case "$p" in
            python)    command -v python3 >/dev/null 2>&1 && continue ;;
            nodejs-lts) command -v node >/dev/null 2>&1 || command -v npm >/dev/null 2>&1 && continue ;;
            *)         command -v "$p" >/dev/null 2>&1 && continue ;;
        esac
        need="$need $p"
    done
    [ -n "$need" ] || { ok "зависимости уже на месте (curl, python, node, unzip)"; return 0; }
    info "доставляю:${need}"
    # сначала пробуем одним заходом, затем — по одному (чтобы одна сломанная
    # репа не валила всю установку)
    if ! pkg_install $need; then
        for p in $need; do
            pkg_install "$p" || warn "не поставился пакет: $p (продолжаю)"
        done
    fi
    for p in $DEPS_OPTIONAL; do
        command -v "$p" >/dev/null 2>&1 || pkg_install "$p" >/dev/null 2>&1 || true
    done
    command -v python3 >/dev/null 2>&1 && ok "python3: $(python3 --version 2>&1)" \
        || warn "python3 нет — часть шагов установщика пропустится"
    command -v node >/dev/null 2>&1 && ok "node: $(node -v 2>&1)" \
        || warn "node нет — ИИ-ассистенту он понадобится (поставится позже)"
    return 0
}

# ── Проверка окружения ───────────────────────────────────────────────────────
check_termux() {
    if [ -d "$PREFIX" ] && { command -v pkg >/dev/null 2>&1 || command -v apt >/dev/null 2>&1; }; then
        ok "Termux обнаружен: $PREFIX"
        return 0
    fi
    err "похоже, это не Termux (PREFIX=$PREFIX, pkg не найден)."
    say ""
    say "${B}Что делать:${N}"
    say "  1. Поставь Termux — только с F-Droid или GitHub (версия из Play Маркета устарела):"
    say "     • F-Droid:  https://f-droid.org/packages/com.termux/"
    say "     • GitHub:   https://github.com/termux/termux-app/releases"
    say "  2. Открой Termux и запусти установщик снова:"
    say "     ${C}sh -c \"\$(curl -fsSL https://raw.githubusercontent.com/$GITHUB_REPO/$GITHUB_BRANCH/get.sh)\"${N}"
    say ""
    say "${DIM}На Linux/ПК этот установщик не нужен: приложение собирается Gradle из app/.${N}"
    return 1
}

# ── Основной сценарий ────────────────────────────────────────────────────────
main() {
    banner

    # 1) исходники уже рядом с get.sh (запущен из репозитория) — просто идём дальше
    SELF="${0:-}"
    HERE=""
    case "$SELF" in
        */*) [ -f "$SELF" ] && HERE=$(CDPATH= cd -- "$(dirname -- "$SELF")" 2>/dev/null && pwd) ;;
        *)   HERE=$(pwd) ;;
    esac
    if [ -n "$HERE" ] && [ -f "$HERE/install.sh" ] && [ -f "$HERE/tools/settings.seed.json" ] \
       && [ "$SRC_EXPLICIT" = 0 ]; then
        SRC_DIR="$HERE"
        info "использую исходники рядом: $SRC_DIR"
    fi

    # 2) окружение
    check_termux || exit 1

    # 3) уже скачано? (быстрый путь — без сети)
    if [ -f "$SRC_DIR/install.sh" ] && [ -f "$SRC_DIR/tools/settings.seed.json" ] && [ "$FORCE_UPDATE" = 0 ]; then
        ok "исходники уже есть: $SRC_DIR ${DIM}(перекачать: --update)${N}"
    else
        ensure_dl || exit 1
        [ "$WITH_DEPS" = 1 ] && install_deps

        LISTS=$(mktemp "${TMPDIR:-$PREFIX/tmp}/codemobile-urls.XXXXXX") || exit 1
        ARCH=$(mktemp "${TMPDIR:-$PREFIX/tmp}/codemobile-arch.XXXXXX")   || exit 1

        if [ "$USE_ZIP" = 1 ]; then KIND=zip; ARCH="$ARCH.zip"; else KIND=tar; ARCH="$ARCH.tar.gz"; fi

        say "${B}Скачиваю проект${N} ($GITHUB_REPO @ $GITHUB_BRANCH, ${KIND})"
        list_archive >"$LISTS"
        if ! try_download "$ARCH" "$LISTS" "$KIND"; then
            err "не удалось скачать архив ни с GitHub, ни с зеркал."
            say "  Проверь интернет и повтори. Можно задать зеркало вручную:"
            say "  ${C}sh get.sh --mirror https://ghproxy.net/${N}"
            say "  или указать другой репозиторий/ветку: --repo owner/repo --branch main"
            rm -f "$LISTS" "$ARCH"; exit 1
        fi
        rm -f "$LISTS"

        say "${B}Распаковываю${N} → $SRC_DIR"
        if ! extract_into "$ARCH" "$SRC_DIR" "$KIND"; then rm -f "$ARCH"; exit 1; fi
        rm -f "$ARCH"
        ok "исходники: $SRC_DIR"

        # 4) недостающие инструменты сборки/оформления (после распаковки, если --no-deps)
        [ "$WITH_DEPS" = 0 ] && [ "$DO_RUN" = 1 ] && ensure_dl
    fi

    hr
    if [ "$DO_RUN" = 0 ]; then
        ok "Готово: скачано в $SRC_DIR"
        say "  Запустить установщик: ${C}sh $SRC_DIR/install.sh${N}"
        exit 0
    fi

    # 5) передаём управление установщику проекта (install.sh — на bash)
    [ -f "$SRC_DIR/install.sh" ] || { err "нет $SRC_DIR/install.sh"; exit 1; }
    say "${B}Запускаю установщик проекта…${N}"
    say ""
    # shellcheck disable=SC2086
    if command -v bash >/dev/null 2>&1; then
        exec bash "$SRC_DIR/install.sh" $PASS
    else
        warn "bash не найден — пробую sh (часть шагов может не сработать)"
        exec sh "$SRC_DIR/install.sh" $PASS
    fi
}

main "$@"
