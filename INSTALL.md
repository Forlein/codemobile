# Установка CodeMobile — подробная инструкция

VS Code + ИИ‑ассистент OpenCode в кармане, **без ПК и без root**.
Короткая версия — в [README](README.md); здесь — все варианты, флаги и лечение
ошибок.

---

## 0. Что понадобится

| Что | Зачем | Где брать |
|---|---|---|
| **Termux** | среда, в которой живут VS Code и OpenCode | **F‑Droid** или [GitHub Releases](https://github.com/termux/termux-app/releases) — **не Play Маркет** |
| Интернет | скачать пакеты и проект | — |
| ~1.5 ГБ места | code-server (~500 МБ) + OpenCode (~200 МБ) + Node | — |
| Android 7.0+ | minSdk приложения | — |

**Git и Python заранее не нужны.** Установщик сам определит, чем качать
(`curl` → `wget` → `python3` → `node`), сам доставит недостающее
(`python`, `nodejs`, `unzip`) через `pkg` и сам скачает проект.

---

## 1. Быстрая установка (рекомендуется)

**Шаг 1.** Открой Termux.

**Шаг 2.** Вставь одну строку и нажми Enter:

```bash
pkg install -y curl && sh -c "$(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/get.sh)"
```

**Шаг 3.** Ответь на вопросы (Enter = «да») и дождись прогресс‑бара:

```
  [████████████░░░░░░░░░░░░░░░]  47%  VS Code в браузере (code-server)
```

**Шаг 4.** В конце поставь APK‑лаунчер (иконка **VS Code**) — установщик скачает
его из Releases и откроет системный установщик Android.

**Шаг 5.** В Termux выполни один раз:

```bash
termux-reload-settings
```

Готово: иконка **VS Code** на телефоне, редактор на `http://127.0.0.1:8080`.

Без вопросов, всё по умолчанию:

```bash
sh -c "$(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/get.sh)" -y
```

---

## 2. Что именно делает `get.sh`

| Этап | Действие | Можно отключить |
|---|---|---|
| 1 | проверяет, что это Termux; иначе показывает, что скачать | — |
| 2 | выбирает загрузчик: `curl` → `wget` → `python3` → `node` | — |
| 3 | чинит репозитории Termux (`pkg update`, `dpkg --configure -a`, авто-смена зеркала) | — |
| 4 | доставляет `curl`, `python`, `nodejs-lts`, `unzip` (перебирая имена пакетов) | `--no-deps` |
| 5 | скачивает архив: замер зеркал, `tar.gz`, при неудаче — авто-`zip` | `--direct` |
| 6 | распаковывает в `~/.local/share/codemobile/src` | `--src DIR` |
| 7 | запускает `install.sh` (меню + прогресс) | `--no-run` |

Если исходники уже скачаны, шаг 5 пропускается (мгновенный повторный запуск);
перекачать заново — `--update`.

### Адаптация под систему

Пользователю достаточно поставить Termux и один раз подтвердить доступ к файлам.
Всё остальное скрипт выясняет сам:

* **Репозитории.** Если `pkg update` не проходит (битый/устаревший mirror),
  установщик тестирует официальные зеркала и переключается на рабочее,
  сохранив бэкап: `sources.list.codemobile.bak`.
* **Имена пакетов.** Перебор вариантов (`nodejs-lts`↔`nodejs`,
  `python`↔`python3`, `openjdk-21`↔`openjdk-17`) — сборка Termux не важна.
* **Сеть.** Замер отклика зеркал (быстрейшее первым), обрыв «зависшей» загрузки
  и повтор, распознавание HTML-заглушек и обрезанных архивов, авто-фолбэк
  `tar.gz` → `zip`. Докачка (`-C -`) применяется там, где сервер умеет Range
  (релизный APK); архивы исходников GitHub перекачиваются заново.
* **Устройство.** Определяет архитектуру, версию Android (API) и Termux;
  на Android 12+ обрабатывает «phantom process killer» (через `rish`/Shizuku).

### Флаги `get.sh`

```text
-y, --yes          передать install.sh: без вопросов
--repo OWNER/REPO  другой репозиторий (форк)
--branch NAME      ветка или тег (по умолчанию main)
--src DIR          куда распаковать исходники
--mirror URL       добавить зеркало (можно несколько раз)
--direct           только GitHub, без зеркал
--zip              качать zip вместо tar.gz
--update           перекачать исходники
--no-deps          не доставлять пакеты
--no-run           только скачать, установщик не запускать
--print-url        показать адреса загрузки и выйти
-h, --help

Любой другой флаг уходит в install.sh:
  --yes --no-apk --no-opencode --no-models --no-code-server --no-design
  --build --dry --refresh --mirror URL --zen-key KEY --no-zen-key
```

### Переменные окружения

```bash
CODEMOBILE_REPO=owner/repo         # свой репозиторий
CODEMOBILE_BRANCH=dev              # своя ветка/тег
CODEMOBILE_SRC=~/cm/src            # каталог исходников
CODEMOBILE_MIRRORS="https://ghproxy.net/,https://gh-proxy.com/"
CODEMOBILE_APK=~/Download/CodeMobile.apk   # свой APK для установки
ZEN_KEY=...                                # Zen-ключ (или OPENCODE_ZEN_KEY)
```

### Ключ к бесплатным моделям — можно пропустить

Бесплатные модели через `zen-proxy` отвечают только с ключом
[opencode.ai](https://opencode.ai). При установке установщик спросит ключ:

* **есть ключ** — вставь его (или задай заранее: `--zen-key KEY`,
  `ZEN_KEY=...`) — он запишется в `zen-proxy.json`;
* **нет ключа / нет аккаунта** — просто нажми **Enter** (или запусти с
  `--no-zen-key`). Установка **не прервётся**: поставится всё, включая
  `zen-proxy`, но провайдер «Zen Proxy» в конфиг OpenCode **не добавится**,
  чтобы в списке моделей не было нерабочих. Его подключит `mem helper`
  после ввода ключа.

Заодно без ключа работают встроенные бесплатные модели (`opencode-go`), так
что ассистентом можно пользоваться сразу.

Вписать ключ позже, по шагам, в любой момент:

```bash
mem helper            # меню-помощник: где взять ключ, ввод, проверка моделей
mem key <КЛЮЧ>        # вписать одной командой
mem status            # что настроено (ключ, прокси, модели)
mem test              # проверить, отвечают ли бесплатные модели
mem del-key           # удалить ключ
```

---

## 3. Если GitHub недоступен или очень медленный

`get.sh` и `install.sh` умеют качать через зеркала‑прокси:
`ghproxy.net`, `gh-proxy.com`, `ghfast.top`, а одиночные файлы — ещё и через
быстрый CDN **jsDelivr**. Перебор идёт автоматически: не вышло на GitHub —
пробуется следующее зеркало.

Свой вариант:

```bash
# 1) скачать сам установщик через jsDelivr
sh -c "$(curl -fsSL https://cdn.jsdelivr.net/gh/Forlein/codemobile@main/get.sh)"

# 2) или указать зеркало вручную
sh get.sh --mirror https://ghproxy.net/ -y

# 3) или подставить цепочку зеркал
CODEMOBILE_MIRRORS="https://ghproxy.net/,https://gh-proxy.com/" sh get.sh -y
```

Посмотреть все адреса, по которым установщик будет пробовать качать:

```bash
sh get.sh --print-url
```

### Установка вообще без установщика

Скачай архив на телефоне (браузер → страница репозитория → **Code → Download ZIP**)
или любым менеджером загрузок, распакуй и запусти:

```bash
cd ~/codemobile-main       # каталог из архива
bash install.sh            # или: bash install.sh -y
```

Тот же результат даёт `git`, если он есть:

```bash
pkg install -y git
git clone https://github.com/Forlein/codemobile && cd codemobile && bash install.sh
```

---

## 4. Обновление и повторная установка

```bash
codemobile upgrade         # скачать свежие исходники и повторить установку
codemobile upgrade -y      # без вопросов
codemobile update          # обновить пакеты (code-server, opencode2, node, python)
codemobile build           # пересобрать APK на телефоне (после install.sh --build)
```

Установщик идемпотентный: повторный запуск ничего не ломает, настройки
`settings.json`/`opencode.json` мержатся (создаются `.bak-install` копии),
пароль code-server и данные приложения сохраняются.

Просто переустановить поверх:

```bash
sh get.sh --update -y
```

---

## 5. Сборка APK прямо на телефоне (режим разработчика)

```bash
sh get.sh --no-run                     # только исходники (git не нужен)
cd ~/.local/share/codemobile/src
bash install.sh --build                # JDK 21 + aapt2 + Android SDK + сборка
```

Дальше:

```bash
codemobile build                       # → Download/CodeMobile-<версия>.apk
bash tools/build_apk.sh --release      # то же самое напрямую
```

Флаги `tools/build_apk.sh`: `--debug` (по умолчанию debug), `--release`,
`--offline` (без сети, из кэша Gradle), `--no-copy`, `--allow-existing`,
`--keep=N` (оставить в Download N последних APK).

Требуется ~1 ГБ свободного места: JDK 21, Android SDK (платформа 34,
build‑tools 36.0.0) и Gradle‑кэш. Первая сборка идёт с сетью.

> `aapt2` из Termux линкуется с `libprotobuf`: при рассинхроне версий пакетов он
> не запускается (`cannot locate symbol … protobuf …`). Установщик проверяет
> `aapt2` запуском и сам выравнивает версии `aapt2` + `libprotobuf`.

---

## 6. Если что-то не так

| Симптом | Причина | Решение |
|---|---|---|
| `pkg: command not found` | это не Termux (или Termux из Play Маркета) | поставить Termux с F‑Droid/GitHub и открыть заново |
| `не удалось скачать архив ни с GitHub, ни с зеркал` | нет сети / блокировка | `sh get.sh --mirror https://ghproxy.net/`; проверить сеть; попробовать `--zip` |
| `python3 нет — часть шагов пропустится` | нет python | `pkg install python` и повторить `bash install.sh` |
| Долго `code-server` не отвечает | сервис ещё поднимается / упал | `codemobile start`, затем `codemobile status`; лог `~/.cache/opencodelauncher/code-server.log` |
| Бесконечное «подключение» в приложении | не включён `allow-external-apps` | `bash install.sh` (сам допишет) → `termux-reload-settings` |
| Всё «пропадает» в фоне | phantom process killer (Android 12+) | выключить через Shizuku — см. `codemobile doctor` |
| Нет бесплатных моделей | `zen-proxy` не поднят | `codemobile start`, потом `codemobile models` |
| Бесплатные модели не отвечают («deprecated»/gated) | нет Zen‑ключа | `mem helper` (вставить ключ) или `mem status` |
| Не ставится APK | нет разрешения «Установка приложений» | Настройки → Приложения → Termux → Установка приложений → Разрешить; либо открой файл из `Download/` |
| `Permission denied` на `/storage/emulated/0` | нет доступа к памяти | `termux-setup-storage`; HyperOS: разрешение «Файлы и медиа → Управление всеми файлами» |
| Termux убивается в фоне (Xiaomi) | агрессивная экономия | Автозапуск ВКЛ, «Без ограничений» по батарее, замок на карточке в недавних |
| `cannot locate symbol … protobuf …` при сборке | разъехались версии `aapt2` и `libprotobuf` | `pkg install aapt2 libprotobuf` (установщик делает это сам) |

Диагностика одной командой:

```bash
codemobile doctor          # окружение и настройки
mem status                 # ключ, zen-proxy, список бесплатных моделей
```

---

## 7. Удаление

```bash
codemobile stop                    # погасить сервисы
rm -rf ~/.local/share/codemobile   # исходники и служебные файлы
rm -f  ~/.shortcuts/OpenCode-*     # скрипты запуска
rm -f  ~/.local/bin/codemobile     # CLI (или: rm -f $PREFIX/bin/codemobile)
pkg uninstall code-server opencode2 # сами пакеты (по желанию)
```

APK‑лаунчер удаляется обычным способом: долгий тап по иконке → «Удалить».
