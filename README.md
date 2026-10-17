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
| **Бесплатные модели** | Плагин `openkilo` (Kilo Gateway) + локальный прокси `zen-proxy` (opencode Zen free) |
| **Оформление** | Светлая «Apple»-оболочка, матовая прозрачность диалогов, тёмная рабочая зона, курсор `#FF2D55` |
| **Энергосбережение** | Сторож сам гасит сервисы по простою и при закрытии приложения |

---

## Требования

* Android 7.0+ (minSdk 24), желательно ARM64, 4 ГБ RAM+
* **Termux** — ставить с **F‑Droid** (версия из Play Store устарела и не подходит)
* ~1.5 ГБ свободного места (code-server ~500 МБ + OpenCode ~200 МБ + Node)
* Интернет при установке (пакеты и, при желании, APK из релиза)

---

## Установка — одна команда

Открой Termux и вставь:

```bash
pkg install -y curl && bash <(curl -fsSL https://raw.githubusercontent.com/Forlein/codemobile/main/install.sh)
```

Появится меню (можно просто жать **Enter**), затем прогресс‑бар:

```
  [████████░░░░░░░░░░░░░░░░░░]  33%  Устанавливаю VS Code в браузере…
```

### Что спросит установщик

| Вопрос | Что делает |
|---|---|
| **ИИ‑ассистент OpenCode?** | ставит `opencode` (если уже есть — обновит, ничего не удаляя) |
| ↳ **бесплатные модели?** | `openkilo` + `zen-proxy` с автонастройкой конфига |
| **VS Code в браузере (code-server)?** | ставит сервер VS Code |
| ↳ **оформление?** | применяет дизайн (матовая прозрачность, шрифты, курсор) |
| **APK‑лаунчер?** | скачивает APK из **Releases** и ставит иконку на телефон |
| **подсказки Xiaomi/Shizuku?** | инструкции, чтобы Android не убивал фон |

Флаги для «неинтерактивно»: `--yes`, `--no-apk`, `--no-opencode`,
`--no-models`, `--no-code-server`, `--no-design`, `--dry` (см. `--help`).

### Что именно скачивается и ставится

1. **tur-repo** — репозиторий Termux с нужными пакетами;
2. **python, nodejs, curl, git, unzip** — зависимости;
3. **opencode2** — ИИ‑ассистент (пакет Termux); плюс наш враппер (`--standalone`);
4. **openkilo** (плагин OpenCode) — 40+ бесплатных моделей Kilo Gateway, без ключа;
5. **zen-proxy** (`github.com/12errh/zen-proxy`) — локальный прокси `:8787` к
   бесплатным моделям OpenCode Zen;
6. **code-server** — сам VS Code в браузере (пакет Termux);
7. **APK‑лаунчер** — из раздела **Releases** этого репозитория (файл `CodeMobile-*.apk`).

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
  ```

* **ИИ‑ассистент:** в VS Code открой терминал и запусти `opencode`.
  Выбор модели — внутри. Бесплатные: провайдеры **OpenKilo** и **Zen Proxy**.

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
| Не устанавливается APK | нет разрешения | разреши Termux «установку приложений», либо открой файл из `Download/` |

---

## Сборка APK из исходников

Нужны Android SDK (compileSdk 34, build‑tools 34.0.0), JDK 17+ и `pkg install aapt2`.

```bash
git clone https://github.com/Forlein/codemobile && cd codemobile
bash tools/build_apk.sh --release     # соберёт и положит CodeMobile-<версия>.apk в Download
```

> В `gradle.properties` задан `android.aapt2FromMavenOverride` для Termux.
> При сборке на ПК/CI его нужно очистить (`-Pandroid.aapt2FromMavenOverride=`).

---

## Структура

```
codemobile/
├── install.sh              # загрузчик «одной командой» (меню + прогресс + doctor)
├── app/                    # Android-приложение (WebView-обёртка)
├── tools/
│   ├── shortcuts/          # OpenCode-All / Stop / Watch (поднимают и стерегут сервисы)
│   ├── opencode2           # враппер OpenCode
│   ├── codemobile          # CLI (start/stop/status/doctor/models/logs)
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
[OpenCode](https://opencode.ai), [zen-proxy](https://github.com/12errh/zen-proxy) (MIT),
[openkilo](https://github.com/AnganSamadder/openkilo) (MIT).
Бесплатные модели предоставляются сторонними сервисами (Kilo Gateway, OpenCode Zen)
и могут меняться/ограничиваться на их стороне.
