# CodeMobile

**VS Code + ИИ‑ассистент OpenCode в кармане — Android, без ПК.**
Бренд-строка: *VS Code · Mobile · by 4rlein*

Тонкий Android‑лаунчер (одна иконка) превращает телефон или планшет в
полноценный редактор **VS Code** (через [code-server](https://github.com/coder/code-server)
в Termux, открывается в собственном WebView) вместе с терминалом и
ИИ‑ассистентом **[OpenCode](https://opencode.ai)** на **бесплатных моделях**.

> Всё ставится **одной командой** с интерактивным меню и прогресс‑баром.
> Без root, без ПК.

---

## Что это даёт

| Возможность | Как реализовано |
|---|---|
| **VS Code** на телефоне | `code-server` в Termux, показ в приложении (`127.0.0.1:8080`) |
| **Быстрый запуск** | Иконка «VS Code» → приложение само поднимает сервисы и открывает редактор |
| **Терминал** | Встроенный терминал VS Code (xterm) + буфер обмена Android |
| **ИИ‑ассистент** | `opencode` — запускается из терминала VS Code |
| **Бесплатные модели** | Локальный прокси `zen-proxy` (бесплатные модели OpenCode Zen, `127.0.0.1:8787`) |
| **Оформление** | Светлая «Apple»-оболочка, матовая прозрачность диалогов, тёмная рабочая зона, курсор `#FF2D55` |
| **Энергосбережение** | Сторож сам гасит сервисы по простою и при закрытии приложения |

---

## Требования

* Android 7.0+ (minSdk 24), желательно ARM64, 4 ГБ RAM+
* **Termux** — ставить с **F‑Droid** (версия из Play Store устарела и не подходит)
* ~1.5 ГБ свободного места (code-server ~500 МБ + OpenCode ~200 МБ + Node)
* Интернет при установке (пакеты и, при желании, APK из релиза)
* **Git не нужен.** Для установки достаточно одного из: `curl`, `wget`, `python3`
  или `node` — установщик сам подберёт, чем качать, и сам доставит остальное.

---

## Установка — одна команда

**Шаг 1.** Поставь **Termux** — только с **F‑Droid** или
[GitHub](https://github.com/termux/termux-app/releases) (версия из Play Маркета
устарела и не подходит).

**Шаг 2.** Открой Termux и вставь **одну строку** — дальше всё делает установщик:

```bash
pkg install -y curl && sh -c "$(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/get.sh)"
```

`get.sh` — это установщик‑загрузчик: он **не требует git и python**. Он сам
выясняет, чем качать (`curl` → `wget` → `python3` → `node`), при необходимости
сам доставляет `python`, `node`, `unzip` (сообщив, что и зачем), скачивает
проект (GitHub, а при сбое — зеркала), распаковывает в
`~/.local/share/codemobile/src` и только потом запускает `install.sh`.

### Что установщик делает сам (адаптация под устройство)

От пользователя нужно только поставить Termux и один раз подтвердить доступ к
файлам — всё остальное установщик выясняет про систему и обходит сам:

* **Репозитории Termux.** Обновляет списки пакетов, добивает прерванные
  установки (`dpkg --configure -a`). Если основной репозиторий не отвечает —
  сам переключается на рабочее зеркало (официальный бэкап остаётся рядом:
  `sources.list.codemobile.bak`).
* **Имена пакетов.** Перебирает варианты (`nodejs-lts` ↔ `nodejs`,
  `python` ↔ `python3`, `openjdk-21` ↔ `openjdk-17`), поэтому различия сборок
  Termux не важны.
* **Сеть.** Замеряет зеркала и берёт самое быстрое, обрывает «зависшую»
  загрузку и повторяет; битый/обрезанный архив и HTML‑заглушку прокси
  распознаёт и пропускает; если `tar.gz` так и не скачался — автоматически
  берёт `zip`.
* **Доступ к памяти.** Сам запрашивает доступ и ждёт подтверждения; проверяет
  запись в `Download`; подсказки учитывают версию Android.
* **Android 12+.** Определяет «убийцу фантомных процессов» и, если есть
  Shizuku (`rish`), выключает его, иначе даёт точную команду.

**Шаг 3.** Ответь на пару вопросов (можно просто жать **Enter**) — появится меню,
затем прогресс‑бар:

```
  [████████░░░░░░░░░░░░░░░░░░]  33%  Устанавливаю VS Code в браузере…
```

В самом конце поставь APK‑лаунчер (иконка **«VS Code»**) — или скачай
`CodeMobile-*.apk` из [Releases](https://github.com/Forlein/codemobile/releases) и открой его.

### Если GitHub недоступен или тормозит

Установщик сам перебирает зеркала (`ghproxy.net`, `gh-proxy.com`, `ghfast.top`)
и, когда `raw.githubusercontent.com` не отвечает, качает исходники через них.
Своё зеркало можно задать руками:

```bash
# скачать сам установщик через зеркало
sh -c "$(curl -fsSL https://cdn.jsdelivr.net/gh/Forlein/codemobile@main/get.sh)"

# или явно указать зеркало для исходников
sh get.sh --mirror https://ghproxy.net/ -y

# вообще без зеркал (только GitHub)
sh get.sh --direct
```

Переменная `CODEMOBILE_MIRRORS` задаёт список зеркал через запятую, а
`CODEMOBILE_REPO`/`CODEMOBILE_BRANCH` — свой форк и ветку.

### Что спросит установщик

| Вопрос | Что делает |
|---|---|
| **ИИ‑ассистент OpenCode?** | ставит `opencode` (если уже есть — обновит, ничего не удаляя) |
| ↳ **бесплатные модели?** | `zen-proxy` с автонастройкой конфига |
| ↳ **Zen‑ключ?** | можно вставить сразу, а можно **пропустить** (Enter) — впишешь позже: `mem helper` |
| **VS Code в браузере (code-server)?** | ставит сервер VS Code |
| ↳ **оформление?** | применяет дизайн (матовая прозрачность, шрифты, курсор) |
| **APK‑лаунчер?** | скачивает APK из **Releases** и ставит иконку на телефон |
| **собрать APK в Termux?** | режим разработчика: JDK + Android SDK и сборка APK на телефоне |
| **подсказки Xiaomi/Shizuku?** | инструкции, чтобы Android не убивал фон |

Флаги: `--yes`, `--no-apk`, `--no-opencode`, `--no-models`,
`--no-code-server`, `--no-design`, `--build`, `--dry`, `--mirror URL`,
`--zen-key KEY`, `--no-zen-key`, `--refresh` (см. `--help`).

> **Нет аккаунта opencode.ai?** Это не помешает установке. Ключ можно ввести
> при установке — или пропустить (просто Enter) и вписать позже командой
> **`mem helper`**, которая по шагам подскажет, где взять ключ, и сама
> пропишет его в конфиг `zen-proxy`.

Всё сразу, без вопросов:

```bash
sh -c "$(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/get.sh)" -y
```

Флаги `get.sh`: `--no-run` (только скачать исходники), `--update` (перекачать),
`--src DIR`, `--zip`, `--no-deps`, `--print-url` — подробнее в `sh get.sh --help`
и в [INSTALL.md](INSTALL.md).

### Установка без сети‑затей: из архива вручную

Скачай zip/tar.gz со страницы репозитория (Code → **Download ZIP**), распакуй в
Termux и запусти:

```bash
cd ~/codemobile-main && bash install.sh
```

### Обновление проекта

```bash
codemobile upgrade          # скачает свежие исходники и повторит установку
codemobile upgrade -y       # без вопросов
```

Данные приложения, пароль code-server и настройки не теряются.

### Доступ к файлам Termux (чтобы не ругался)

```bash
termux-setup-storage          # один раз → системный диалог → «Разрешить»
ls ~/storage/shared/Download  # показал список — доступ есть
```

Если всё равно `Permission denied` на `/storage/emulated/0/…`, разрешение
закрыто на уровне Android:

* **Настройки → Приложения → Termux → Разрешения → Файлы и медиа →
  «Управление всеми файлами»**;
* **HyperOS/MIUI:** Приложения → Управление приложениями → Termux →
  Разрешения → Хранилище → **«Всегда разрешать»**;
* затем повторить `termux-setup-storage`.

Для установки APK вне «магазинов» Termux ещё нужно разрешить:
**Настройки → Специальные возможности → Установка приложений →
Termux → Разрешить**.

### Установка APK вручную (командами Termux)

```bash
termux-setup-storage

# 1) скачать APK из релиза
curl -fL -o "$HOME/storage/shared/Download/CodeMobile.apk" \
  https://github.com/Forlein/codemobile/releases/download/v1.0.0/CodeMobile-v1.0.0.apk

# 2) установить (обновление сохраняет данные приложения)
pm install -r "$HOME/storage/shared/Download/CodeMobile.apk"

# 3) запасной путь, если pm отказал — системный установщик Android
termux-open "$HOME/storage/shared/Download/CodeMobile.apk"
```

Если `pm` отвечает `Failure [INSTALL_*]` — у шелла нет прав: используйте
`adb install -r <apk>` с ПК либо `rish pm install -r <apk>` через Shizuku.

### Что именно скачивается и ставится

1. **tur-repo** — репозиторий Termux с нужными пакетами;
2. **python, nodejs, curl, unzip** — зависимости (git — по желанию, для разработки);
   их можно не ставить: сам проект уже скачан установщиком `get.sh`;
3. **opencode2** — ИИ‑ассистент: сборка OpenCode 2 под Android из стороннего
   apt‑репозитория `opencode-android-apt` (в репозиториях Termux пакета нет);
   плюс наш враппер (`--standalone`);
4. **zen-proxy** (`github.com/12errh/zen-proxy`) — локальный прокси `:8787` к
   бесплатным моделям OpenCode Zen (ключ можно ввести при установке или позже
   через `mem helper`);
5. **code-server** — сам VS Code в браузере (пакет Termux);
6. **APK‑лаунчер** — из раздела **Releases** этого репозитория (файл `CodeMobile-*.apk`).

Пароль к code-server генерируется случайно, лежит в
`~/.config/code-server/config.yaml`.

---

## Как пользоваться

* **Телефон:** нажми иконку **«VS Code»**. Приложение поднимет сервисы и
  откроет редактор. Двухпальцевый свайп вверх — шторка выхода/перезапуска.
* **Терминал:** команда `codemobile`:

  ```bash
  codemobile start      # поднять всё
  codemobile stop       # погасить сервисы
  codemobile restart    # перезапустить
  codemobile status     # что запущено
  codemobile doctor      # поиск ошибок и подсказки по решению
  codemobile models     # обновить список бесплатных моделей
  codemobile logs       # хвост логов
  codemobile upgrade    # обновить сам проект (исходники + скрипты)
  codemobile build      # собрать APK на телефоне (после install.sh --build)
  mem helper            # вписать Zen-ключ для бесплатных моделей (по шагам)
  ```

* **ИИ‑ассистент:** в VS Code открой терминал и запусти `opencode`.
  Выбор модели — внутри. Бесплатные модели — провайдер **Zen Proxy**.

* **Нет ключа opencode.ai?** Пропусти его при установке и позже выполни
  `mem helper` — помощник объяснит, где взять ключ, и сам пропишет его:

  ```bash
  mem helper            # меню-помощник (ввод ключа, проверка моделей)
  mem key <КЛЮЧ>        # вписать ключ одной строкой
  mem status            # ключ, прокси, список моделей
  mem test              # послать тестовый запрос к бесплатной модели
  ```

---

## Диагностика

```bash
codemobile doctor            # окружение: allow-external-apps, node, storage, порты
codemobile status            # живы ли code-server и zen-proxy
tail -f ~/.cache/opencodelauncher/watch.log
```

Типовые проблемы и решения:

| Симптом | Причина | Решение |
|---|---|---|
| Бесконечное «подключение» | не выставлен `allow-external-apps` | `bash install.sh` + `termux-reload-settings` |
| `code-server` не отвечает | не поднят / упал | `codemobile start`, смотри `~/.cache/opencodelauncher/code-server.log` |
| Всё «пропадает» в фоне | phantom process killer (Android 12+) | выключить через Shizuku (`codemobile doctor`) |
| Нет бесплатных моделей | zen-proxy не запущен | `codemobile start`, затем `codemobile models` |
| Бесплатные модели не отвечают («deprecated») | нет Zen‑ключа или free‑доступ изменился | `mem helper` (вставить ключ) или `mem status` |
| Не устанавливается APK | нет разрешения | разреши Termux «установку приложений», либо открой файл из `Download/` |

---

## Сборка APK из исходников

### На телефоне (без ПК)

```bash
sh get.sh --no-run          # только скачать исходники (git не нужен)
cd ~/.local/share/codemobile/src
bash install.sh --build     # JDK 21 + aapt2 + Android SDK и сборка APK
```

Дальше пересобирать можно одной командой:

```bash
codemobile build            # → Download/CodeMobile-<версия>.apk
```

Скрипт сам доставит Android SDK (платформа 34, build‑tools 36.0.0), подставит
нативный `aapt2` из Termux (сборка из Maven собрана под x86_64 и на телефоне не
запускается) и запишет `sdk.dir` в `local.properties`.

> `aapt2` из Termux линкуется с `libprotobuf`. Если версии разъехались
> (например, `aapt2 16.0.0.4-3` требует `libprotobuf` 36.x, а стоит 35.x),
> `aapt2` падает с `cannot locate symbol … protobuf …`. Установщик проверяет
> `aapt2` запуском и сам выравнивает версии `aapt2` + `libprotobuf`.

### На ПК / в CI

Нужны Android SDK (compileSdk 34, build‑tools 36.0.0) и JDK 17+.

```bash
git clone https://github.com/Forlein/codemobile && cd codemobile
bash tools/build_apk.sh --release     # соберёт и положит CodeMobile-<версия>.apk в Download
```

> В `gradle.properties` задан `android.aapt2FromMavenOverride` для Termux.
> На ПК/CI его нужно очистить (`-Pandroid.aapt2FromMavenOverride=`), иначе Gradle
> возьмёт чужой `aapt2`.

Полезные флаги `tools/build_apk.sh`: `--release`, `--debug`, `--offline`
(только из кэша Gradle), `--no-copy` (не копировать в Download),
`--allow-existing` (не ругаться, если такая версия уже лежит), `--keep=N`
(чистить Download, оставив N последних). Первая сборка требует сети: Gradle
скачивает плагин Android и зависимости.

---

## Структура

```
codemobile/
├── get.sh                  # установщик-загрузчик: скачает проект и запустит install.sh
├── install.sh              # установщик «одной командой» (меню + прогресс + doctor)
├── INSTALL.md              # подробная инструкция по установке и решению проблем
├── app/                    # Android-приложение (WebView-обёртка)
├── tools/
│   ├── shortcuts/          # OpenCode-All / Stop / Watch (поднимают и стерегут сервисы)
│   ├── opencode2           # враппер OpenCode
│   ├── codemobile          # CLI (start/stop/status/doctor/models/logs/upgrade/build)
│   ├── mem                 # помощник: Zen-ключ для бесплатных моделей (mem helper)
│   ├── settings.seed.json  # оформление для settings.json code-server
│   ├── opencode.json.seed  # конфиг OpenCode с бесплатными моделями
│   ├── zen-proxy.json.seed # конфиг zen-proxy
│   ├── merge_settings.py   # неразрушающий мерж настроек
│   └── build_apk.sh        # сборка APK
├── gradle/ · gradlew · *.gradle
└── .github/workflows/      # автосборка релизов
```

---

## Лицензия и авторы

MIT — см. [LICENSE](LICENSE). Оригинальный автор: **@4rlein**.

Сторонние компоненты: [code-server](https://github.com/coder/code-server) (MIT),
[OpenCode](https://opencode.ai), [zen-proxy](https://github.com/12errh/zen-proxy) (MIT).
Пакет `opencode2` — сторонняя сборка OpenCode 2 под Android
(`guysoft/opencode-termux`); бесплатные модели предоставляются OpenCode Zen
и могут меняться/ограничиваться на стороне сервиса.
