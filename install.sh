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
#    6.  VS Code в браузере (code-server)
#    7.  Скрипты запуска + CLI `codemobile` + termux.properties
#    8.  Дизайн/оформление (тема-стиль Apple, шрифты, курсор)
#    9.  APK-лаунчер (скачать из релиза GitHub и установить)
#   10.  Проверка ошибок и подсказки по решению
#
#  Запуск (любой из вариантов):
#    pkg install curl
#    bash <(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/install.sh)
#
#    # или из клонированного репозитория:
#    git clone https://github.com/Forlein/codemobile && cd codemobile && bash install.sh
#
#  Флаги:
#    -y, --yes        не задавать вопросов, ставить всё по умолчанию
#    --dry            только показать, что будет сделано
#    --no-opencode    не ставить OpenCode
#    --no-models      без бесплатных моделей (OpenKilo/Zen-Proxy)
#    --no-code-server без code-server
#    --no-design      без оформления/дизайна
#    --no-apk         не качать/не ставить APK
#    --no-hints       без инструкций Xiaomi/Shizuku
#    -h, --help
#
#  Коды выхода: 0 — ок, 1 — были ошибки.
# =============================================================================
set -uo pipefail

# ── Репозиторий (для bootstrap и скачивания APK) ─────────────────────────────
GITHUB_REPO="${CODEMOBILE_REPO:-Forlein/codemobile}"
GITHUB_BRANCH="${CODEMOBILE_BRANCH:-main}"

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
export PATH="$PREFIX/bin:$PREFIX/opt/nodejs-24/bin:$PATH"
SRC_DIR="$HOME_DIR/.local/share/codemobile/src"
PKG_APP="com.example.opencodelauncher"

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
  bash install.sh --no-code-server   без code-server
  bash install.sh --no-design        без оформления
  bash install.sh --no-apk           без APK-лаунчера
  bash install.sh --no-hints         без инструкций Xiaomi/Shizuku
  bash install.sh --help

Репозиторий можно переопределить: CODEMOBILE_REPO=owner/repo bash install.sh
USAGE
}

# ── Разбор флагов ────────────────────────────────────────────────────────────
ASSUME_YES=0; DRY=0
WANT_OPENCODE=2; WANT_MODELS=2; WANT_CODESERVER=2; WANT_DESIGN=2; WANT_APK=2; WANT_HINTS=1
for a in "$@"; do
    case "$a" in
        -y|--yes)        ASSUME_YES=1 ;;
        --dry)           DRY=1 ;;
        --no-opencode)   WANT_OPENCODE=0 ;;
        --no-models)     WANT_MODELS=0 ;;
        --no-code-server) WANT_CODESERVER=0 ;;
        --no-design)     WANT_DESIGN=0 ;;
        --no-apk)        WANT_APK=0 ;;
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

# ── Bootstrap: если запущено из pipe (`curl | bash`), tools/ рядом нет ───────
bootstrap_self() {
    if [ -n "$ROOT" ] && [ -f "$ROOT/tools/settings.seed.json" ]; then
        cd "$ROOT" || return 1
        return 0
    fi
    if [ -n "${CODEMOBILE_SRC:-}" ] && [ -f "$CODEMOBILE_SRC/tools/settings.seed.json" ]; then
        cd "$CODEMOBILE_SRC" || return 1
        return 0
    fi
    say "${DIM}Bootstrap: исходников рядом нет — скачиваю репозиторий…${N}"
    command -v curl >/dev/null 2>&1 || { pkg install -y curl >/dev/null 2>&1; }
    command -v curl >/dev/null 2>&1 || { err "нет curl — установи: pkg install curl"; return 1; }
    local tmp; tmp="$(mktemp -d)"
    if ! curl -fsSL "https://codeload.github.com/$GITHUB_REPO/tar.gz/refs/heads/$GITHUB_BRANCH" \
            -o "$tmp/repo.tar.gz"; then
        err "не удалось скачать $GITHUB_REPO ($GITHUB_BRANCH)"
        return 1
    fi
    mkdir -p "$SRC_DIR"
    tar -xzf "$tmp/repo.tar.gz" -C "$tmp"
    rm -rf "${SRC_DIR:?}"/*
    cp -r "$tmp"/*/. "$SRC_DIR/"
    rm -rf "$tmp"
    say "${G}✓${N} исходники в $SRC_DIR"
    CODEMOBILE_SRC="$SRC_DIR" exec bash "$SRC_DIR/install.sh" "$@"
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
    echo "Termux OK: $PREFIX"
}

task_storage() {
    if [ -d "$HOME_DIR/storage" ]; then echo "доступ к памяти уже есть"; return 0; fi
    command -v termux-setup-storage >/dev/null 2>&1 || { echo "нет termux-setup-storage"; return 0; }
    echo "запрашиваю доступ к памяти устройства…"
    termux-setup-storage >/dev/null 2>&1 || true
    for _ in $(seq 1 8); do [ -d "$HOME_DIR/storage" ] && break; sleep 1; done
    [ -d "$HOME_DIR/storage" ] && echo "доступ есть" || echo "доступ не получен (можно позже: termux-setup-storage)"
    return 0
}

task_pkg() {
    pkg install -y tur-repo || return 1
}

task_deps() {
    pkg install -y python nodejs-lts curl git unzip || return 1
    node -v; python3 --version
}

task_opencode() {
    pkg install -y opencode2 || return 1
    install_file tools/opencode2 "$PREFIX/bin/opencode2" 755
    [ -x "$PREFIX/libexec/opencode2/opencode2.bin" ] \
        && echo "бинарь на месте: $PREFIX/libexec/opencode2/opencode2.bin" \
        || echo "нет opencode2.bin (установится пакетом; враппер подождёт)"
}

task_opencode_config() {
    local dir="$HOME_DIR/.config/opencode" cfg="$HOME_DIR/.config/opencode/opencode.json"
    mkdir -p "$dir"
    if [ ! -f "$cfg" ]; then
        install_file tools/opencode.json.seed "$cfg" 644 || return 1
    else
        # неразрушающий мерж: добавляем плагин и провайдера, остальное сохраняем
        cp -p "$cfg" "$cfg.bak-install" 2>/dev/null
        node - "$cfg" <<'NODE' || return 1
const fs = require("fs");
const p = process.argv[2];
let t = fs.readFileSync(p, "utf8")
  .replace(/\/\/[^\n]*/g, "").replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/,(\s*[}\]])/g, "$1");
let cfg = {};
try { cfg = JSON.parse(t); } catch { cfg = {}; }
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
console.log("opencode.json объединён (неразрушающе)");
NODE
    fi
    # плагин openkilo (бесплатные модели Kilo Gateway)
    npm install --prefix "$dir" openkilo >/dev/null 2>&1 \
        && echo "openkilo установлен" || echo "openkilo: npm пропущен (opencode подтянет сам)"
    echo "провайдеры: openkilo (Kilo free) + zen-proxy (:8787)"
}

task_zenproxy() {
    local zen="$HOME_DIR/.zen-proxy"
    curl -fsSL "https://raw.githubusercontent.com/12errh/zen-proxy/main/install.sh" \
        | ZEN_PROXY_DIR="$zen" bash || return 1
    [ -f "$zen/zen-proxy.json" ] || install_file tools/zen-proxy.json.seed "$zen/zen-proxy.json" 644
    echo "zen-proxy: $zen"
}

task_codeserver() {
    pkg install -y code-server || return 1
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
    if [ -f "$ms" ]; then
        python3 "$ms" tools/settings.seed.json "$settings"
    else
        python3 tools/merge_settings.py tools/settings.seed.json "$settings"
    fi
    echo "оформление: матовая прозрачность + курсор #FF2D55 + шрифты"
}

task_apk() {
    local dl="$HOME_DIR/storage/shared/Download"; [ -d "$dl" ] || dl="$(mktemp -d)"
    local apk=""
    # 1) локальная сборка (режим разработчика)
    for cand in "${CODEMOBILE_APK:-}" app/build/outputs/apk/release/app-release.apk \
                app/build/outputs/apk/debug/app-debug.apk; do
        [ -n "$cand" ] && [ -f "$cand" ] && { apk="$cand"; break; }
    done
    # 2) последний релиз на GitHub
    if [ -z "$apk" ]; then
        local url
        url="$(curl -fsSL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" 2>/dev/null \
               | grep -oE '"browser_download_url":[[:space:]]*"[^"]+\.apk"' \
               | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
        if [ -n "$url" ]; then
            apk="$dl/CodeMobile-latest.apk"
            echo "качаю APK: $url"
            curl -fsSL "$url" -o "$apk" || apk=""
        fi
    fi
    if [ -z "$apk" ] || [ ! -f "$apk" ]; then
        echo "APK не найден (нет релиза с .apk). Собери сам: bash tools/build_apk.sh --release"
        echo "или скачай вручную со страницы Releases репозитория $GITHUB_REPO"
        return 0
    fi
    ok "APK: $apk ($(du -h "$apk" | cut -f1))"
    if [ "$DRY" = 1 ]; then info "[dry] запустил бы установку $apk"; return 0; fi
    if command -v pm >/dev/null 2>&1 \
        && pm list packages 2>/dev/null | grep -q "^package:$PKG_APP\$"; then
        : # уже установлен — обновим ниже при наличии pm
    fi
    if command -v pm >/dev/null 2>&1 && pm install -r "$apk" >/dev/null 2>&1; then
        ok "APK установлен через pm (приложение появится в меню)"
    else
        echo "открываю установщик Android — подтверди установку (нужен доступ Termux на установку приложений)"
        command -v termux-open >/dev/null 2>&1 && termux-open "$apk" || echo "поставь вручную: $apk"
    fi
}

task_doctor() {
    command -v codemobile >/dev/null 2>&1 && codemobile doctor || echo "codemobile не установлен"
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
[ "$WANT_HINTS" = 1 ] && { ask_yn "Показать подсказки Xiaomi/Shizuku (чтобы фон не убивался)?" Y || WANT_HINTS=0; }
say ""

# ── Собираю план ─────────────────────────────────────────────────────────────
STEPS=(); LABELS=()
add() { STEPS+=("$2"); LABELS+=("$1"); }

add "Базовые пакеты (tur-repo, python, node, git…)" task_pkg
add "Зависимости" task_deps
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
say "  • Пароль code-server: ${C}cat ~/.config/code-server/config.yaml${N}"
say ""
say "${DIM}После установки выполни: termux-reload-settings${N}"

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
