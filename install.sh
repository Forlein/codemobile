#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  CodeMobile — установщик «одной командой» для Termux.
#  VS Code в браузере + ИИ-ассистент OpenCode с бесплатными моделями. by 4rlein
#
#  Что делает (интерактивно, с прогресс-баром):
#    1.  Проверка окружения Termux
#    2.  Доступ к памяти устройства (termux-setup-storage)
#    3.  Базовые пакеты (tur-repo, python, node, curl, git, unzip)
#    4.  OpenCode (ИИ-ассистент)                    — спрашивает, нужен ли
#    5.  Бесплатные модели (OpenKilo + Zen-Proxy)   — автоконфиг
#        └ ключ opencode.ai можно ввести сразу или ПРОПУСТИТЬ (позже: mem helper)
#    6.  VS Code в браузере (code-server)
#    7.  Скрипты запуска + CLI `codemobile` + termux.properties
#    8.  Дизайн/оформление (тема-стиль Apple, шрифты, курсор)
#    9.  APK-лаунчер (скачать из релиза GitHub и установить)
#   10.  Проверка ошибок и подсказки по решению
#
#  Режим разработчика (--build): доставить JDK 21, нативный aapt2 и Android
#  SDK (платформа 34, build-tools 36) и СОБРАТЬ APK прямо в Termux — без ПК.
#
#  Запуск (любой из вариантов):
#    # 1) самый простой — через установщик-загрузчик get.sh (git не нужен):
#    sh -c "$(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/get.sh)"
#
#    # 2) напрямую (если исходники уже есть или скачаются сами):
#    bash <(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/install.sh)
#
#    # 3) из готовых исходников (скачанных get.sh, zip-архивом или git clone):
#    git clone https://github.com/Forlein/codemobile && cd codemobile && bash install.sh
#
#  Git и python для загрузки НЕ нужны: install.sh сам скачивает архив проекта
#  (GitHub + зеркала-прокси), а зависимости доставляет пакетным менеджером.
#
#  Флаги:
#    -y, --yes        не задавать вопросов, ставить всё по умолчанию
#    --dry            только показать, что будет сделано
#    --no-opencode    не ставить OpenCode
#    --no-models      без бесплатных моделей (OpenKilo/Zen-Proxy)
#    --zen-key KEY    Zen-ключ для бесплатных моделей (иначе спросит / можно пропустить)
#    --no-zen-key     не спрашивать ключ (вписать позже командой: mem helper)
#    --no-code-server без code-server
#    --no-design      без оформления/дизайна
#    --no-apk         не качать/не ставить APK
#    --no-hints       без инструкций Xiaomi/Shizuku
#    --build          поставить JDK+aapt2+Android SDK и собрать APK в Termux
#    --no-build       не собирать APK (только скачать готовый из релиза)
#    --mirror URL     скачивать исходники через зеркало-прокси GitHub
#    --refresh        перекачать исходники, даже если они уже есть
#    -h, --help
#
#  Коды выхода: 0 — ок, 1 — были ошибки.
# =============================================================================
set -uo pipefail

# ── Репозиторий (для bootstrap и скачивания APK) ─────────────────────────────
GITHUB_REPO="${CODEMOBILE_REPO:-Forlein/codemobile}"
GITHUB_BRANCH="${CODEMOBILE_BRANCH:-main}"
# Зеркала-прокси GitHub: подставляются ПЕРЕД адресом (работают и для raw, и для
# архивов). Нужны там, где github.com/raw недоступен или очень медленный.
GITHUB_MIRRORS="${CODEMOBILE_MIRRORS:-https://ghproxy.net/,https://gh-proxy.com/,https://ghfast.top/}"
RAW_CDN="https://cdn.jsdelivr.net/gh"     # jsDelivr — быстрый доступ к файлам репо
REFRESH_SRC=0                             # --refresh: перекачать исходники

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
export PATH="$PREFIX/bin:$PREFIX/opt/nodejs-24/bin:$PATH"
SRC_DIR="$HOME_DIR/.local/share/codemobile/src"
PKG_APP="com.example.opencodelauncher"

# ── Набор для сборки в Termux (режим --build) ────────────────────────────────
SDK_DIR="$HOME_DIR/android-sdk"
SDK_PLATFORM="platforms;android-34"
SDK_BUILDTOOLS="build-tools;36.0.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

# Корень проекта = каталог install.sh (если он есть файлом). При запуске через
# `bash <(curl …)` файла рядом нет — сработает bootstrap ниже.
SELF="${BASH_SOURCE[0]:-}"
ROOT=""
if [ -n "$SELF" ] && [ -f "$SELF" ]; then
    ROOT="$(CDPATH= cd -- "$(dirname -- "$SELF")" 2>/dev/null && pwd)"
fi

# ── Справка ──────────────────────────────────────────────────────────────────
usage() {
    cat <<'USAGE'
CodeMobile — установщик VS Code + OpenCode в Termux (одной командой).

  bash install.sh            интерактивно (меню + прогресс-бар)
  bash install.sh -y         без вопросов, всё по умолчанию
  bash install.sh --dry      только показать план
  bash install.sh --no-opencode      без OpenCode
  bash install.sh --no-models        без бесплатных моделей
  bash install.sh --zen-key KEY      Zen-ключ для бесплатных моделей
  bash install.sh --no-zen-key       не спрашивать ключ (вписать позже: mem helper)
  bash install.sh --no-code-server   без code-server
  bash install.sh --no-design        без оформления
  bash install.sh --no-apk           без APK-лаунчера
  bash install.sh --no-hints         без инструкций Xiaomi/Shizuku
  bash install.sh --build            собрать APK прямо в Termux (JDK + Android SDK)
  bash install.sh --no-build         только скачать готовый APK из релиза
  bash install.sh --mirror URL       качать исходники через зеркало-прокси GitHub
  bash install.sh --refresh          перекачать исходники заново
  bash install.sh --help

Важно: ключ можно НЕ вводить сейчас — жми Enter на запросе ключа (или запусти
установщик с --no-zen-key), всё поставится, а ключ впишешь позже: mem helper

Репозиторий можно переопределить: CODEMOBILE_REPO=owner/repo bash install.sh
Зеркала: CODEMOBILE_MIRRORS="https://ghproxy.net/,https://gh-proxy.com/"
USAGE
}

# ── Разбор флагов ────────────────────────────────────────────────────────────
ASSUME_YES=0; DRY=0
WANT_OPENCODE=2; WANT_MODELS=2; WANT_CODESERVER=2; WANT_DESIGN=2; WANT_APK=2; WANT_BUILD=2; WANT_HINTS=1
# Zen-ключ для бесплатных моделей: можно задать заранее (--zen-key / переменная
# окружения ZEN_KEY / OPENCODE_ZEN_KEY), спросить интерактивно или пропустить.
ZEN_KEY="${ZEN_KEY:-${OPENCODE_ZEN_KEY:-}}"
WANT_ZEN_KEY=2
for a in "$@"; do
    case "$a" in
        -y|--yes)        ASSUME_YES=1 ;;
        --dry)           DRY=1 ;;
        --no-opencode)   WANT_OPENCODE=0 ;;
        --no-models)     WANT_MODELS=0 ;;
        --zen-key)       shift; [ $# -gt 0 ] || { echo "--zen-key требует ключ"; exit 2; }
                         ZEN_KEY="$1" ;;
        --no-zen-key)    WANT_ZEN_KEY=0 ;;
        --no-code-server) WANT_CODESERVER=0 ;;
        --no-design)     WANT_DESIGN=0 ;;
        --no-apk)        WANT_APK=0 ;;
        --build)         WANT_BUILD=1 ;;
        --no-build)      WANT_BUILD=0 ;;
        --mirror)        shift; [ $# -gt 0 ] || { echo "--mirror требует URL"; exit 2; }
                         GITHUB_MIRRORS="${GITHUB_MIRRORS:+$GITHUB_MIRRORS,}$1" ;;
        --refresh)       REFRESH_SRC=1 ;;
        --no-hints)      WANT_HINTS=0 ;;
        -h|--help)       usage; exit 0 ;;
        *) echo "неизвестный флаг: $a (см. --help)"; exit 2 ;;
    esac
done

# ── Цвета / вывод ────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; C=$'\033[36m'; B=$'\033[1m'; N=$'\033[0m'; DIM=$'\033[2m'
else
    R=""; G=""; Y=""; C=""; B=""; N=""; DIM=""
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
    printf '%s         VS Code + OpenCode AI в кармане · by 4rlein%s\n\n' "$DIM" "$N"
}

# ── Вопрос (читает с /dev/tty, чтобы работало при curl | bash) ───────────────
ask_yn() {                    # ask_yn "вопрос" [Y|N]
    local q="$1" def="${2:-Y}" ans hint
    hint="[Y/n]"; [ "$def" = N ] && hint="[y/N]"
    if [ "$ASSUME_YES" = 1 ]; then
        printf '  %s?%s %s %s→ auto: %s%s\n' "$C" "$N" "$q" "$DIM" "$def" "$N"
        [ "$def" = Y ]; return
    fi
    while true; do
        if [ -r /dev/tty ]; then
            printf '  %s?%s %s %s ' "$C" "$N" "$q" "$hint"
            read -r ans </dev/tty || ans=""
        else
            ans="$def"
        fi
        ans="${ans:-$def}"
        case "$ans" in
            y|Y|yes|Yes|да|Да) return 0 ;;
            n|N|no|No|нет|Нет) return 1 ;;
            *) echo "    введи y или n" ;;
        esac
    done
}

# ── Zen-ключ (бесплатные модели): спросить / пропустить / записать ───────────
mask_key() {                  # mask_key <key> → ••••••••  (для вывода)
    local k="${1:-}"
    [ -n "$k" ] || { printf '(нет)'; return; }
    if [ "${#k}" -le 12 ]; then printf '••••••••'
    else printf '%s••••••%s' "${k:0:6}" "${k: -4}"; fi
}

# Записать ключ в конфиг zen-proxy (defaultZenKey). node ИЛИ python3.
write_zen_key() {             # write_zen_key <config.json> <key>
    local json="$1" key="$2"
    if command -v node >/dev/null 2>&1; then
        node - "$json" "$key" <<'NODE' || return 1
const fs = require("fs");
const [p, k] = process.argv.slice(2);
let c = {}; try { c = JSON.parse(fs.readFileSync(p, "utf8")); } catch {}
c.defaultZenKey = k;
fs.writeFileSync(p, JSON.stringify(c, null, 2) + "\n");
NODE
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$json" "$key" <<'PY' || return 1
import json, sys
p, k = sys.argv[1], sys.argv[2]
try:
    with open(p, encoding="utf-8") as f: c = json.load(f)
except Exception: c = {}
c["defaultZenKey"] = k
with open(p, "w", encoding="utf-8") as f:
    json.dump(c, f, ensure_ascii=False, indent=2); f.write("\n")
PY
    else
        err "нет node/python3 — ключ не записать (впиши позже: mem helper)"
        return 1
    fi
}

# ── Провайдер Zen Proxy в конфиге OpenCode ─────────────────────────────────────
# Подключаем ТОЛЬКО когда есть ключ. Без ключа провайдер не добавляем, чтобы в
# списке моделей не висели нерабочие «free»-модели: его включит `mem helper`
# после ввода ключа.
zen_provider_ensure() {       # zen_provider_ensure <opencode.json>
    node - "$1" <<'NODE' || return 1
const fs = require("fs");
const p = process.argv[2];
// Мягкий парсинг: сначала обычный JSON (в конфиге есть URL "https://…"), и
// только если он не прошёл — срезаем комментарии/висячие запятые.
function parseCfg(t) {
  try { return JSON.parse(t); } catch {}
  try {
    return JSON.parse(
      t.replace(/^\s*\/\/[^\n]*/gm, "").replace(/\/\*[\s\S]*?\*\//g, "")
       .replace(/,(\s*[}\]])/g, "$1"));
  } catch { return {}; }
}
let t = "";
try { t = fs.readFileSync(p, "utf8"); } catch {}
let cfg = parseCfg(t);
cfg.plugin = Array.isArray(cfg.plugin) ? cfg.plugin : [];
if (!cfg.plugin.includes("openkilo")) cfg.plugin.push("openkilo");
cfg.provider = cfg.provider || {};
if (!cfg.provider["zen-proxy"]) {
  cfg.provider["zen-proxy"] = {
    npm: "@ai-sdk/openai-compatible", name: "Zen Proxy · бесплатные",
    options: { baseURL: "http://127.0.0.1:8787/v1", apiKey: "public" },
    models: { "deepseek-v4.1-flash-free": { name: "DeepSeek V4.1 Flash (free)" } }
  };
}
fs.writeFileSync(p, JSON.stringify(cfg, null, 2) + "\n");
NODE
}

zen_provider_remove() {       # zen_provider_remove <opencode.json>
    node - "$1" <<'NODE' || return 1
const fs = require("fs");
const p = process.argv[2];
function parseCfg(t) {
  try { return JSON.parse(t); } catch {}
  try {
    return JSON.parse(
      t.replace(/^\s*\/\/[^\n]*/gm, "").replace(/\/\*[\s\S]*?\*\//g, "")
       .replace(/,(\s*[}\]])/g, "$1"));
  } catch { return {}; }
}
let t = "";
try { t = fs.readFileSync(p, "utf8"); } catch { process.exit(0); }
let cfg = parseCfg(t);
if (cfg.provider && cfg.provider["zen-proxy"]) delete cfg.provider["zen-proxy"];
fs.writeFileSync(p, JSON.stringify(cfg, null, 2) + "\n");
NODE
}

# Спросить ключ. Ключа может не быть — тогда ПРОСТО ПРОПУСКАЕМ: всё остальное
# ставится, а ключ впишется позже командой `mem helper`.
prompt_zen_key() {
    if [ -n "${ZEN_KEY:-}" ]; then
        ok "Zen-ключ задан ($(mask_key "$ZEN_KEY")) — запишу при настройке Zen-Proxy"
        return 0
    fi
    if [ "$WANT_ZEN_KEY" = 0 ]; then
        info "Zen-ключ: пропущено — впиши позже: mem helper"
        return 0
    fi
    if [ "$ASSUME_YES" = 1 ] || [ ! -r /dev/tty ]; then
        info "Zen-ключ не спрашиваем (неинтерактивно) — впиши позже: mem helper"
        return 0
    fi
    say "  ${C}?${N} Бесплатные модели (Zen) отвечают только с ключом opencode.ai."
    say "    ${DIM}Есть ключ — вставь и Enter. Нет ключа — просто Enter, и мы"
    say "    всё поставим, а ключ впишешь позже командой: ${C}mem helper${DIM}.${N}"
    printf '    Zen-ключ (Enter — пропустить): '
    local k=""
    IFS= read -rs k </dev/tty 2>/dev/null || k=""
    printf '\n'
    k="$(printf '%s' "$k" | tr -d '[:space:]')"
    case "$k" in
        ""|n|N|no|No|нет|Нет|skip|пропустить|-)
            info "ок, пропускаем — впишешь позже: mem helper"
            return 0 ;;
    esac
    ZEN_KEY="$k"
    ok "ключ принят ($(mask_key "$ZEN_KEY")) — впишу в конфиг zen-proxy"
    return 0
}

# ── Прогресс-бар ─────────────────────────────────────────────────────────────
BAR_TOTAL=0
draw_bar() {                 # draw_bar <done> <total> <label>
    local done=$1 total=$2 label=$3 pct fill i bar="" width=26
    [ "$total" -gt 0 ] && pct=$(( done * 100 / total )) || pct=0
    fill=$(( pct * width / 100 ))
    for ((i = 0; i < width; i++)); do
        [ "$i" -lt "$fill" ] && bar+="█" || bar+="░"
    done
    printf '\r  %s[%s]%s %3d%%  %s\033[K' "$C" "$bar" "$N" "$pct" "$label"
}

LOGF="$(mktemp "${TMPDIR:-$PREFIX/tmp}/codemobile.XXXXXX")" 2>/dev/null || LOGF="$HOME_DIR/.cache/codemobile-install.log"
mkdir -p "$(dirname "$LOGF")" 2>/dev/null
trap 'rm -f "$LOGF" 2>/dev/null' EXIT

run_step() {                 # run_step <номер> <метка> <функция>
    local n="$1" label="$2" fn="$3"
    draw_bar "$n" "$BAR_TOTAL" "$label"
    if [ "$DRY" = 1 ]; then
        printf '\r  %s[dry]%s %s\033[K\n' "$Y" "$N" "$label"
        return 0
    fi
    : >"$LOGF"
    if "$fn" >>"$LOGF" 2>&1; then
        printf '\r  %s[ok]%s %s\033[K\n' "$G" "$N" "$label"
        return 0
    else
        printf '\r  %s[XX]%s %s\033[K\n' "$R" "$N" "$label"
        sed 's/^/       /' "$LOGF" | tail -n 10
        FAILS=$((FAILS + 1))
        return 1
    fi
}

FAILS=0

# ═══════════════════════════════════════════════════════════════════════════
#  Самовосстановление: репозитории Termux, имена пакетов, устройство, сеть
#  Пользователь ставит Termux и даёт доступ к файлам — остальное скрипт
#  выясняет про систему сам и обходит её различия без вопросов.
# ═══════════════════════════════════════════════════════════════════════════
DEV_ARCH=""; DEV_API=""; TERMUX_VER=""
APT_BIN=""; APT_REFRESHED=0
TIMEOUT_BIN="$(command -v timeout 2>/dev/null || true)"
# Официальные зеркала репозитория Termux (в порядке предпочтения).
APT_MIRRORS=(
    "https://packages.termux.dev/apt/termux-main"
    "https://packages-cf.termux.dev/apt/termux-main"
    "https://grimler.se/termux/termux-main"
    "https://mirror.mwt.me/termux/main"
)

detect_device() {
    DEV_ARCH="$(uname -m 2>/dev/null || echo '?')"
    TERMUX_VER="${TERMUX_VERSION:-?}"
    DEV_API="$(getprop ro.build.version.sdk 2>/dev/null | tr -cd '0-9')"
    [ -n "$DEV_API" ] || DEV_API="?"
}

apt_bin() {
    [ -n "$APT_BIN" ] && return 0
    if command -v apt >/dev/null 2>&1; then APT_BIN=apt
    elif command -v pkg >/dev/null 2>&1; then APT_BIN=pkg
    else return 1; fi
}

apt_dpkg_heal() {                       # добить прерванные установки (dpkg lock)
    command -v dpkg >/dev/null 2>&1 && dpkg --configure -a >/dev/null 2>&1 || true
    return 0
}

_apt_update() {                         # _apt_update <секунды> [флаги…] → вывод
    local t="$1"; shift
    if [ -n "$TIMEOUT_BIN" ]; then LC_ALL=C "$TIMEOUT_BIN" "$t" "$APT_BIN" update "$@" 2>&1
    else LC_ALL=C "$APT_BIN" update "$@" 2>&1; fi
}

# apt возвращает 0 даже при сбое скачивания, поэтому судим по выводу и по тому,
# появились ли файлы индекса (*_Packages) в изолированном каталоге.
apt_iso_ok() {                          # apt_iso_ok <секунды> <sourcelist-файл>
    local ld out okres=1
    ld="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/codemobile-apt.XXXXXX")" || return 1
    out="$(_apt_update "$1" -o Dir::Etc::sourcelist="$2" \
               -o Dir::Etc::sourceparts="-" -o Dir::State::lists="$ld")"
    grep -qiE 'failed to (fetch|download)' <<<"$out" && okres=0
    find "$ld" -name '*_Packages*' 2>/dev/null | grep -q . || okres=0
    rm -rf "$ld"
    [ "$okres" = 1 ]
}

apt_fix_mirror() {                      # переключить основной репо на рабочее зеркало
    local sl="$PREFIX/etc/apt/sources.list" m try
    [ -w "$sl" ] || [ -w "$(dirname "$sl")" ] || return 1
    try="$PREFIX/etc/apt/codemobile-mirror.list"
    for m in "${APT_MIRRORS[@]}"; do
        printf 'deb %s stable main\n' "$m" >"$try" 2>/dev/null || continue
        if apt_iso_ok 180 "$try"; then
            [ -f "$sl.codemobile.bak" ] || cp -p "$sl" "$sl.codemobile.bak" 2>/dev/null
            printf 'deb %s stable main\n' "$m" >"$sl"
            rm -f "$try"
            ok "репозиторий Termux переключён на $m"
            return 0
        fi
    done
    rm -f "$try"
    return 1
}

apt_refresh() {
    [ "$APT_REFRESHED" = 1 ] && return 0
    apt_bin || return 1
    apt_dpkg_heal
    info "обновляю списки пакетов Termux…"
    local out; out="$(_apt_update 300)"
    if ! grep -qiE 'failed to (fetch|download)' <<<"$out"; then APT_REFRESHED=1; return 0; fi
    # Сбои были: если основной репо жив — виноваты сторонние (tur/opencode).
    if apt_iso_ok 180 "$PREFIX/etc/apt/sources.list"; then APT_REFRESHED=1; return 0; fi
    warn "основной репозиторий Termux не отвечает — подбираю рабочее зеркало…"
    if apt_fix_mirror; then _apt_update 300 >/dev/null 2>&1 || true; APT_REFRESHED=1; return 0; fi
    warn "репозитории Termux недоступны — продолжаю"
    return 1
}

pkg_install() {                         # pkg_install <пакеты…>
    apt_bin || return 1
    [ "$APT_REFRESHED" = 1 ] || apt_refresh >/dev/null 2>&1 || true
    if [ -n "$TIMEOUT_BIN" ]; then "$TIMEOUT_BIN" 900 "$APT_BIN" install -y "$@"
    else "$APT_BIN" install -y "$@"; fi
}

# Поставить пакет, перебрав варианты имени: в разных сборках Termux они разные
# (nodejs-lts ↔ nodejs, python ↔ python3, openjdk-21 ↔ openjdk-17).
pkg_ensure() {                          # pkg_ensure <проверка-команда> <пакет…>
    local chk="$1"; shift
    command -v "$chk" >/dev/null 2>&1 && return 0
    local i p
    for i in 1 2; do
        for p in "$@"; do
            pkg_install "$p" >/dev/null 2>&1 || continue
            command -v "$chk" >/dev/null 2>&1 && return 0
        done
        apt_dpkg_heal
    done
    return 1
}

# ── Bootstrap: если запущено из pipe (`curl | bash`), tools/ рядом нет ───────
#     Скачиваем архив проекта сами: git и python для этого не нужны.
#     Зеркало → зеркало: если GitHub недоступен, пробуем ghproxy/gh-proxy/ghfast.
DL_TOOL=""
pick_dl() {
    if   command -v curl    >/dev/null 2>&1; then DL_TOOL=curl
    elif command -v wget    >/dev/null 2>&1; then DL_TOOL=wget
    elif command -v python3 >/dev/null 2>&1; then DL_TOOL=python3
    elif command -v node    >/dev/null 2>&1; then DL_TOOL=node
    else DL_TOOL=""
    fi
}

ensure_dl() {
    pick_dl
    [ -n "$DL_TOOL" ] && return 0
    info "нет curl/wget/python3 — доставляю curl пакетом…"
    pkg_ensure curl curl || true
    pick_dl
    [ -n "$DL_TOOL" ] && return 0
    err "нечем качать: поставь curl → pkg install curl"
    return 1
}

cm_download() {                      # cm_download <url> <файл>
    local url="$1" file="$2"
    rm -f "$file" 2>/dev/null
    case "$DL_TOOL" in
        curl)
            if [ -t 1 ]; then
                curl -fL --progress-bar --retry 4 --retry-delay 2 \
                     --speed-limit 1024 --speed-time 45 \
                     --connect-timeout 20 --max-time 900 -o "$file" "$url"
            else
                curl -fsSL --retry 4 --retry-delay 2 \
                     --speed-limit 1024 --speed-time 45 \
                     --connect-timeout 20 --max-time 900 -o "$file" "$url"
            fi ;;
        wget) wget -O "$file" --tries=3 --timeout=30 -q --show-progress "$url" ;;
        python3)
            python3 - "$url" "$file" <<'PY'
import sys, urllib.request
url, out = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={"User-Agent": "CodeMobile/1.0"})
with urllib.request.urlopen(req, timeout=60) as r, open(out, "wb") as f:
    while True:
        b = r.read(65536)
        if not b: break
        f.write(b)
PY
            ;;
        node)
            node -e '
const fs=require("fs");const [u,o]=process.argv.slice(1);
fetch(u,{redirect:"follow"}).then(async r=>{if(!r.ok){console.error("HTTP "+r.status);process.exit(1)}
fs.writeFileSync(o,Buffer.from(await r.arrayBuffer()))}).catch(e=>{console.error(String(e));process.exit(1)});' "$url" "$file" ;;
        *) return 1 ;;
    esac
}

# Список адресов архива: GitHub → jsDelivr-нет (только файлы) → зеркала-прокси
archive_urls() {
    local tail="$1"            # например: archive/refs/heads/main.tar.gz
    printf '%s\n' "https://codeload.github.com/$GITHUB_REPO/tar.gz/refs/heads/$GITHUB_BRANCH"
    printf '%s\n' "https://github.com/$GITHUB_REPO/$tail"
    local m
    for m in $(printf '%s' "$GITHUB_MIRRORS" | tr ',' ' '); do
        [ -n "$m" ] || continue
        printf '%shttps://github.com/%s/%s\n' "$m" "$GITHUB_REPO" "$tail"
        printf '%shttps://codeload.github.com/%s/tar.gz/refs/heads/%s\n' "$m" "$GITHUB_REPO" "$GITHUB_BRANCH"
    done
}

bootstrap_self() {
    if [ -n "$ROOT" ] && [ -f "$ROOT/tools/settings.seed.json" ]; then
        cd "$ROOT" || return 1
        return 0
    fi
    if [ -n "${CODEMOBILE_SRC:-}" ] && [ -f "$CODEMOBILE_SRC/tools/settings.seed.json" ]; then
        cd "$CODEMOBILE_SRC" || return 1
        return 0
    fi
    # исходники уже скачаны ранее (например, установщиком get.sh) — берём их
    if [ "$REFRESH_SRC" = 0 ] && [ -f "$SRC_DIR/tools/settings.seed.json" ]; then
        say "${DIM}Bootstrap: использую ранее скачанные исходники.${N}"
        cd "$SRC_DIR" || return 1
        return 0
    fi

    say "${DIM}Bootstrap: исходников рядом нет — скачиваю архив проекта…${N}"
    ensure_dl || return 1

    local tmp
    tmp="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/codemobile.XXXXXX")" || return 1

    local arc; arc="$(fetch_archive "$tmp")"
    if [ -z "$arc" ]; then
        err "не удалось скачать $GITHUB_REPO ($GITHUB_BRANCH) ни с GitHub, ни с зеркал"
        rm -rf "$tmp"
        return 1
    fi

    mkdir -p "$SRC_DIR" || { rm -rf "$tmp"; return 1; }
    case "$arc" in
        *.zip) unzip -qq "$arc" -d "$tmp" || { rm -rf "$tmp"; return 1; } ;;
        *)     tar -xzf "$arc" -C "$tmp"  || { rm -rf "$tmp"; return 1; } ;;
    esac
    # в архиве один каталог верхнего уровня: repo-branch
    local top="" kind
    for kind in "$tmp"/*/; do [ -d "$kind" ] && { top="$kind"; break; }; done
    if [ -z "$top" ] || [ ! -f "$top/install.sh" ]; then
        err "в архиве нет каталога проекта (ветка $GITHUB_BRANCH верная?)"
        rm -rf "$tmp"
        return 1
    fi
    cp -R "$top". "$SRC_DIR"/ || { err "не скопировалось в $SRC_DIR"; rm -rf "$tmp"; return 1; }
    rm -rf "$tmp"
    say "${G}✓${N} исходники в $SRC_DIR"
    CODEMOBILE_SRC="$SRC_DIR" exec bash "$SRC_DIR/install.sh" "$@"
}

# ── Сеть: замер зеркал, проверка архива, авто-фолбэк tar.gz → zip ────────────
_archive_ok() {                         # _archive_ok <файл> <tar|zip>
    local f="$1" kind="$2" magic sz
    [ -s "$f" ] || return 1
    sz="$(wc -c <"$f" 2>/dev/null | tr -d ' ')"
    [ -n "$sz" ] && [ "$sz" -gt 2048 ] || return 1
    magic="$(dd if="$f" bs=1 count=2 2>/dev/null | od -An -tx1 | tr -d ' \n')"
    case "$magic" in 3c*) return 1 ;; esac          # '<' — HTML-заглушка прокси
    if [ "$kind" = zip ]; then
        case "$magic" in 504b) ;; *) return 1 ;; esac
        unzip -tqq "$f" >/dev/null 2>&1
    else
        case "$magic" in 1f8b) ;; *) return 1 ;; esac
        gzip -t "$f" >/dev/null 2>&1
    fi
}

order_urls() {                          # stdin: URL-ы → stdout: быстрые первыми
    local u t
    [ "$DL_TOOL" = curl ] || { cat; return 0; }
    while IFS= read -r u; do
        [ -n "$u" ] || continue
        t="$(curl -o /dev/null -s -w '%{time_total}' -r 0-2047 \
                  --connect-timeout 3 --max-time 5 -fL "$u" 2>/dev/null)" || t=9.9
        printf '%s %s\n' "${t:-9.9}" "$u"
    done | sort -n | awk '{ print $2 }'
}

# Скачать архив проекта: перебирает tar.gz, затем zip, по быстрым зеркалам.
fetch_archive() {                       # fetch_archive <tmpdir> → путь к архиву
    local tmp="$1" kind tail arc urls u
    for kind in tar zip; do
        if [ "$kind" = zip ]; then tail="archive/refs/heads/$GITHUB_BRANCH.zip";     arc="$tmp/repo.zip"
        else                        tail="archive/refs/heads/$GITHUB_BRANCH.tar.gz"; arc="$tmp/repo.tar.gz"; fi
        urls="$(archive_urls "$tail")"
        if [ "$DL_TOOL" = curl ]; then
            info "замеряю зеркала и выбираю быстрейшее…" >&2
            urls="$(printf '%s\n' "$urls" | order_urls)"
        fi
        while IFS= read -r u; do
            [ -n "$u" ] || continue
            info "пробую: $u" >&2
            if cm_download "$u" "$arc" && _archive_ok "$arc" "$kind"; then
                printf '%s\n' "$arc"; return 0
            fi
            warn "не вышло — пробую следующее зеркало…" >&2
        done <<<"$urls"
        [ "$kind" = tar ] && warn "tar.gz не удалось — пробую zip-архив…" >&2
    done
    return 1
}

# ── install_file SRC DST MODE — идемпотентная установка ──────────────────────
install_file() {
    local src="$1" dst="$2" mode="$3"
    if [ ! -f "$src" ]; then err "нет источника: $src"; return 1; fi
    if [ -f "$dst" ] && cmp -s "$src" "$dst"; then info "$dst — уже на месте"; return 0; fi
    if [ "$DRY" = 1 ]; then info "[dry] обновил бы $dst"; return 0; fi
    mkdir -p "$(dirname "$dst")"
    [ -f "$dst" ] && cp -p "$dst" "$dst.bak-install"
    cp "$src" "$dst" && chmod "$mode" "$dst" && ok "$dst"
}

# =============================================================================
#  Задачи установки
# =============================================================================
task_env() {
    [ -d "$PREFIX" ] || { echo "PREFIX=$PREFIX не найден — это не Termux?"; return 1; }
    command -v pkg >/dev/null 2>&1 || command -v apt >/dev/null 2>&1 \
        || { echo "нет pkg/apt — это не Termux?"; return 1; }
    detect_device
    echo "Termux OK: $PREFIX · $DEV_ARCH · Android API $DEV_API · Termux $TERMUX_VER"
}

# Доступ к памяти устройства. Диалог Android требует одного нажатия «Разрешить»,
# поэтому ждём его появления (до 30 с), а не фиксированную паузу.
task_storage() {
    local st="$HOME_DIR/storage" i
    if [ -d "$st/shared" ]; then
        echo "доступ к памяти уже есть"; _storage_probe; return 0
    fi
    command -v termux-setup-storage >/dev/null 2>&1 \
        || { echo "нет termux-setup-storage (это точно Termux?)"; return 0; }
    echo "запрашиваю доступ к памяти — на экране подтверди «Разрешить»…"
    termux-setup-storage >/dev/null 2>&1 || true
    for i in $(seq 1 30); do [ -d "$st/shared" ] && break; sleep 1; done
    if [ -d "$st/shared" ]; then
        echo "доступ есть"; _storage_probe
    else
        echo "[!!] доступ к памяти пока не получен — установка продолжится,"
        echo "     но APK не попадёт в Download. Позже: termux-setup-storage"
        _storage_hint
    fi
    return 0
}

_storage_probe() {                      # можно ли писать в общий Download
    local d="$HOME_DIR/storage/shared/Download"
    if [ -d "$d" ] && : >"$d/.codemobile-write-test" 2>/dev/null; then
        rm -f "$d/.codemobile-write-test" 2>/dev/null
        echo "запись в Download: ок"
    else
        echo "запись в Download недоступна (возможно, нужен доступ «Все файлы»)"
    fi
}

_storage_hint() {                       # подсказка с учётом версии Android
    echo "  Android ${DEV_API}: Настройки → Приложения → Termux → Разрешения →"
    echo "  «Файлы и медиа/Хранилище» → «Управление всеми файлами», затем"
    echo "  termux-setup-storage. На HyperOS/MIUI — «Всегда разрешать»."
}

task_pkg() {
    apt_refresh || true
    # tur-repo нужен части пакетов; если не встал — установку не валим.
    if ! dpkg -s tur-repo >/dev/null 2>&1; then
        pkg_install tur-repo || echo "[!!] tur-repo не встал (продолжаю)"
        APT_REFRESHED=0
        apt_refresh || true
    fi
    echo "tur-repo: $(dpkg -s tur-repo >/dev/null 2>&1 && echo 'установлен' || echo 'нет')"
}

# ── Зависимости ──────────────────────────────────────────────────────────────
# Ставим по одному и не валим установку из-за одного пакета: git/unzip нужны
# только части сценариев, а python и node — для ИИ-ассистента и оформления.
# Python/node НЕ нужны для скачивания проекта (это делает bootstrap сам).
task_deps() {
    apt_refresh
    echo "нужны пакеты Termux: python — оформление и мерж настроек,"
    echo "node — ИИ-ассистент и бесплатные модели, unzip/tar — архивы."
    pkg_ensure curl    curl              || true
    pkg_ensure python3 python            || echo "[!!] python не встал — оформление пропустится"
    pkg_ensure node    nodejs-lts nodejs || echo "[!!] node не встал — ИИ-ассистенту понадобится позже"
    pkg_ensure unzip   unzip             || true
    pkg_ensure tar     tar               || true
    pkg_ensure gzip    gzip              || true
    pkg_ensure git     git               || true
    command -v curl >/dev/null 2>&1 || { echo "нет curl — скачивание с зеркал будет недоступно"; }
    command -v python3 >/dev/null 2>&1 && python3 --version
    command -v node >/dev/null 2>&1 && node -v
    return 0
}

# aapt2 из Termux линкуется с libprotobuf: при рассинхроне версий пакетов он не
# запускается («cannot locate symbol … protobuf …»), хотя файл на месте. Поэтому
# проверяем ЗАПУСКОМ, а не наличием, и лечим выравниванием версий.
aapt2_ok() { command -v aapt2 >/dev/null 2>&1 && aapt2 version >/dev/null 2>&1; }

fix_aapt2() {
    aapt2_ok && return 0
    info "aapt2 не запускается — выравниваю версии aapt2/libprotobuf…"
    pkg_install libprotobuf >/dev/null 2>&1 || true
    aapt2_ok && return 0
    pkg_install aapt2 libprotobuf >/dev/null 2>&1 || true
    apt_dpkg_heal
    aapt2_ok
}

# ── Режим сборки: JDK 21 + нативный aapt2 ────────────────────────────────────
task_toolchain() {
    # JDK 21 (AGP 9.x требует 17+) — версии перебираем. aapt2 ставим ИЗ TERMUX:
    # версия из Maven/SDK собрана под x86_64 и на телефоне (arm64) не стартует.
    pkg_ensure java  openjdk-21 openjdk-17 || { echo "нет java (JDK)"; return 1; }
    pkg_ensure aapt2 aapt2 || { echo "нет aapt2"; return 1; }
    pkg_ensure unzip unzip || true
    fix_aapt2 || { echo "aapt2 не запускается даже после обновления libprotobuf"; return 1; }
    if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME:-/nonexistent}" ]; then
        local d
        for d in "$PREFIX"/lib/jvm/*/; do [ -d "$d" ] && JAVA_HOME="${d%/}" && break; done
    fi
    export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}"
    java -version
    aapt2 version
}

# ── Режим сборки: Android SDK через sdkmanager из command-line tools ─────────
task_sdk() {
    export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}"
    local sm="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

    if [ ! -x "$sm" ]; then
        echo "качаю Android command-line tools…"
        local tmp; tmp="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/cmdtools.XXXXXX")" || return 1
        curl -fL --retry 3 -o "$tmp/cmdline.zip" "$CMDLINE_TOOLS_URL" || { rm -rf "$tmp"; return 1; }
        mkdir -p "$SDK_DIR/cmdline-tools"
        rm -rf "$SDK_DIR/cmdline-tools/latest" "$SDK_DIR/cmdline-tools/cmdline-tools"
        unzip -q "$tmp/cmdline.zip" -d "$SDK_DIR/cmdline-tools" || { rm -rf "$tmp"; return 1; }
        # в архиве каталог верхнего уровня тоже зовётся cmdline-tools → переименуем в latest
        if [ -d "$SDK_DIR/cmdline-tools/cmdline-tools" ] && [ ! -d "$SDK_DIR/cmdline-tools/latest" ]; then
            mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
        fi
        rm -rf "$tmp"
    fi
    [ -x "$sm" ] || { echo "sdkmanager не установился"; return 1; }

    yes | "$sm" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
    "$sm" --sdk_root="$SDK_DIR" "$SDK_PLATFORM" "$SDK_BUILDTOOLS" 2>&1 | tail -n 3

    [ -f "$SDK_DIR/platforms/android-34/android.jar" ] || { echo "нет platforms/android-34/android.jar"; return 1; }
    [ -x "$SDK_DIR/build-tools/36.0.0/aapt2" ]         || { echo "нет build-tools/36.0.0"; return 1; }
    echo "SDK готов: $SDK_DIR"
}

# ── Режим сборки: связать Gradle с SDK и нативным aapt2 ──────────────────────
task_gradle_config() {
    printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties || { echo "не пишется local.properties"; return 1; }

    local aapt2 gp="gradle.properties"
    aapt2="$(command -v aapt2 || true)"
    [ -n "$aapt2" ] || { echo "нет aapt2 (pkg install aapt2)"; return 1; }
    if grep -q '^android\.aapt2FromMavenOverride=' "$gp" 2>/dev/null; then
        sed -i "s|^android\.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$aapt2|" "$gp"
    else
        printf '\n# добавлено CodeMobile: нативный aapt2 (x86_64 из SDK на arm64 не стартует)\nandroid.aapt2FromMavenOverride=%s\n' "$aapt2" >>"$gp"
    fi
    echo "sdk.dir=$SDK_DIR"
    echo "aapt2=$aapt2"
}

# ── Режим сборки: собрать APK и положить в Download ──────────────────────────
task_build_apk() {
    command -v java >/dev/null 2>&1 || { echo "нет java — сначала шаг «набор для сборки»"; return 1; }
    local dl="${DL_DIR:-$HOME_DIR/storage/shared/Download}" extra=""
    [ -d "$dl" ] || extra="--no-copy"   # нет доступа к памяти — соберём в app/build
    bash tools/build_apk.sh --release --allow-existing $extra
}

task_opencode() {
    pkg_install opencode2 || return 1
    install_file tools/opencode2 "$PREFIX/bin/opencode2" 755
    [ -x "$PREFIX/libexec/opencode2/opencode2.bin" ] \
        && echo "бинарь на месте: $PREFIX/libexec/opencode2/opencode2.bin" \
        || echo "нет opencode2.bin (установится пакетом; враппер подождёт)"
}

task_opencode_config() {
    local dir="$HOME_DIR/.config/opencode" cfg="$HOME_DIR/.config/opencode/opencode.json"
    mkdir -p "$dir"
    local fresh=0
    if [ ! -f "$cfg" ]; then
        install_file tools/opencode.json.seed "$cfg" 644 || return 1
        fresh=1
    else
        # неразрушающий мерж: копия на случай отката
        cp -p "$cfg" "$cfg.bak-install" 2>/dev/null
    fi
    # Провайдер Zen Proxy подключаем ТОЛЬКО если есть ключ. Нет ключа — не
    # оставляем нерабочие free-модели: провайдер включит `mem helper` после
    # ввода ключа (см. zen_provider_ensure/remove выше).
    if [ -n "${ZEN_KEY:-}" ]; then
        zen_provider_ensure "$cfg" || return 1
        echo "провайдер Zen Proxy подключён (есть ключ)"
    elif [ "$fresh" = 1 ]; then
        zen_provider_remove "$cfg" || return 1
        echo "провайдер Zen Proxy пока не подключён (нет ключа) — включит: mem helper"
    else
        echo "провайдер Zen Proxy не трогаем (ключа нет); включить: mem helper"
    fi
    # плагин openkilo (бесплатные модели Kilo Gateway)
    npm install --prefix "$dir" openkilo >/dev/null 2>&1 \
        && echo "openkilo установлен" || echo "openkilo: npm пропущен (opencode подтянет сам)"
    if [ -n "${ZEN_KEY:-}" ]; then
        echo "провайдеры: openkilo (Kilo free) + zen-proxy (:8787)"
    else
        echo "провайдеры: openkilo (Kilo free); zen-proxy — после mem helper"
    fi
}

task_zenproxy() {
    local zen="$HOME_DIR/.zen-proxy" tmp u got=0
    tmp="$(mktemp "${TMPDIR:-$PREFIX/tmp}/zenproxy.XXXXXX")" || return 1
    ensure_dl >/dev/null 2>&1 || { rm -f "$tmp"; return 1; }
    # напрямую + через зеркала (репозиторий zen-proxy — сторонний)
    for u in "https://raw.githubusercontent.com/12errh/zen-proxy/main/install.sh" \
             $(printf '%s' "$GITHUB_MIRRORS" | tr ',' '\n' | while read -r m; do
                  [ -n "$m" ] && printf '%shttps://raw.githubusercontent.com/12errh/zen-proxy/main/install.sh\n' "$m"
               done); do
        [ -n "$u" ] || continue
        if cm_download "$u" "$tmp" && [ -s "$tmp" ]; then got=1; break; fi
    done
    if [ "$got" = 1 ] && ZEN_PROXY_DIR="$zen" bash "$tmp"; then
        rm -f "$tmp"
    else
        rm -f "$tmp"
        echo "[!!] zen-proxy не встал (недоступен GitHub/зеркала) — бесплатные модели"
        echo "     из OpenCode Zen подключатся позже: codemobile update && bash install.sh"
    fi
    [ -f "$zen/zen-proxy.json" ] || install_file tools/zen-proxy.json.seed "$zen/zen-proxy.json" 644
    # Zen-ключ: если пользователь его дал (или ввёл при установке) — пишем сюда.
    # Если ключа нет — НЕ ломаем установку: всё на месте, ключ впишется позже.
    if [ -n "${ZEN_KEY:-}" ]; then
        if write_zen_key "$zen/zen-proxy.json" "$ZEN_KEY"; then
            echo "Zen-ключ записан ($(mask_key "$ZEN_KEY")) → $zen/zen-proxy.json"
        else
            echo "[!!] ключ не записался — впиши позже: mem helper"
        fi
    else
        echo "Zen-ключ не задан — бесплатные модели включатся после: mem helper"
    fi
    echo "zen-proxy: $zen"
    return 0
}

task_codeserver() {
    pkg_install code-server || return 1
    local cfg="$HOME_DIR/.config/code-server/config.yaml"
    if [ ! -f "$cfg" ]; then
        local pw; pw="$(LC_ALL=C tr -dc '0-9' </dev/urandom | head -c 6)"
        mkdir -p "$(dirname "$cfg")"
        if [ "$DRY" = 1 ]; then
            echo "[dry] создал бы $cfg (auth: password, пароль: случайный)"
        else
            cat >"$cfg" <<EOF
bind-addr: 127.0.0.1:8080
auth: password
password: $pw
cert: false
EOF
            echo "конфиг code-server создан (пароль: $pw)"
        fi
    else
        echo "конфиг code-server уже есть — пароль берём из него"
    fi
}

task_shortcuts() {
    install_file tools/shortcuts/OpenCode-All  "$HOME_DIR/.shortcuts/OpenCode-All"  700
    install_file tools/shortcuts/OpenCode-Stop "$HOME_DIR/.shortcuts/OpenCode-Stop" 700
    install_file tools/shortcuts/OpenCode-Watch "$HOME_DIR/.shortcuts/OpenCode-Watch" 700
    install_file tools/opencode2 "$PREFIX/bin/opencode2" 755
    install_file tools/codemobile "$PREFIX/bin/codemobile" 755
    install_file tools/mem "$PREFIX/bin/mem" 755
    install_file tools/merge_settings.py "$HOME_DIR/.local/share/codemobile/merge_settings.py" 644
    # termux.properties: allow-external-apps (лаунчеру нужно для RUN_COMMAND)
    local prop="$HOME_DIR/.termux/termux.properties"
    if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$prop" 2>/dev/null; then
        info "termux.properties — allow-external-apps уже включён"
    elif [ "$DRY" = 1 ]; then
        info "[dry] добавил бы allow-external-apps = true в $prop"
    else
        mkdir -p "$(dirname "$prop")"
        [ -f "$prop" ] && cp -p "$prop" "$prop.bak-install"
        printf '\n# добавлено CodeMobile — лаунчеру нужно для RUN_COMMAND\nallow-external-apps = true\n' >>"$prop"
        ok "termux.properties — allow-external-apps = true (нужен termux-reload-settings)"
    fi
}

task_design() {
    local settings="$HOME_DIR/.local/share/code-server/User/settings.json"
    local ms="$HOME_DIR/.local/share/codemobile/merge_settings.py"
    if ! command -v python3 >/dev/null 2>&1; then
        echo "[!!] python3 не установлен — оформление пропущено"
        echo "     поставь и повтори: pkg install python && bash install.sh"
        return 0
    fi
    if [ -f "$ms" ]; then
        python3 "$ms" tools/settings.seed.json "$settings"
    else
        python3 tools/merge_settings.py tools/settings.seed.json "$settings"
    fi
    echo "оформление: матовая прозрачность + курсор #FF2D55 + шрифты"
}

task_apk() {
    local dl="$HOME_DIR/storage/shared/Download"
    [ -d "$dl" ] || dl="$HOME_DIR"          # нет доступа к памяти — кладём в ~
    local apk=""
    # 1) локальная сборка (режим разработчика)
    for cand in "${CODEMOBILE_APK:-}" app/build/outputs/apk/release/app-release.apk \
                app/build/outputs/apk/debug/app-debug.apk; do
        [ -n "$cand" ] && [ -f "$cand" ] && { apk="$cand"; break; }
    done
    # 2) последний релиз на GitHub (напрямую, затем через зеркала)
    if [ -z "$apk" ]; then
        local url="" api
        for api in "https://api.github.com/repos/$GITHUB_REPO/releases/latest" \
                   $(printf '%s' "$GITHUB_MIRRORS" | tr ',' '\n' | while read -r m; do
                        [ -n "$m" ] && printf '%s%s\n' "$m" "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
                     done); do
            [ -n "$api" ] || continue
            url="$(cm_download "$api" "$HOME_DIR/.cache/codemobile-release.json" >/dev/null 2>&1 \
                   && grep -oE '"browser_download_url":[[:space:]]*"[^"]+\.apk"' "$HOME_DIR/.cache/codemobile-release.json" \
                   | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
            [ -n "$url" ] && break
        done
        if [ -n "$url" ]; then
            apk="$dl/CodeMobile-latest.apk"
            echo "качаю APK: $url"
            ensure_dl >/dev/null 2>&1
            # Релизные файлы GitHub поддерживают докачку (в отличие от архива
            # исходников) → -C - позволяет продолжить после обрыва сети.
            if [ "$DL_TOOL" = curl ]; then
                : >>"$apk"
                curl -fL --retry 4 --retry-delay 2 -C - --connect-timeout 20 \
                     --max-time 1800 -o "$apk" "$url" >/dev/null 2>&1 || apk=""
            else
                cm_download "$url" "$apk" >/dev/null 2>&1 || apk=""
            fi
        fi
    fi
    if [ -z "$apk" ] || [ ! -f "$apk" ]; then
        echo "APK не найден (нет релиза с .apk). Собери сам: bash tools/build_apk.sh --release"
        echo "или скачай вручную со страницы Releases репозитория $GITHUB_REPO"
        return 0
    fi
    ok "APK: $apk ($(du -h "$apk" | cut -f1))"
    if [ "$DRY" = 1 ]; then info "[dry] запустил бы установку $apk"; return 0; fi
    # 1) shell-права: root/adb или Shizuku (rish) — ставим без диалога
    local pmbin=""
    command -v pm >/dev/null 2>&1 && pmbin="pm"
    if [ -z "$pmbin" ]; then
        if   [ -x "$HOME_DIR/rish" ]; then pmbin="$HOME_DIR/rish pm"
        elif command -v rish >/dev/null 2>&1; then pmbin="rish pm"
        fi
    fi
    if [ -n "$pmbin" ] && $pmbin install -r "$apk" >/dev/null 2>&1 \
       && $pmbin list packages 2>/dev/null | grep -q "^package:$PKG_APP\$"; then
        ok "APK установлен без диалога (через $pmbin) — иконка в меню"
        return 0
    fi
    # 2) системный установщик Android: нужен доступ Termux «Установка приложений»
    pkg_ensure termux-open termux-tools >/dev/null 2>&1 || true
    echo "открываю системный установщик Android…"
    if command -v termux-open >/dev/null 2>&1; then
        termux-open --content-type application/vnd.android.package-archive "$apk" 2>/dev/null \
            || termux-open "$apk" || true
        echo "Подтверди установку на экране телефона (кнопка «Установить»)."
        echo "Нет окна? Android ${DEV_API}: Настройки → Приложения → Termux →"
        echo "  «Установка неизвестных приложений» → Разрешить, затем:"
        echo "  termux-open \"$apk\""
    else
        echo "termux-open недоступен — открой APK из файлового менеджера:"
        echo "  $apk"
    fi
    # 3) запасные пути
    echo "Другие способы:"
    echo "  • файловый менеджер: $apk"
    echo "  • Shizuku:  rish pm install -r \"$apk\""
    echo "  • с ПК:     adb install -r \"$apk\""
    return 0
}

task_doctor() {
    command -v codemobile >/dev/null 2>&1 && codemobile doctor || echo "codemobile не установлен"
}

# Android 12+ (API 31+) убивает дочерние процессы («phantom process killer»).
# Если есть rish (Shizuku) — выключаем автоматически, иначе даём точную команду.
phantom_check() {
    case "${DEV_API:-?}" in ''|*[!0-9]*) echo "phantom killer: версия Android неизвестна — пропускаю"; return 0 ;; esac
    [ "$DEV_API" -ge 31 ] || { echo "phantom killer: не нужен (Android API $DEV_API)"; return 0; }
    local rish=""
    if   [ -x "$HOME_DIR/rish" ]; then rish="$HOME_DIR/rish"
    elif command -v rish >/dev/null 2>&1; then rish="$(command -v rish)"
    fi
    if [ -n "$rish" ]; then
        if "$rish" settings put global settings_enable_monitor_phantom_procs false >/dev/null 2>&1; then
            echo "phantom killer: отключён через rish/Shizuku ✓"; return 0
        fi
    fi
    echo "phantom killer: Android API $DEV_API может убивать фоновые процессы."
    echo "  Выключить: rish settings put global settings_enable_monitor_phantom_procs false"
    echo "  (нужен Shizuku; подробности — в подсказках ниже)"
}

# =============================================================================
#  Bootstrap + меню + запуск
# =============================================================================
banner
if ! bootstrap_self "$@"; then
    err "bootstrap не удался — проверь интернет и имя репозитория ($GITHUB_REPO)"
    exit 1
fi

say "${B}Проверка окружения…${N}"
if ! task_env; then err "нужен Termux (Android). Останавливаюсь."; exit 1; fi
say ""

# ── Меню (вопросы) ───────────────────────────────────────────────────────────
say "${B}Что установить?${N} (Enter = значение по умолчанию)"
[ "$WANT_OPENCODE"  = 2 ] && { ask_yn "Установить ИИ-ассистент OpenCode?" Y && WANT_OPENCODE=1 || WANT_OPENCODE=0; }
if [ "$WANT_OPENCODE" = 1 ]; then
    [ "$WANT_MODELS" = 2 ] && { ask_yn "  └ бесплатные модели (OpenKilo + Zen-Proxy) с автонастройкой?" Y && WANT_MODELS=1 || WANT_MODELS=0; }
    [ "$WANT_MODELS" = 1 ] && prompt_zen_key
else
    WANT_MODELS=0
fi
[ "$WANT_CODESERVER" = 2 ] && { ask_yn "Установить VS Code в браузере (code-server)?" Y && WANT_CODESERVER=1 || WANT_CODESERVER=0; }
if [ "$WANT_CODESERVER" = 1 ]; then
    [ "$WANT_DESIGN" = 2 ] && { ask_yn "  └ применить оформление (стиль Apple, шрифты, курсор)?" Y && WANT_DESIGN=1 || WANT_DESIGN=0; }
else
    WANT_DESIGN=0
fi
[ "$WANT_APK" = 2 ] && { ask_yn "Скачать и установить APK-лаунчер (иконка «VS Code» на телефоне)?" Y && WANT_APK=1 || WANT_APK=0; }
[ "$WANT_BUILD" = 2 ] && { ask_yn "Собрать APK прямо в Termux? (JDK + Android SDK ≈ 1 ГБ, для разработки)" N && WANT_BUILD=1 || WANT_BUILD=0; }
[ "$WANT_HINTS" = 1 ] && { ask_yn "Показать подсказки Xiaomi/Shizuku (чтобы фон не убивался)?" Y || WANT_HINTS=0; }
say ""

# ── Собираю план ─────────────────────────────────────────────────────────────
STEPS=(); LABELS=()
add() { STEPS+=("$2"); LABELS+=("$1"); }

add "Адаптация системы: репозитории Termux + пакеты" task_pkg
add "Зависимости" task_deps
[ "$WANT_BUILD" = 1 ]    && add "Набор для сборки: JDK 21 + aapt2" task_toolchain
[ "$WANT_BUILD" = 1 ]    && add "Android SDK (платформа 34, build-tools 36)" task_sdk
[ "$WANT_BUILD" = 1 ]    && add "Настройка SDK и aapt2 для Gradle" task_gradle_config
[ "$WANT_BUILD" = 1 ]    && add "Сборка APK в Termux" task_build_apk
[ "$WANT_OPENCODE" = 1 ] && add "OpenCode (ИИ-ассистент)" task_opencode
[ "$WANT_MODELS" = 1 ]   && add "Бесплатные модели: конфиг OpenCode" task_opencode_config
[ "$WANT_MODELS" = 1 ]   && add "Бесплатные модели: Zen-Proxy" task_zenproxy
[ "$WANT_CODESERVER" = 1 ] && add "VS Code в браузере (code-server)" task_codeserver
add "Скрипты запуска + CLI codemobile" task_shortcuts
[ "$WANT_DESIGN" = 1 ]   && add "Оформление и дизайн" task_design
[ "$WANT_APK" = 1 ]      && add "APK-лаунчер" task_apk
add "Проверка ошибок и подсказки" task_doctor

BAR_TOTAL=$(( ${#STEPS[@]} + 1 ))
draw_bar 1 "$BAR_TOTAL" "Доступ к памяти"
if [ "$DRY" = 1 ]; then
    printf '\r  %s[dry]%s Доступ к памяти\033[K\n' "$Y" "$N"
else
    task_storage >>"$LOGF" 2>&1
    printf '\r  %s[ok]%s Доступ к памяти\033[K\n' "$G" "$N"
fi

say "${B}Установка:${N} ${#STEPS[@]} шагов"
say ""
for i in "${!STEPS[@]}"; do
    run_step "$((i + 2))" "${LABELS[$i]}" "${STEPS[$i]}"
done
say ""

# ── Итог ─────────────────────────────────────────────────────────────────────
hr
if [ "$DRY" = 1 ]; then
    say "${B}ИТОГ (--dry):${N} шагов распланировано: ${#STEPS[@]}, ошибок: $FAILS"
else
    say "${B}ИТОГ:${N} шагов: ${#STEPS[@]}, ошибок: $FAILS"
fi
if [ "$FAILS" -eq 0 ]; then
    ok "Готово! ${B}http://127.0.0.1:8080${N} — VS Code; иконка «VS Code» — быстрый запуск."
else
    warn "Были ошибки — см. [XX] выше. Часто помогает: bash install.sh --yes"
fi
say ""
say "${B}Как пользоваться:${N}"
say "  • Телефон: открой иконку «VS Code» — сервисы поднимутся сами, откроется редактор."
say "  • Терминал: ${C}codemobile start|stop|status|doctor|models|logs${N}"
say "  • ИИ-ассистент: в VS Code встроенный терминал → ${C}opencode${N} (Tab — переключение агента)."
[ "$WANT_MODELS" = 1 ] && say "  • Бесплатные модели: ${C}mem helper${N} — вставит Zen-ключ по шагам (если его ещё нет)."
say "  • Пароль code-server: ${C}cat ~/.config/code-server/config.yaml${N}"
[ "$WANT_BUILD" = 1 ] && say "  • Сборка из исходников: ${C}codemobile build${N} → APK в Download/CodeMobile-<версия>.apk"
say ""
say "${DIM}После установки выполни: termux-reload-settings${N}"
say ""
phantom_check | sed 's/^/  /'

# ── Подсказки Xiaomi / Shizuku ───────────────────────────────────────────────
if [ "$WANT_HINTS" = 1 ]; then
    cat <<'HINTS'

== Xiaomi / HyperOS: чтобы фон не убивался (один раз, руками) ==
  1) Termux (и Termux:Boot, Shizuku) ставить с F-Droid, не из Play.
  2) Настройки → Приложения → Termux → Автозапуск — ВКЛ.
  3) Там же → Батарея → «Без ограничений».
  4) В списке недавних: потянуть карточку Termux вниз → замок.
  5) termux-reload-settings (или перезапустить Termux).

== Shizuku: выключить phantom process killer (Android 12+, без ПК) ==
  1) Установить Shizuku (F-Droid/GitHub), запустить через отладку по Wi-Fi.
  2) Shizuku → Меню → rish → положить файл rish в ~ и chmod +x rish.
  3) cd ~ && ./rish settings put global settings_enable_monitor_phantom_procs false
     Проверка: codemobile doctor
HINTS
fi
say ""
[ "$FAILS" -eq 0 ] || exit 1
exit 0
