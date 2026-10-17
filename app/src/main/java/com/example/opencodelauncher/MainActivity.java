package com.example.opencodelauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Обёртка-«кнопка» из меню приложений.
 *
 * При запуске:
 *   1) просит Termux выполнить ~/.shortcuts/OpenCode-All (поднимает code-server и zen-proxy);
 *   2) показывает http://127.0.0.1:8080 в собственном WebView;
 *   3) «Назад» ходит по истории браузера, а не закрывает приложение.
 *
 * Пока сервер поднимается — рисуем собственную заглушку с номером попытки,
 * после MAX_ATTEMPTS — экран ошибки с кнопками «Повторить» и «Открыть Termux».
 *
 * Вся работа с системными панелями — в полноэкранном (immersive) режиме.
 */
public class MainActivity extends Activity {

    /** Порт code-server (и источник START_URL). */
    private static final int SERVER_PORT = 8080;
    private static final String START_URL = "http://127.0.0.1:" + SERVER_PORT;
    // Константы Termux/стоп-скрипта package-private: их же использует ExitService,
    // чтобы слать стоп-команду НАПРЯМУЮ из onTaskRemoved (без гонки через Activity).
    static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String HOME_DIR = "/data/data/com.termux/files/home";
    static final String TERMUX_PKG = "com.termux";

    /** Скрипт-стоппер: гасит code-server, zen-proxy и opencode. */
    static final String STOP_SCRIPT = HOME_DIR + "/.shortcuts/OpenCode-Stop";
    /**
     * Тот же стоппер с флагом «--termux»: гасит сервисы И убивает сам Termux.
     * Идёт в кнопку «Exit» и в onTaskRemoved — приложение выключили, значит
     * и Termux больше не нужен (п.4 задач 2.7). Без флага — «мягкая»
     * остановка (нужна кнопке Restart).
     */
    static final String STOP_ALL_ARGS = " --termux";

    /**
     * Порт сторожа {@code ~/.shortcuts/OpenCode-Watch}: лаунчер шлёт туда
     * heartbeat каждые {@link #PING_INTERVAL_MS}, а сторож по отсутствию
     * пингов понимает, что приложение убито, и выключает всё (включая Termux).
     */
    static final int WATCH_PORT = 8799;
    /** Период heartbeat. Должен быть заметно меньше порога сторожа (90 с). */
    private static final int PING_INTERVAL_MS = 15000;
    /** Повторная попытка поднять сервисы — не чаще, чем раз в эту паузу. */
    private static final long SERVICE_KICK_INTERVAL_MS = 60_000L;
    /** Грейс после отправки OpenCode-All: пока сервер поднимается, живость не проверяем. */
    private static final long START_GRACE_MS = 30_000L;
    /** Период фоновой проверки «сервер ещё жив?» (только пока приложение на экране). */
    private static final long HEALTH_CHECK_INTERVAL_MS = 25_000L;
    /** Сколько ждать подъёма сервера при восстановлении (шагов по 1.5 с ≈ 2 мин). */
    private static final int RECOVERY_PROBE_STEPS = 80;

    /**
     * Авто-выключение по простою (шторка, свайп двумя пальцами вверх):
     * ползунок с дискретными ступенями. Раньше сторож гасил всё через 20
     * минут фона — reconnect ронял работу VS Code; теперь по умолчанию 2 ч.
     */
    static final int[] AUTO_OFF_OPTIONS = {15, 30, 60, 120, 240, 360, 720};
    /** Индекс значения по умолчанию в {@link #AUTO_OFF_OPTIONS} (120 мин = 2 ч). */
    static final int AUTO_OFF_DEFAULT_INDEX = 3;
    /** Ключ значения в ocl.prefs (минуты). */
    static final String AUTO_OFF_PREF = "auto_off_minutes";
    /** Повторная пересылка порога сторожу — не чаще, чем раз в эту паузу. */
    private static final long IDLE_PUSH_INTERVAL_MS = 120_000L;

    /**
     * Куда слать RUN_COMMAND.
     *
     * В Termux 0.118+ сервис {@code TermuxService} объявлен exported=false —
     * попытка его запустить даёт
     * «SecurityException: Not allowed to start service … not exported from uid …».
     * Нужен {@code RunCommandService}: exported=true, с intent-filter на
     * {@link #TERMUX_RUN_ACTION} и защитой разрешением {@link #TERMUX_RUN_PERMISSION}.
     */
    static final String TERMUX_RUN_SERVICE = "com.termux.app.RunCommandService";
    static final String TERMUX_RUN_ACTION = "com.termux.RUN_COMMAND";

    /**
     * Разрешение, которым защищён RunCommandService.
     * В установленном Termux оно объявлено с protectionLevel=0x01 (dangerous),
     * поэтому его приходится проверять и, если не выдано, запрашивать у системы.
     */
    static final String TERMUX_RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    /** Код запроса разрешения RUN_COMMAND в onRequestPermissionsResult. */
    private static final int REQ_RUN_PERMISSION = 4101;

    /** Через сколько пробовать загрузить страницу после запуска Termux-команды. */
    // 2.7: было 1500 мс — полторы секунды экран «простаивал» ещё до первой
    // попытки, из-за этого загрузка страницы казалась лагающей. Код-сервер
    // всё равно поднимается за секунды, здесь мы лишь не мешаем ему.
    private static final int INITIAL_DELAY_MS = 400;
    /** Пауза между повторными попытками, пока сервер поднимается. */
    private static final int RETRY_DELAY_MS = 2500;
    /** Максимум повторов (~1 минута), потом показываем страницу с ошибкой. */
    private static final int MAX_ATTEMPTS = 24;

    /**
     * Общий каркас страниц-заглушек.
     *
     * Заглушки — это ОБОЛОЧКА (мы там редко), поэтому светлая матовая холодная
     * гамма Apple: не чистый белый (#F2F2F7), а холодный серый; текст — тёмный
     * графит #1D1D1F. Внизу — лаконичная подпись @4rlein.
     */
    private static final String PAGE_OPEN =
            "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>html,body{height:100%;margin:0;background:#F2F2F7;color:#1D1D1F;"
            + "font:15px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;display:flex;"
            + "align-items:center;justify-content:center;text-align:center}"
            + "b{color:#1D1D1F}.url{color:#6E6E73}.hint{color:#8E8E93;font-size:13px}"
            + ".tag{position:fixed;left:0;right:0;bottom:14px;color:#8E8E93;"
            + "font-size:11px;letter-spacing:.1em;opacity:.85}"
            + "button{margin:6px;padding:9px 18px;border:1px solid #D1D1D6;border-radius:14px;"
            + "background:#E5E5EA;color:#1D1D1F;font:14px inherit;cursor:pointer}"
            + "button:hover{background:#E5E5EA;border-color:#0A84FF}"
            + "</style></head><body><div class=\"tag\">@4rlein</div><div>";

    private static final String PAGE_CLOSE = "</div></body></html>";

    // ⛔ 2026-10-16: вся внешка снесена (темы, прозрачность, шлейф). Цвета
    //    даёт ТОЛЬКО активная тема VS Code — дефолтная. БЕКАП:
    //    backups/PRE_CLEANUP_20261016_205529/

    /**
     * CSS геометрии окон code-server — ТОЛЬКО СКРУГЛЕНИЯ И ОТСТУПЫ.
     *
     * ⛔ 2026-10-16: раздел 4 «СКОЗНАЯ ПРОЗРАЧНОСТЬ» (3.5) ВЫРЕЗАН —
     *    transparent-подложки, rgba(0,0,0,0.4) поверх обоев и чёрные
     *    статус/титул-бары были вне темы и перекрывали цвета. Теперь цвета
     *    даёт ТОЛЬКО активная тема VS Code (дефолтная). БЕКАП:
     *        OpenCodeLauncher/backups/PRE_CLEANUP_20261016_205529/
     *        OpenCodeLauncher/backups/20261015-140446-colors-themes(.tar.gz)
     *
     * БЛОКИ (осталась одна геометрия):
     *  1) ЕДИНЫЙ РАДИУС 14px: токены --vscode-cornerRadius-* + литеральные px.
     *  2) ЗАЗОРЫ МЕЖДУ ОКНАМИ = НАТИВНЫЕ 4px (между окнами) / 8px (у края экрана).
     *     ВАЖНО (история 2.9.12 → 3.0 → 3.1): раньше тут стояло
     *     `--vscode-spacing-size40: 8px !important`, и оно ЛОМАЛО раскладку.
     *     JS воркбенча (URo / BRo / zRo, EditorPart.layout) резервирует под
     *     карточки ЖЁСТКИЕ константы AS=4px (внутренний зазор) и AS*2=8px (у
     *     края экрана) и CSS-токены НЕ читает. CSS-отступы 8/16px против
     *     JS-резерва 4/8px → каждая карточка на 4–8px больше своей ячейки →
     *     наезд и ОБРЕСКА по краям: меню «...» и кнопка разделения окна в
     *     правом верхнем углу редактора уходили за границу, ползунки
     *     скроллбара вылезали из окна, промежутки «плясали» и росли.
     *     ЕДИНСТВЕННОЕ значение, с которым сходятся JS и CSS, — 4px,
     *     поэтому токен больше НЕ переопределяется.
     *  3) СКРОЛЛБАРЫ: «пилюля», в 2px от кромки — не вылезают за границу окна;
     *     углы карточки не пропускают контент (overflow:hidden).
     *
     * Идемпотентен (флаг #oclglass), внедряется ТОЛЬКО в onPageFinished.
     */
    private static final String GLASS_CSS =
            """
            (function () {
              try {
                if (document.getElementById('oclglass')) { return; }
                var css = ''
                  // === 0. УСТРАНЕНИЕ БЕЛЫХ ПОЛОС СНИЗУ И СПРАВА У КРАЁВ ЭКРАНА ===
                  // На экранах с дробным DPR (например 3200×2136 при 440 dpi,
                  // DPR=2.75 → 1163.64×776.73 CSS px) JS воркбенча округляет
                  // инлайновые width/height .monaco-workbench вниз до целых
                  // CSS-пикселей (1163×776), оставляя ~2 физических пикселя
                  // справа и снизу, где просвечивал белый фон html/body/WebView.
                  // Закрываем зазор фоном html/body и внешней тенью-продолжением
                  // цвета оболочки/статус-бара на .monaco-workbench.
                  + 'html, body { '
                  + '  margin: 0 !important; '
                  + '  padding: 0 !important; '
                  + '  width: 100% !important; '
                  + '  height: 100% !important; '
                  + '  overflow: hidden !important; '
                  + '  background-color: var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, var(--vscode-editor-background, #1C1C1E))) !important; '
                  + '}'
                  + '.monaco-workbench { '
                  + '  box-shadow: '
                  + '    8px 0 0 0 var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, var(--vscode-editor-background, #1C1C1E))), '
                  + '    0 8px 0 0 var(--vscode-statusBar-background, var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, var(--vscode-editor-background, #1C1C1E)))), '
                  + '    8px 8px 0 0 var(--vscode-statusBar-background, var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, var(--vscode-editor-background, #1C1C1E)))) !important; '
                  + '}'
                  + '.monaco-workbench .part.statusbar { '
                  + '  box-shadow: '
                  + '    8px 0 0 0 var(--vscode-statusBar-background, var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, #1C1C1E))), '
                  + '    0 8px 0 0 var(--vscode-statusBar-background, var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, #1C1C1E))), '
                  + '    8px 8px 0 0 var(--vscode-statusBar-background, var(--modern-ui-shell-background, var(--vscode-titleBar-activeBackground, #1C1C1E))) !important; '
                  + '}'
                  // === 1. ЕДИНЫЙ ШАБЛОН СКРУГЛЕНИЯ = 14px ======================
                  // Все пять px-токенов (кроме circle — бейджи/аватары круглые)
                  // принудительно = 14px: :root/body — для view на body,
                  // .monaco-workbench — нативная выдача токенов
                  // (updateDynamicCSSRules) пишет их сюда без !important.
                  + ':root, body, .monaco-workbench { '
                  + '  --vscode-cornerRadius-xSmall: 14px !important; '
                  + '  --vscode-cornerRadius-small: 14px !important; '
                  + '  --vscode-cornerRadius-medium: 14px !important; '
                  + '  --vscode-cornerRadius-large: 14px !important; '
                  + '  --vscode-cornerRadius-xLarge: 14px !important; '
                  + '}'
                  // Крупные части = карточки того же шаблона.
                  + '.monaco-workbench.floating-panels .part.sidebar, '
                  + '.monaco-workbench.floating-panels .part.auxiliarybar, '
                  + '.monaco-workbench.floating-panels .part.panel { '
                  + '  border-radius: 14px !important; '
                  + '}'
                  // new_era (2026-10-17): карточка редактора содержит полосу
                  // вкладок — тоже 14px, иначе её верхние углы стоят квадратом
                  // между светлым титул-баром и тёмной полосой вкладок.
                  + '.monaco-workbench.floating-panels .part.editor { '
                  + '  border-radius: 14px !important; '
                  + '  overflow: hidden !important; '
                  + '}'
                  // Чипы вкладок скругляются сверху — пилюли-«папки»,
                  // а не квадраты; сама полоса — карточка из правила выше.
                  + '.monaco-workbench .editor-group-container .tabs-container '
                  + '> .tab { '
                  + '  border-top-left-radius: 10px !important; '
                  + '  border-top-right-radius: 10px !important; '
                  + '}'
                  // Мелкие плавающие окна и фокус-«облачка».
                  + '.suggest-widget, .monaco-hover, .monaco-menu-container, '
                  + '.monaco-list-container, .quick-input-widget, '
                  + '.notifications-toasts .notification, .telemetry-opt-in-view, '
                  + '.keybinding-entry, .monaco-dialog-box { '
                  + '  border-radius: 14px !important; '
                  + '}'
                  + '.monaco-workbench :focus-visible { '
                  + '  border-radius: 14px !important; '
                  + '}'
                  // Контролы с ЛИТЕРАЛЬНЫМИ px в нативном CSS (4px) — приводим
                  // к тому же шаблону: поля ввода, кнопки, селекты, чипы клавиш.
                  + '.monaco-inputbox, .monaco-text-button, .monaco-button, '
                  + '.monaco-select-box, .monaco-keybinding-key { '
                  + '  border-radius: 14px !important; '
                  + '}'
                  // === 2. ЗАЗОРЫ: НАТИВНЫЕ 4px — НЕ ТРОГАТЬ ====================
                  // См. javadoc: JS раскладки резервирует ровно AS=4 / AS*2=8.
                  // Здесь только ВЫРАВНИВАНИЕ внутренних отступов на эту же
                  // сетку 4px, чтобы всё стояло «по линеечке».
                  // Статус-бар нативно паддингует 6px (size60) — ставим 4px,
                  // тогда его элементы совпадают по линии с карточками выше.
                  + '.monaco-workbench.floating-panels .part.statusbar { '
                  + '  padding-left: 4px !important; '
                  + '  padding-right: 4px !important; '
                  + '}'
                  // Действия в шапке редактора (кнопка разделения окна и «...»):
                  // тот же шаг 4px, всегда внутри карточки, без вылезания на
                  // скруглённый угол.
                  + '.monaco-workbench .part.editor .editor-group-container '
                  + '> .title .editor-actions, '
                  + '.monaco-workbench .part.editor .editor-group-container '
                  + '> .editor-group-header .editor-actions { '
                  + '  padding: 0 4px !important; '
                  + '  box-sizing: border-box; '
                  + '  max-width: 100%; '
                  + '}'
                  // Всплывающие окна не шире экрана (меню «...» у правого края).
                  + '.monaco-workbench .context-view { '
                  + '  max-width: calc(100vw - 16px); '
                  + '  max-height: calc(100vh - 16px); '
                  + '}'
                  // === 3. СКРОЛЛБАРЫ: внутрь окна, «пилюлей» ===================
                  + '.monaco-workbench .monaco-scrollable-element > .scrollbar { '
                  + '  border-radius: 10px; '
                  + '}'
                  + '.monaco-workbench .monaco-scrollable-element > .scrollbar '
                  + '> .slider { '
                  + '  border-radius: 10px !important; '
                  + '}'
                  // Отступ 2px от кромки карточки: ползунок не садится на
                  // скруглённый угол и не читается как «вылезший за границу».
                  + '.monaco-workbench.floating-panels .part .monaco-scrollable-element'
                  + ' > .scrollbar.vertical { right: 2px !important; }'
                  + '.monaco-workbench.floating-panels .part .monaco-scrollable-element'
                  + ' > .scrollbar.horizontal { bottom: 2px !important; }'
                  // Скруглённая карточка не должна пропускать контент за угол:
                  + '.monaco-workbench.floating-panels .part.editor > .content { '
                  + '  overflow: hidden !important; '
                  + '}'
                  // new_era: то же для содержимого боковых карточек (древо/
                  // расширения/Copilot/панель) — текст и списки обрезаются
                  // ровно по радиусу 14px, не «вылезают» за угол.
                  + '.monaco-workbench.floating-panels .part.sidebar > .content, '
                  + '.monaco-workbench.floating-panels .part.auxiliarybar > .content, '
                  + '.monaco-workbench.floating-panels .part.panel > .content { '
                  + '  overflow: hidden !important; '
                  + '}'
                  // === 4. ЭТАЛОННЫЙ СТИЛЬ МАТОВОЙ ПРОЗРАЧНОСТИ APPLE (#F2F2F7) ====
                  // Все прозрачные элементы (всплывающие окна: контекстные меню,
                  // палитра F1, диалоги, подсказки suggest/hover, уведомления,
                  // виджет поиска, command-center, а также боковые окна —
                  // архитектура проекта/Explorer и чат с Copilot) выполнены
                  // в единой светлой гамме Apple (как кастомная шторка: мягкий
                  // не выжигающий глаза #F2F2F7, #E5E5EA, рамки #C7C7CC, текст
                  // #1D1D1F / #6E6E73, акцент #007AFF). За счёт матовой
                  // полупрозрачности (82–88%) при смене темы они сохраняют
                  // светлый оттенок Apple, но смешиваются с фоном активной темы
                  // и приобретают её лёгкий оттенок.
                  //
                  // 4.1) Переменные внутри светлых матовых поверхностей
                  //      (не затрагивают тёмный редактор, терминал и панель снизу):
                  + '.monaco-workbench .part.sidebar, '
                  + '.monaco-workbench .part.auxiliarybar, '
                  + '.monaco-workbench .part.activitybar, '
                  + '.monaco-workbench .quick-input-widget, '
                  + '.monaco-workbench .context-view, '
                  + '.monaco-workbench .monaco-menu-container, '
                  + '.monaco-workbench .action-widget, '
                  + '.monaco-workbench .suggest-widget, '
                  + '.monaco-workbench .workbench-suggest-widget, '
                  + '.monaco-workbench .suggest-details, '
                  + '.monaco-workbench .monaco-hover, '
                  + '.monaco-workbench .parameter-hints-widget, '
                  + '.monaco-workbench .monaco-dialog-box, '
                  + '.monaco-workbench > .notifications-toasts, '
                  + '.monaco-workbench > .notifications-center, '
                  + '.monaco-workbench .find-widget, '
                  + '.monaco-workbench .findOptionsWidget, '
                  + '.monaco-workbench .editor-widget.rename-box { '
                  + '  --vscode-foreground: #1D1D1F !important; '
                  + '  --vscode-descriptionForeground: #6E6E73 !important; '
                  + '  --vscode-errorForeground: #D70015 !important; '
                  + '  --vscode-icon-foreground: #1D1D1F !important; '
                  + '  --vscode-textLink-foreground: #0A5FC0 !important; '
                  + '  --vscode-textLink-activeForeground: #007AFF !important; '
                  + '  --vscode-sideBar-foreground: #1D1D1F !important; '
                  + '  --vscode-sideBarTitle-foreground: #1D1D1F !important; '
                  + '  --vscode-sideBarSectionHeader-foreground: #1D1D1F !important; '
                  + '  --vscode-sideBarSectionHeader-border: rgba(199, 199, 204, 0.55) !important; '
                  + '  --vscode-activityBar-foreground: #1D1D1F !important; '
                  + '  --vscode-activityBar-inactiveForeground: #6E6E73 !important; '
                  + '  --vscode-activityBarTop-foreground: #1D1D1F !important; '
                  + '  --vscode-activityBarTop-inactiveForeground: #6E6E73 !important; '
                  + '  --vscode-activityBarTop-activeBorder: #007AFF !important; '
                  + '  --vscode-modernTab-activeBackground: rgba(209, 209, 214, 0.75) !important; '
                  + '  --vscode-modernTab-activeForeground: #1D1D1F !important; '
                  + '  --vscode-modernTab-hoverBackground: rgba(229, 229, 234, 0.55) !important; '
                  + '  --vscode-modernTab-hoverForeground: #1D1D1F !important; '
                  + '  --modern-ui-tab-active-background: rgba(209, 209, 214, 0.75) !important; '
                  + '  --modern-ui-tab-hover-background: rgba(229, 229, 234, 0.55) !important; '
                  + '  --vscode-input-background: rgba(229, 229, 234, 0.78) !important; '
                  + '  --vscode-input-foreground: #1D1D1F !important; '
                  + '  --vscode-input-border: rgba(199, 199, 204, 0.75) !important; '
                  + '  --vscode-input-placeholderForeground: #6E6E73 !important; '
                  + '  --vscode-dropdown-background: rgba(229, 229, 234, 0.85) !important; '
                  + '  --vscode-dropdown-foreground: #1D1D1F !important; '
                  + '  --vscode-dropdown-border: rgba(199, 199, 204, 0.75) !important; '
                  + '  --vscode-button-background: #007AFF !important; '
                  + '  --vscode-button-foreground: #FFFFFF !important; '
                  + '  --vscode-button-hoverBackground: #0A5FC0 !important; '
                  + '  --vscode-button-secondaryBackground: rgba(209, 209, 214, 0.75) !important; '
                  + '  --vscode-button-secondaryForeground: #1D1D1F !important; '
                  + '  --vscode-button-secondaryHoverBackground: rgba(199, 199, 204, 0.85) !important; '
                  + '  --vscode-badge-background: #007AFF !important; '
                  + '  --vscode-badge-foreground: #FFFFFF !important; '
                  + '  --vscode-list-activeSelectionBackground: #007AFF !important; '
                  + '  --vscode-list-activeSelectionForeground: #FFFFFF !important; '
                  + '  --vscode-list-activeSelectionIconForeground: #FFFFFF !important; '
                  + '  --vscode-list-inactiveSelectionBackground: rgba(209, 209, 214, 0.68) !important; '
                  + '  --vscode-list-inactiveSelectionForeground: #1D1D1F !important; '
                  + '  --vscode-list-hoverBackground: rgba(229, 229, 234, 0.60) !important; '
                  + '  --vscode-list-hoverForeground: #1D1D1F !important; '
                  + '  --vscode-list-focusBackground: #007AFF !important; '
                  + '  --vscode-list-focusForeground: #FFFFFF !important; '
                  + '  --vscode-list-highlightForeground: #0A5FC0 !important; '
                  + '  --vscode-list-focusHighlightForeground: #FFFFFF !important; '
                  + '  --vscode-list-deemphasizedForeground: #6E6E73 !important; '
                  + '  --vscode-list-warningForeground: #B25000 !important; '
                  + '  --vscode-list-errorForeground: #D70015 !important; '
                  + '  --vscode-gitDecoration-modifiedResourceForeground: #B25000 !important; '
                  + '  --vscode-gitDecoration-stageModifiedResourceForeground: #B25000 !important; '
                  + '  --vscode-gitDecoration-addedResourceForeground: #0F7B3E !important; '
                  + '  --vscode-gitDecoration-untrackedResourceForeground: #0F7B3E !important; '
                  + '  --vscode-gitDecoration-deletedResourceForeground: #D70015 !important; '
                  + '  --vscode-gitDecoration-stageDeletedResourceForeground: #D70015 !important; '
                  + '  --vscode-gitDecoration-ignoredResourceForeground: #6E6E73 !important; '
                  + '  --vscode-tree-indentGuidesStroke: rgba(199, 199, 204, 0.75) !important; '
                  + '  --vscode-toolbar-hoverBackground: rgba(209, 209, 214, 0.55) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  // 4.2) Боковые окна (архитектура проекта / Explorer и чат с Copilot):
                  //      единый светлый матово-прозрачный слой #F2F2F7 (83%),
                  //      внутренние обёртки прозрачны, чтобы не множить альфу.
                  + '.monaco-workbench.floating-panels .part.sidebar, '
                  + '.monaco-workbench.floating-panels .part.auxiliarybar, '
                  + '.modern-ui.floating-panels :is(.part.sidebar, .part.auxiliarybar), '
                  + '.monaco-workbench .part.sidebar, '
                  + '.monaco-workbench .part.auxiliarybar { '
                  + '  background-color: rgba(242, 242, 247, 0.83) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.55) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench .part.activitybar { '
                  + '  background-color: rgba(229, 229, 234, 0.83) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) > .title, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) > .header-or-footer, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) > .content, '
                  + '.modern-ui :is(.part.sidebar, .part.auxiliarybar) .monaco-pane-view .pane, '
                  + '.modern-ui :is(.part.sidebar, .part.auxiliarybar) .monaco-pane-view .pane > .pane-body, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .monaco-list .monaco-list-rows, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-list { '
                  + '  background: transparent !important; '
                  + '  background-color: transparent !important; '
                  + '}'
                  + '.modern-ui :is(.part.sidebar, .part.auxiliarybar) .monaco-pane-view .pane > .pane-header, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .monaco-pane-view .pane > .pane-header { '
                  + '  background-color: rgba(229, 229, 234, 0.45) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .monaco-tree-sticky-container, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .monaco-tree-sticky-row { '
                  + '  background-color: rgba(242, 242, 247, 0.92) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  // Списки и дерево внутри боковых панелей и всплывающих окон:
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar, .quick-input-widget, .suggest-widget, .workbench-suggest-widget, .notifications-center, .notifications-toasts) .monaco-list .monaco-list-row { '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar, .quick-input-widget, .suggest-widget, .workbench-suggest-widget) .monaco-list:not(.drop-target) > div.monaco-scrollable-element > .monaco-list-rows > .monaco-list-row:hover:not(.selected):not(.focused) { '
                  + '  background-color: rgba(209, 209, 214, 0.45) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .monaco-list > div.monaco-scrollable-element > .monaco-list-rows > .monaco-list-row.selected { '
                  + '  background-color: rgba(209, 209, 214, 0.68) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar, .quick-input-widget, .suggest-widget, .workbench-suggest-widget) .monaco-list:focus > div.monaco-scrollable-element > .monaco-list-rows > .monaco-list-row.selected, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar, .quick-input-widget, .suggest-widget, .workbench-suggest-widget) .monaco-list:focus > div.monaco-scrollable-element > .monaco-list-rows > .monaco-list-row.focused { '
                  + '  background-color: #007AFF !important; '
                  + '  color: #FFFFFF !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar, .quick-input-widget, .suggest-widget, .workbench-suggest-widget) .monaco-list:focus > div.monaco-scrollable-element > .monaco-list-rows > .monaco-list-row:is(.selected, .focused) :is(.monaco-icon-label, .label-name, .label-description, .codicon, .monaco-highlighted-label) { '
                  + '  color: #FFFFFF !important; '
                  + '}'
                  // Поле ввода и карточки чата Copilot в боковой панели:
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-input-container, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-editor-container .monaco-editor, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-editor-container .monaco-editor-background, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-editing-session-container, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-todo-list-widget { '
                  + '  background-color: rgba(229, 229, 234, 0.78) !important; '
                  + '  color: #1D1D1F !important; '
                  + '  border-color: rgba(199, 199, 204, 0.75) !important; '
                  + '}'
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-editor-container .view-lines, '
                  + '.monaco-workbench :is(.part.sidebar, .part.auxiliarybar) .interactive-session .chat-editor-container .view-line { '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  // 4.3) Всплывающие окна (контекстные меню, палитра F1, диалоги,
                  //      подсказки suggest/hover, уведомления, поиск, command-center)
                  //      — тот же светлый матово-прозрачный стиль нашей шторки:
                  + '.monaco-menu .monaco-menu-container, '
                  + '.context-view.monaco-menu-container > .monaco-scrollable-element, '
                  + '.action-widget, '
                  + '.action-widget .action-widget-action-bar, '
                  + '.action-list-submenu-panel { '
                  + '  background-color: rgba(242, 242, 247, 0.85) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-menu .monaco-action-bar.vertical .action-menu-item:not(.focused) .action-label, '
                  + '.monaco-menu .monaco-action-bar.vertical .action-menu-item:not(.focused) .keybinding { '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-menu .monaco-action-bar.vertical .action-menu-item.focused, '
                  + '.monaco-menu .monaco-action-bar.vertical .action-menu-item:hover:not(.disabled) { '
                  + '  background-color: #007AFF !important; '
                  + '  color: #FFFFFF !important; '
                  + '}'
                  + '.monaco-menu .monaco-action-bar.vertical .action-menu-item:is(.focused, :hover:not(.disabled)) :is(.action-label, .keybinding, .submenu-indicator) { '
                  + '  color: #FFFFFF !important; '
                  + '}'
                  + '.quick-input-widget { '
                  + '  background-color: rgba(242, 242, 247, 0.85) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.quick-input-widget .quick-input-titlebar { '
                  + '  background-color: rgba(229, 229, 234, 0.65) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.quick-input-widget .quick-input-list .monaco-list-rows, '
                  + '.quick-input-widget .quick-input-list .monaco-tree-sticky-container, '
                  + '.quick-input-widget .quick-input-list .monaco-tree-sticky-row, '
                  + '.monaco-editor .suggest-widget .monaco-list-rows, '
                  + '.workbench-suggest-widget .monaco-list-rows { '
                  + '  background: transparent !important; '
                  + '  background-color: transparent !important; '
                  + '}'
                  + '.monaco-dialog-box { '
                  + '  background-color: rgba(242, 242, 247, 0.86) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-editor .suggest-widget, '
                  + '.monaco-editor .suggest-details, '
                  + '.workbench-suggest-widget, '
                  + '.workbench-suggest-widget .suggest-details, '
                  + '.monaco-workbench .suggest-details { '
                  + '  background-color: rgba(242, 242, 247, 0.88) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-editor .monaco-hover, '
                  + '.monaco-hover.workbench-hover, '
                  + '.monaco-editor .monaco-resizable-hover, '
                  + '.monaco-editor .parameter-hints-widget, '
                  + '.standalone-colorpicker { '
                  + '  background-color: rgba(242, 242, 247, 0.88) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-editor .monaco-hover .hover-row .actions, '
                  + '.monaco-hover.workbench-hover .hover-row .actions { '
                  + '  background-color: rgba(229, 229, 234, 0.70) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-editor .find-widget, '
                  + '.monaco-editor .findOptionsWidget, '
                  + '.monaco-editor .editor-widget.rename-box { '
                  + '  background-color: rgba(242, 242, 247, 0.86) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench > .notifications-toasts .notification-toast { '
                  + '  background: rgba(242, 242, 247, 0.88) !important; '
                  + '  background-color: rgba(242, 242, 247, 0.88) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.60) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench > .notifications-toasts .notifications-list-container, '
                  + '.monaco-workbench > .notifications-toasts .notifications-list-container .monaco-list-rows { '
                  + '  background: transparent !important; '
                  + '  background-color: transparent !important; '
                  + '}'
                  + '.monaco-workbench > .notifications-center > .notifications-center-header { '
                  + '  background: rgba(229, 229, 234, 0.75) !important; '
                  + '  background-color: rgba(229, 229, 234, 0.75) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench > .notifications-center .notifications-list-container { '
                  + '  background: rgba(242, 242, 247, 0.88) !important; '
                  + '  background-color: rgba(242, 242, 247, 0.88) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench > .notifications-center .notifications-list-container .monaco-list-rows { '
                  + '  background: transparent !important; '
                  + '  background-color: transparent !important; '
                  + '}'
                  + '.monaco-workbench .part.titlebar > .titlebar-container > .titlebar-center > .window-title > .command-center .action-item.command-center-center { '
                  + '  background-color: rgba(242, 242, 247, 0.78) !important; '
                  + '  border: 1px solid rgba(199, 199, 204, 0.55) !important; '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  + '.monaco-workbench .part.titlebar > .titlebar-container > .titlebar-center > .window-title > .command-center .action-item.command-center-center :is(.codicon, .search-label) { '
                  + '  color: #1D1D1F !important; '
                  + '}'
                  // 4.4) Единый красно-розовый цвет курсора #FF2D55 (как и шлейф за ним)
                  //      во всех редакторах, полях ввода и терминале независимо от темы.
                  + ':root, body, .monaco-workbench { '
                  + '  --vscode-editorCursor-foreground: #FF2D55 !important; '
                  + '  --vscode-editorMultiCursor-primary-foreground: #FF2D55 !important; '
                  + '  --vscode-editorMultiCursor-secondary-foreground: #FF2D55 !important; '
                  + '  --vscode-terminalCursor-foreground: #FF2D55 !important; '
                  + '  caret-color: #FF2D55 !important; '
                  + '}'
                  + '.monaco-workbench input, '
                  + '.monaco-workbench textarea, '
                  + '.monaco-workbench [contenteditable="true"], '
                  + '.monaco-editor .inputarea.ime-input, '
                  + '.monaco-editor .native-edit-context { '
                  + '  caret-color: #FF2D55 !important; '
                  + '}'
                  + '.monaco-editor .cursors-layer .cursor, '
                  + '.monaco-editor .cursors-layer .cursor-primary, '
                  + '.monaco-editor .cursors-layer .cursor-secondary { '
                  + '  background-color: #FF2D55 !important; '
                  + '  border-color: #FF2D55 !important; '
                  + '}'
                  + '.xterm .xterm-rows .xterm-cursor.xterm-cursor-block { '
                  + '  background-color: #FF2D55 !important; '
                  + '}'
                  + '.xterm .xterm-rows .xterm-cursor.xterm-cursor-outline { '
                  + '  outline-color: #FF2D55 !important; '
                  + '}'
                  + '.xterm .xterm-rows .xterm-cursor.xterm-cursor-bar { '
                  + '  box-shadow: 2px 0 0 #FF2D55 inset !important; '
                  + '}'
                  + '.xterm .xterm-rows .xterm-cursor.xterm-cursor-underline { '
                  + '  border-bottom-color: #FF2D55 !important; '
                  + '}'
                var s = document.createElement('style');
                s.id = 'oclglass';
                s.textContent = css;
                (document.head || document.documentElement).appendChild(s);
              } catch (e) {}
            })();
            """;

    /**
     * Прижимает всплывающие окна (меню, палитру F1, подсказки, уведомления)
     * к границам экрана, чтобы они не «уходили за границу».
     *
     * Контекстное меню (в т.ч. меню «...» и кнопка разделения окна в шапке
     * редактора) VS Code позиционирует сам: ставит инлайновые left/top от
     * координат кнопки. Если кнопка стоит у правого/нижнего края (а в
     * плавающих карточках modernUI она почти всегда у кромки), меню может
     * вылезти за край экрана. Скрипт после открытия (MutationObserver по
     * добавлению DOM + пара повторных проходов, пока меню дорисовывается)
     * сдвигает окно внутрь экрана с отступом 8px — ровно настолько,
     * насколько оно вылезло.
     *
     * Позиционирование трогаем АККУРАТНО: инлайновые left/top поправляем
     * попиксельно; если их нет (окно двигается transform'ом) — складываем
     * сдвиг в НАШ transform, не затирая чужой.
     *
     * Наблюдатель отфильтрован: срабатывает только на DOM внутри самих
     * всплывающих окон (всплытие по родителям), поэтому поток вывода
     * терминала/редактора не заставляет его крутиться каждый кадр
     * (антилаг 2.6/2.7).
     *
     * Идемпотентен (флаг {@code window.__oclClamp}), внедряется в onPageFinished.
     */
    private static final String EDGE_CLAMP_JS =
            """
            (function () {
              if (window.__oclClamp) { return; }
              window.__oclClamp = true;
              var M = 8; // отступ от края экрана
              var POPUP = ['context-view', 'quick-input-widget', 'suggest-widget',
                'monaco-hover', 'notifications-toasts', 'monaco-menu-container'];
              var queued = false;

              function popupRoot(node) {
                for (var el = node; el; el = el.parentElement) {
                  var c = el.classList;
                  if (!c) { continue; }
                  for (var i = 0; i < POPUP.length; i++) {
                    if (c.contains(POPUP[i])) { return el; }
                  }
                }
                return null;
              }

              function shift(el, dx, dy) {
                var left = el.style.left, top = el.style.top;
                if (left && left.slice(-2) === 'px'
                    && top && top.slice(-2) === 'px') {
                  var lp = parseFloat(left), tp = parseFloat(top);
                  if (!isNaN(lp) && !isNaN(tp)) {
                    el.style.left = (lp + dx) + 'px';
                    el.style.top = (tp + dy) + 'px';
                    return;
                  }
                }
                // left/top не в пикселях — двигаем transform'ом, складывая
                // сдвиг с тем, что уже стоит (чужой transform не затираем).
                var base = el.__oclNudge || { x: 0, y: 0 };
                base.x += dx; base.y += dy;
                el.__oclNudge = base;
                el.style.transform = 'translate(' + base.x + 'px,'
                  + base.y + 'px)';
              }

              function clampOne(el) {
                try {
                  var r = el.getBoundingClientRect();
                  if (r.width < 1 || r.height < 1) { return; }
                  var dx = 0, dy = 0;
                  if (r.right > window.innerWidth - M) {
                    dx = window.innerWidth - M - r.right;
                  }
                  if (r.left + dx < M) { dx = M - r.left; }
                  if (r.bottom > window.innerHeight - M) {
                    dy = window.innerHeight - M - r.bottom;
                  }
                  if (r.top + dy < M) { dy = M - r.top; }
                  if (dx || dy) { shift(el, dx, dy); }
                } catch (e) {}
              }

              function clampAll() {
                queued = false;
                try {
                  var list = document.querySelectorAll('.context-view, '
                    + '.quick-input-widget, .suggest-widget, .monaco-hover, '
                    + '.notifications-toasts');
                  for (var i = 0; i < list.length; i++) { clampOne(list[i]); }
                } catch (e) {}
              }

              function schedule() {
                if (queued) { return; }
                queued = true;
                var raf = window.requestAnimationFrame;
                if (raf) { raf(function () { clampAll(); }); }
                else { setTimeout(clampAll, 16); }
                // Меню дорисовывается не в один кадр — прижимаем ещё дважды.
                setTimeout(clampAll, 80);
                setTimeout(clampAll, 320);
              }

              try {
                new MutationObserver(function (records) {
                  for (var i = 0; i < records.length; i++) {
                    var added = records[i].addedNodes;
                    for (var j = 0; j < added.length; j++) {
                      var n = added[j];
                      if (n.nodeType !== 1) { continue; }
                      if (popupRoot(n) || popupRoot(n.parentElement)) {
                        schedule();
                        return;
                      }
                    }
                  }
                }).observe(document.documentElement,
                    { childList: true, subtree: true });
              } catch (e) {}

              ['keydown', 'pointerup'].forEach(function (name) {
                document.addEventListener(name, schedule, true);
              });
              window.addEventListener('resize', schedule);
            })();
            """;

    /**
     * Розово-красный след-«призрак» за кареткой (восстановлен 2026-10-17,
     * эпоха new_era_01_v_1 — ранее вырезан при сносе внешки).
     *
     * Цвет {@code #FF2D55} обязан совпадать с кареткой темы «OCL New Era»
     * ({@code editorCursor.foreground}/{@code terminalCursor.foreground}).
     *
     * Антилаг-архитектура (проверена в 2.6/2.7):
     * <ul>
     *   <li>кадровый режим {@code requestAnimationFrame}, пока каретка движется —
     *       чтение позиции совпадает с той раскладкой, которую браузер и так
     *       делает перед покраской;</li>
     *   <li>«сон» 250 мс, пока каретка стоит, 400 мс — пока страница в фоне;
     *       сон прерывают события ввода (ответ отклика мгновенный);</li>
     *   <li>поток вывода распознаётся (каретка дёргается 4 тика подряд) и
     *       усыпляет опрос на 600 мс;</li>
     *   <li>уход призрака — через WAAPI {@code animate()} (композитор), без
     *       {@code void offsetWidth} и CSS-переходов; {@code will-change:
     *       transform} держит призраки на GPU-слоях (композитинг без ре-растр).</li>
     * </ul>
     *
     * Требование: {@code terminal.integrated.gpuAcceleration: "off"} — при
     * WebGL-рендере xterm каретка рисуется на канвасе и её позиция недоступна
     * DOM (след будет только в редакторе).
     *
     * Идемпотентен (флаг {@code window.__oclTrail}), внедряется в onPageFinished.
     */
    private static final String CURSOR_TRAIL_JS =
            """
            (function () {
              if (window.__oclTrail) { return; }
              window.__oclTrail = true;
              var COLOR = '#FF2D55';
              var N = 8, ghosts = [], next = 0;
              var SEL = '.monaco-editor .cursor, .xterm .xterm-cursor, '
                + '.xterm-screen .xterm-cursor, .xterm-cursor';
              var lastX = -1, lastY = -1;
              var streak = 0, busyUntil = 0, lastSpawn = 0;
              var timer = 0, timerIsRaf = false;

              function now() {
                return (window.performance && performance.now)
                  ? performance.now() : Date.now();
              }

              function cancel() {
                if (!timer) { return; }
                if (timerIsRaf && window.cancelAnimationFrame) {
                  cancelAnimationFrame(timer);
                } else {
                  clearTimeout(timer);
                }
                timer = 0;
              }

              // Один планировщик на два режима: ms<=0 — следующий кадр (rAF),
              // иначе — таймер сна.
              function later(ms) {
                cancel();
                if (ms <= 0 && window.requestAnimationFrame) {
                  timerIsRaf = true;
                  timer = requestAnimationFrame(loop);
                } else {
                  timerIsRaf = false;
                  timer = setTimeout(loop, ms || 16);
                }
              }

              // Прерываем сон при любом действии пользователя.
              function wake() {
                if (timer && !timerIsRaf) { later(0); }
              }
              ['input', 'keyup', 'pointerup', 'mouseup', 'click'].forEach(function (n) {
                document.addEventListener(n, wake, true);
              });
              document.addEventListener('visibilitychange', wake);

              function make() {
                var g = document.createElement('div');
                g.style.cssText = 'position:fixed;left:0;top:0;pointer-events:none;'
                  + 'opacity:0;z-index:99;border-radius:2px;'
                  + 'will-change:transform,opacity;background:' + COLOR;
                document.body.appendChild(g);
                return g;
              }

              function caret() {
                var el = document.querySelector(SEL);
                if (!el) { return null; }
                var r = el.getBoundingClientRect();
                if (r.width > 0 && r.height > 0
                    && r.top > -20 && r.top < window.innerHeight + 20) {
                  return r;
                }
                return null;
              }

              function spawn(r, x, y) {
                if (!ghosts.length) {
                  for (var i = 0; i < N; i++) { ghosts.push(make()); }
                }
                var g = ghosts[next++ % N];
                if (g.__oclAnim) { try { g.__oclAnim.cancel(); } catch (e) {} }
                g.style.width = Math.max(2, Math.round(r.width)) + 'px';
                g.style.height = Math.round(r.height) + 'px';
                g.style.opacity = '';
                var from = 'translate(' + x + 'px,' + y + 'px) scale(1)';
                var to = 'translate(' + x + 'px,' + (y + 6) + 'px) scale(.35)';
                if (g.animate) {
                  g.__oclAnim = g.animate(
                      [{ opacity: 0.7, transform: from },
                       { opacity: 0, transform: to }],
                      { duration: 500, easing: 'linear', fill: 'forwards' });
                } else {
                  g.style.transition = 'none';
                  g.style.opacity = '0.7';
                  g.style.transform = from;
                  void g.offsetWidth;
                  g.style.transition = 'opacity .5s linear, transform .5s ease-out';
                  g.style.opacity = '0';
                  g.style.transform = to;
                }
              }

              function loop() {
                timer = 0;
                try {
                  if (document.hidden || !document.body) { later(400); return; }
                  var t = now();
                  if (t < busyUntil) {
                    later(Math.min(busyUntil - t, 200));
                    return;
                  }
                  var r = caret();
                  if (!r) { lastX = lastY = -1; streak = 0; later(250); return; }
                  var x = Math.round(r.left), y = Math.round(r.top);
                  if (x === lastX && y === lastY) {
                    // Каретка стоит — опрашивать нечего.
                    streak = 0;
                    later(250);
                    return;
                  }
                  streak++;
                  if (streak >= 4) {
                    // Дёргается каждый кадр подряд — это поток вывода, а не
                    // набор: раскладкой во время потока не дёргаемся (600 мс).
                    streak = 0;
                    busyUntil = t + 600;
                    later(600);
                    return;
                  }
                  lastX = x; lastY = y;
                  if (t - lastSpawn < 60) { later(0); return; } // не чаще 60 мс
                  lastSpawn = t;
                  spawn(r, x, y);
                  later(0);
                } catch (e) {
                  later(500);
                }
              }

              later(0);
            })();
            """;

    /**
     * JS-шим, подменяющий navigator.clipboard на системный буфер обмена Android.
     *
     * WebView внутри http://127.0.0.1:8080 отказывается отдавать readText()
     * («Unable to read from the browser's clipboard…»), поэтому страница
     * (code-server/OpenCode) не может вставить текст. Сим перехватывает
     * clipboard.readText()/writeText()/read()/write() и события copy/paste,
     * читая и записывая буфер через JavascriptInterface {@link Bridge}.
     *
     * Внедряется дважды: в onPageStarted (как можно раньше, пока страница не
     * успела сохранить ссылку на «родной» clipboard) и в onPageFinished
     * (на случай, если первая вставка попала на промежуточный документ).
     *
     * Содержит общий движок вставки {@code window.__oclPaste(text, target)} —
     * его используют Java (Ctrl+V) и перехват insertFromPaste; он не зависит
     * от DataTransfer/ClipboardEvent (см. makeData): на части WebView
     * конструкторы недоступны, и вставка молча не срабатывала.
     */
    private static final String CLIPBOARD_JS =
            """
            (function () {
              if (window.__launcherClipboard) { return; }
              window.__launcherClipboard = true;
              var B = window.Launcher;

              function read() {
                try {
                  if (B && typeof B.readClipboardText === 'function') {
                    return String(B.readClipboardText() || '');
                  }
                } catch (e) {}
                return '';
              }

              function write(text) {
                try {
                  if (B && typeof B.writeClipboardText === 'function') {
                    B.writeClipboardText(String(text == null ? '' : text));
                  }
                } catch (e) {}
              }

              // Данные события paste: DataTransfer, если конструктор доступен,
              // иначе «утка» — xterm/monaco читают только getData('text/plain').
              function makeData(text) {
                try {
                  var dt = new DataTransfer();
                  dt.setData('text/plain', text);
                  if (dt.getData('text/plain')) { return dt; }
                } catch (e) {}
                return {
                  getData: function (t) {
                    return (!t || t === 'text/plain') ? text : '';
                  },
                  setData: function () {}, clearData: function () {},
                  types: ['text/plain'], items: [], files: []
                };
              }

              // Синтетическое событие paste с нашим текстом в цели.
              // Не зависит от того, умеет ли WebView собирать ClipboardEvent.
              window.__oclPaste = function (text, target) {
                if (!text) { return false; }
                var el = target || document.activeElement || document.body;
                if (!el) { return false; }
                var ev = null;
                try {
                  ev = new ClipboardEvent('paste',
                      { clipboardData: makeData(text), bubbles: true, cancelable: true });
                  if (!ev.clipboardData) { ev = null; }
                } catch (e) { ev = null; }
                if (!ev) {
                  try {
                    ev = new Event('paste', { bubbles: true, cancelable: true });
                    Object.defineProperty(ev, 'clipboardData', { value: makeData(text) });
                  } catch (e) { return false; }
                }
                el.dispatchEvent(ev);
                return true;
              };

              var shim = {
                readText: function () { return Promise.resolve(read()); },
                writeText: function (text) { write(text); return Promise.resolve(); },
                read: function () {
                  try {
                    var text = read();
                    var item = {};
                    item['text/plain'] = new Blob([text], { type: 'text/plain' });
                    return Promise.resolve([new ClipboardItem(item)]);
                  } catch (e) { return Promise.reject(e); }
                },
                write: function (clipItem) {
                  try {
                    var list = (clipItem && clipItem.getItems) ? clipItem.getItems() : [];
                    Promise.resolve(list[0])
                      .then(function (blob) { return blob && blob.text ? blob.text() : ''; })
                      .then(write, function () {});
                  } catch (e) {}
                  return Promise.resolve();
                }
              };

              try {
                Object.defineProperty(navigator, 'clipboard', {
                  value: shim, configurable: true, enumerable: true
                });
              } catch (e) {
                try { navigator.clipboard = shim; } catch (e2) {}
              }

              // Вставка по событию paste (долгое нажатие → «Вставить», Ctrl+V,
              // контекстное меню VS Code): если WebView пришёл с пустым или
              // отсутствующим clipboardData — подкладываем системный буфер,
              // иначе xterm/monaco молча ничего не вставляют.
              document.addEventListener('paste', function (ev) {
                var has = '';
                try {
                  var d = ev.clipboardData;
                  if (d && d.getData) { has = d.getData('text/plain') || ''; }
                } catch (e) {}
                if (has) {
                  window.__oclPasteSeen = performance.now();
                  return; // данные есть — обработчики вставят сами
                }
                var text = read();
                if (!text) { return; }
                try {
                  var d2 = ev.clipboardData;
                  if (d2 && d2.setData) {
                    d2.setData('text/plain', text);
                    window.__oclPasteSeen = performance.now();
                    return;
                  }
                } catch (e) {}
                try {
                  Object.defineProperty(ev, 'clipboardData', { value: makeData(text) });
                  window.__oclPasteSeen = performance.now();
                } catch (e) {}
              }, true);

              // WebView/IME иногда вставляют как insertFromPaste минуя событие
              // paste — xterm такое молча игнорирует (вот почему в консоль могло
              // ничего не попадать). Перехватываем: переделываем в нашу вставку.
              document.addEventListener('beforeinput', function (ev) {
                if (ev.inputType !== 'insertFromPaste') { return; }
                var tgt = ev.target;
                var inTerm = !!(tgt && tgt.closest && tgt.closest('.xterm'));
                var seen = window.__oclPasteSeen || 0;
                if (performance.now() - seen < 400) {
                  // paste выше уже обработан: в терминале гасим только хвостовую
                  // нативную вставку в невидимую textarea (иначе двойная вставка),
                  // в обычных полях оставляем нативную вставку как есть.
                  if (inTerm) { try { ev.preventDefault(); } catch (e) {} }
                  return;
                }
                var text = '';
                try { text = ev.data || ''; } catch (e) {}
                if (!text) {
                  try {
                    var dt = ev.dataTransfer;
                    if (dt) { text = dt.getData('text/plain') || ''; }
                  } catch (e) {}
                }
                if (!text) { text = read(); }
                if (!text) { return; }
                try { ev.preventDefault(); } catch (e) {}
                window.__oclPaste(text, tgt);
              }, true);

              // Ctrl+V / Ctrl+Shift+V / Ctrl+Insert / Shift+Insert, когда Java
              // не перехватил клавишу (например, страница перехватила раньше).
              // ev.code, а не ev.key — физическая клавиша, работает и на
              // русской раскладке.
              window.addEventListener('keydown', function (ev) {
                try {
                  var code = ev.code || '';
                  var paste = false;
                  if (code === 'KeyV') {
                    paste = (ev.ctrlKey && !ev.altKey && !ev.metaKey);
                  } else if (code === 'Insert') {
                    paste = ((ev.ctrlKey || ev.shiftKey) && !ev.altKey && !ev.metaKey);
                  }
                  if (!paste) { return; }
                  var tgt = document.activeElement;
                  if (!tgt) { return; }
                  var text = read();
                  if (!text) { return; }
                  ev.preventDefault();
                  ev.stopImmediatePropagation();
                  if (!window.__oclPaste(text, tgt)) { return; }
                  // Запасной путь для обычных полей (input/textarea/contenteditable):
                  // синтетическое paste само по себе текст не вставляет.
                  try {
                    var tag = (tgt.tagName || '').toLowerCase();
                    var inTerm = !!(tgt.closest && tgt.closest('.xterm'));
                    if (!inTerm && (tag === 'input' || tag === 'textarea')
                        && typeof tgt.value === 'string') {
                      tgt.value = text;
                      tgt.dispatchEvent(new Event('input', { bubbles: true }));
                    } else if (!inTerm && tgt.isContentEditable) {
                      document.execCommand('insertText', false, text);
                    }
                  } catch (e2) {}
                } catch (e) {}
              }, true);
            })();
            """;

    private WebView webView;
    /**
     * Последний URL главного кадра.
     *
     * Нужен потому, что {@code isLocalPage()} вызывается из потока JavaBridge
     * (мост {@code navigator.clipboard}), а {@code webView.getUrl()} оттуда
     * бросает IllegalStateException («A WebView method was called on thread
     * other than the UI thread») — раньше это молча отключало чтение/запись
     * буфера через шим. Пишется только на UI-потоке, читается с любого.
     */
    private volatile String currentUrl;
    /** Оверлей-сплэш поверх WebView (пока грузится страница). */
    private View splashOverlay;
    /** Матовый «прощальный» оверлей (перезагрузка/выключение, см. showFadeOverlay). */
    private View fadeOverlay;
    /** Корневой контейнер (WebView + оверлеи) — сюда добавляется fadeOverlay. */
    private FrameLayout contentRoot;
    /** Системный буфер обмена (работает из фонового потока JS-интерфейса). */
    private ClipboardManager clipboard;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int attempts;
    private boolean mainFrameFailed;
    /** Сейчас на экране наша собственная заглушка, а не страница с сервера. */
    private boolean placeholderShown;
    /** RUN_COMMAND реально ушёл в Termux (ставится только после успешного startService). */
    private boolean termuxStarted;
    /** Первый проход по onResume уже сделан (ради него и показывается системный запрос разрешения). */
    private boolean startupAttempted;
    /** Уже показывали системный запрос разрешения RUN_COMMAND в этом сеансе. */
    private boolean runPermissionAsked;
    private Runnable pendingRetry;

    // --- 2.7: живучесть после авто-выключения + heartbeat для сторожа ---

    /** Последняя основная страница не загрузилась — сервер лежит (простой/выключен). */
    private volatile boolean serverDown;
    /** Когда последний раз поновые отправляли OpenCode-All (анти-спам). */
    private long lastServiceKickMs;
    /** Плотность экрана (кэш: в жесте она нужна на каждом движении пальца). */
    private float density;
    /** Пользователь касался экрана/клавиатуры после последнего heartbeat. */
    private volatile long lastUserInputMs;
    /** Момент последнего удавшегося heartbeat (для логов). */
    private volatile long lastPingSentMs;
    /** Фоновый поток heartbeat → сторожу OpenCode-Watch. */
    private Thread pingThread;
    private volatile boolean pingStop;
    /** Монитор, по которому heartbeat-поток просыпается при смене состояния. */
    private final Object pingWake = new Object();
    /**
     * Приложение на переднем плане (onStart…onStop).
     *
     * Сторожу уходит 'F'/'B': пока приложение просто в фоне, Android может
     * ЗАМОРОЗИТЬ процесс (cached app freezer), и heartbeat-поток встаёт.
     * Без этой метки сторож считал замороженное приложение убитым, гасил
     * code-server, а возврат в приложение показывал вечный «reconnect».
     */
    private volatile boolean appForeground = true;
    /** Какое состояние ('F'/'B') уже подтверждено сторожу (чтобы не спамить). */
    private volatile boolean lastReportedForeground = true;

    // --- восстановление сервера (проверка порта в фоне + перезапуск) ---

    /** Сейчас идёт проверка живости сервера (не запускаем вторую параллельно). */
    private volatile boolean probeInFlight;
    /** Идёт восстановление: сервер поднимают заново и ждут его. */
    private volatile boolean recovering;
    /** Когда последний раз отправляли OpenCode-All (грейс для фоновых проверок). */
    private volatile long lastStartRequestMs;
    /** Сетевая проверка порта вне главного потока (NetworkOnMainThread). */
    private final ExecutorService netExec = Executors.newSingleThreadExecutor();

    // --- Выход и перезапуск (двупальцевый свайп вверх → стеклянная шторка) ---

    /** Шторка сейчас на экране (чтобы не показать второй раз). */
    private Dialog exitSheet;
    /** Запущен ли уже «невидимый» сервис, ловящий onTaskRemoved. */
    private boolean exitServiceStarted;
    /** Зарегистрирован ли приёмник TASK_REMOVED. */
    private boolean taskReceiverRegistered;

    // Детекция двухпальцевого свайпа вверх по экрану.
    private float gestureStartY = -1f;
    private float gestureStartX = -1f;
    private int gesturePointerCount;
    /** Свайп уже сработал — ждём подъёма пальцев, чтобы не дёргать повторно. */
    private boolean gestureTriggered;

    /** Порог срабатывания: насколько надо провести вверх (в dp). */
    private static final int SWIPE_UP_THRESHOLD_DP = 120;
    /** Максимальное отклонение по горизонтали (в dp), чтобы считать жест вертикальным. */
    private static final int SWIPE_TOLERANCE_DP = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ставим ловушку падений ДО всего рискованного кода: любое исключение
        // попадёт в лог в Download/ (читается из Termux) вместо молчаливого закрытия.
        CrashLogger.install(this);
        CrashLogger.log(this, "onCreate: версия " + appVersion()
                + ", savedInstanceState=" + (savedInstanceState != null));

        try {
            hideSystemBars();
        } catch (Throwable t) {
            // Полноэкранный режим не вышел — не повод закрывать приложение.
            CrashLogger.log(this, "hideSystemBars failed: " + t);
        }

        try {
            initWebView(savedInstanceState);
        } catch (Throwable t) {
            // WebView не создался (нет провайдера, нехватка памяти и т.п.) —
            // показываем текст ошибки вместо вылета.
            showFatalError(t);
            return;
        }

        // Плотность нужна жесту свайпа и шторке — кэшируем один раз.
        density = getResources().getDisplayMetrics().density;

        // Heartbeat сторожу: по молчанию OpenCode-Watch понимает, что приложение
        // убито/свайпано, и выключает всё, включая Termux (задача 4 в 2.7).
        startWatchPing();

        // Фоновая проверка «сервер ещё жив?»: если code-server упал, пока мы
        // были в фоне (сторож/система), поднимаем его и обновляем страницу —
        // иначе WebView застревает в «Connection Lost / reconnect» навсегда.
        startHealthCheck();

        // Невидимый сервис + приёмник: вместе они дают «выключить всё», когда
        // приложение выкинули из реестра задач (см. ExitService.onTaskRemoved).
        try {
            registerTaskRemovedReceiver();
            startExitService();
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось поднять ExitService: " + t);
        }
    }

    private void initWebView(Bundle savedInstanceState) {
        webView = new WebView(this);
        // Новая версия APK → сбрасываем кеш WebView один раз: code-server отдаёт
        // свои assets с Cache-Control «на год», и без сброса WebView продолжал
        // бы подсовывать годовой кеш вместо обновлённых файлов (показательный
        // случай — пропатченный на диске xterm.js, см. fix «листание чата»).
        purgeStaleCacheAfterUpdate();

        // Контейнер: WebView + сплэш-оверлей поверх. Сплэш гасится в onPageFinished.
        FrameLayout root = new FrameLayout(this);
        contentRoot = root;
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupSplashOverlay(root);
        setContentView(root);

        // КЛАВИАТУРА (фикс бага «софтверная клавиатура не выезжает»):
        // активность идёт в immersive-полноэкранном режиме с
        // setDecorFitsSystemWindows(false) — в такой конфигурации Android НЕ
        // растягивает окно под всплывающую клавиатуру (IME просто перекрывает
        // контент, а поле ввода уезжает под неё/не появляется вовсе). Проявляется
        // именно на планшете: пока не подключишь физическую клавиатуру — печатать
        // нечем. Слушаем инсеты клавиатуры и вручную «поднимаем» нижний край
        // WebView на высоту IME, чтобы поле ввода доскролливалось над клавиатурой.
        setupImeResize(root);

        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        // Тёмная подложка под WebView и отключение нативных скроллбаров/оверскролла:
        // на экранах с дробным DPR (3200×2136 @ 440 dpi) дефолтный белый фон WebView
        // давал 2px белые полосы у правого и нижнего краёв экрана.
        root.setBackgroundColor(0xFF1C1C1E);
        webView.setBackgroundColor(0xFF1C1C1E);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CrashLogger.log(this, "WebView создан, загружаю " + START_URL);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Кнопки на наших страницах-заглушках (см. waitingHtml()/ERROR_HTML).
        webView.addJavascriptInterface(new Bridge(), "Launcher");

        // Некоторые WebView спрашивают разрешение на чтение буфера обмена —
        // без явного grant() страница получает NotAllowedError.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    CrashLogger.log(MainActivity.this, "PermissionRequest: "
                            + java.util.Arrays.toString(request.getResources()));
                    request.grant(request.getResources());
                } catch (Throwable t) {
                    CrashLogger.log(MainActivity.this, "grant failed: " + t);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Всё остаётся внутри нашего WebView (включая редиректы на /login).
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) {
                    return;
                }
                // Наши собственные вызовы loadUrl часто ОТМЕНЯЮТ уже идущую
                // загрузку — Chromium сообщает об этом как net::ERR_ABORTED.
                // Это не «сервер лежит», просто пропускаем (иначе счётчик
                // попыток накручивался и мы раньше времени уходили на ошибку).
                String desc = String.valueOf(error.getDescription());
                if (desc.contains("ERR_ABORTED")) {
                    return;
                }
                mainFrameFailed = true;
                serverDown = true;
                attempts++;
                CrashLogger.log(MainActivity.this, "onReceivedError #" + attempts + ": "
                        + error.getErrorCode() + " " + error.getDescription()
                        + " url=" + request.getUrl());

                if (attempts >= MAX_ATTEMPTS) {
                    // Время вышло: сервер так и не ответил — предлагаем ручной запуск.
                    showPlaceholder(ERROR_HTML);
                    // Не планируем повторов: кнопка «Повторить» сбросит счётчик.
                } else {
                    // 2.7: НЕ перезагружаем документ заглушки на каждой попытке —
                    // раньше это были лишние полные загрузки раз в 2.5 с
                    // (мигание и лаги при старте). Счётчик тупо обновляем вживую.
                    refreshWaitingPlaceholder(attempts);
                    scheduleRetry();
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mainFrameFailed = false;
                currentUrl = url;
                CrashLogger.log(MainActivity.this, "onPageStarted " + url);
                hideSystemBars();
                injectClipboardShim(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                if (!mainFrameFailed && !placeholderShown) {
                    attempts = 0; // страница открылась — сбрасываем счётчик попыток
                    serverDown = false; // сервер отвечает — живём обычной жизнью
                } else {
                    // Заглушка или неудачная загрузка: сервер лежит.
                    serverDown = true;
                }
                CrashLogger.log(MainActivity.this, "onPageFinished " + url
                        + " failed=" + mainFrameFailed);
                hideSystemBars();
                injectClipboardShim(view);
                // Стёкла, градиенты, единый радиус и хвост за курсором — только
                // когда страница нарисована: в onPageStarted нужного DOM ещё может
                // не быть, а вёрстка воркбенча пересоздаётся при каждой загрузке.
                injectShellStyles(view);
                if (!mainFrameFailed) {
                    // Реальная страница (или заглушка) отрисована — убираем сплэш
                    // и матовый оверлей перезагрузки (2.7).
                    dismissSplash();
                    hideFadeOverlay();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            scheduleInitialLoad();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        appForeground = true;
        wakePing();
    }

    @Override
    protected void onStop() {
        appForeground = false;
        wakePing();
        // Сторож должен узнать о фоне НЕМЕДЛЕННО: Android вскоре может
        // заморозить процесс (cached app freezer), heartbeat-поток встанет, и
        // без явного 'B' сторож счёл бы живое приложение убитым и погасил бы
        // code-server — возврат в приложение давал вечный «reconnect».
        // Свой короткий поток: сокет с главного потока нельзя.
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (sendWatchSignal("B")) {
                        lastReportedForeground = false;
                    }
                }
            }, "ocl-bg-signal");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CrashLogger.log(this, "onStop: не удалось отправить 'B': " + t);
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CrashLogger.log(this, "onResume");
        hideSystemBars();
        // startService() из возобновлённой (foreground) Activity — разрешён системой.
        // Всё тело обёрнуто: раньше любое исключение здесь (например, отказ системы
        // на startService для Termux) убивало приложение сразу после старта.
        if (termuxStarted) {
            // Команда уже уходила. Сервер мог умереть, пока приложение было в
            // фоне (сторож, phantom-killer). Проверяем порт в фоне: жив —
            // ничего не делаем (VS Code переподключится сам), мёртв — поднимаем
            // заново и перезагружаем страницу, иначе «reconnect» вечный.
            if (SystemClock.uptimeMillis() - lastStartRequestMs > START_GRACE_MS) {
                checkServerAlive("возврат в приложение");
            }
            return;
        }
        boolean granted = checkSelfPermission(TERMUX_RUN_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
        CrashLogger.log(this, "onResume: разрешение RUN_COMMAND выдано=" + granted
                + ", первая попытка=" + !startupAttempted);
        if (startupAttempted && !granted) {
            // Пользователь отказал в разрешении — не дёргаем систему заново
            // при каждом возврате в приложение (диалог уже показан, см. showRunPermissionDialog).
            return;
        }
        startupAttempted = true;
        try {
            startTermuxServices();
        } catch (Throwable t) {
            CrashLogger.log(this, "startTermuxServices failed: " + t);
            showTermuxErrorDialog("Не удалось запустить Termux", String.valueOf(t));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemBars();
    }

    /** Кнопка «Назад» — назад по истории, как в браузере. */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Перехват Ctrl+V / Ctrl+Shift+V на уровне Android, ДО того как клавишу
     * увидят WebView и VS Code.
     *
     * Внутри code-server вставка идёт через navigator.clipboard.readText(),
     * который WebView блокирует, поэтому в терминале Ctrl+V молча ничего не
     * делал: VS Code перехватывал клавишу своим обработчиком и гасил её раньше
     * нашего JS-шима. Здесь то же самое, но в Java: читаем буфер напрямую из
     * ClipboardManager и рассылаем синтетический paste в активный элемент.
     * Клавиша потребляется (возвращаем true), чтобы избежать двойной вставки.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        try {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                lastUserInputMs = SystemClock.uptimeMillis();
                kickServicesIfDown("нажатие клавиши");
            }
            if (isPasteShortcut(event)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    injectSystemPaste();
                }
                return true; // гасим и DOWN, и UP — дальше WebView её не видит
            }
        } catch (Throwable t) {
            CrashLogger.log(this, "dispatchKeyEvent: " + t);
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Ctrl+V / Ctrl+Shift+V / Ctrl+Insert / Shift+Insert — все штатные
     * сочетания вставки в VS Code/терминале (последние два есть не у всех
     * экраноклавиш, но на внешних клавиатурах встречаются).
     */
    private static boolean isPasteShortcut(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_V && code != KeyEvent.KEYCODE_INSERT) {
            return false;
        }
        int meta = event.getMetaState();
        boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0
                || (meta & KeyEvent.META_CTRL_LEFT_ON) != 0
                || (meta & KeyEvent.META_CTRL_RIGHT_ON) != 0;
        if (code == KeyEvent.KEYCODE_V) {
            return ctrl;
        }
        boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0
                || (meta & KeyEvent.META_SHIFT_LEFT_ON) != 0
                || (meta & KeyEvent.META_SHIFT_RIGHT_ON) != 0;
        return ctrl || shift;
    }

    /**
     * Детекция двухпальцевого свайпа ВВЕРХ → показ стеклянной шторки выхода.
     *
     * Жест ловится на уровне Activity ДО того, как его разберёт WebView, но
     * события при этом пропускаются наружу (super), чтобы обычный скролл
     * терминала и редактора продолжал работать. Срабатывает только при
     * осознанном вертикальном движении двумя пальцами вверх.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // 2.7: «пользователь жив» — это и есть сигнал активности для
                // сторожа (heartbeat 'A') и повод оживить упавший сервер.
                lastUserInputMs = SystemClock.uptimeMillis();
                kickServicesIfDown("касание экрана");
            }
            trackSwipeUpGesture(ev);
        } catch (Throwable t) {
            CrashLogger.log(this, "dispatchTouchEvent: " + t);
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Состоятельная машина детекции свайпа вверх двумя пальцами. */
    private void trackSwipeUpGesture(MotionEvent ev) {
        int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            // Новое касание — сбрасываем жест.
            gestureStartY = ev.getY();
            gestureStartX = ev.getX();
            gesturePointerCount = 1;
            gestureTriggered = false;
            return;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            gesturePointerCount++;
            // Второй (и последующий) палец фиксируем как старт свайпа.
            if (gesturePointerCount == 2) {
                gestureStartY = ev.getY();
                gestureStartX = ev.getX();
                gestureTriggered = false;
            }
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (gesturePointerCount < 2 || gestureTriggered
                    || gestureStartY < 0) {
                return; // нужен ровно второй палец и ещё не срабатывали
            }
            float dy = gestureStartY - ev.getY(); // вверх = положительное
            float dx = Math.abs(ev.getX() - gestureStartX);
            float threshold = SWIPE_UP_THRESHOLD_DP * density;
            float tolerance = SWIPE_TOLERANCE_DP * density;

            if (dy >= threshold && dx <= tolerance) {
                gestureTriggered = true; // больше не срабатываем до подъёма пальцев
                CrashLogger.log(this, "Двупальцевый свайп вверх — показываю шторку");
                showExitSheet();
            }
            return;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // Пальцы подняты — можно снова детектировать новый жест.
            gesturePointerCount = 0;
            gestureStartY = -1f;
            gestureTriggered = false;
        }
    }

    /** Читает системный буфер и вставляет его текст в фокусированный элемент WebView. */
    private void injectSystemPaste() {
        if (webView == null) {
            return;
        }
        String text = readClipboardText();
        if (text == null || text.isEmpty()) {
            return; // в буфере не текст — нечего вставлять
        }
        // JSONObject.quote корректно экранирует текст для подстановки в JS.
        String quoted = org.json.JSONObject.quote(text);
        String js =
                "(function(t){"
                + "var el=document.activeElement||document.body;"
                + "if(!el){return;}"
                // 1) Основной путь — наш шим CLIPBOARD_JS: обходит зависимость от
                //    DataTransfer/ClipboardEvent (на части WebView конструкторы
                //    недоступны, и вставка молча не срабатывала).
                + "var done=false;"
                + "try{ if(window.__oclPaste){done=window.__oclPaste(t,el);} }catch(e){}"
                // 2) Запасной путь — классический синтетический ClipboardEvent.
                + "if(!done){try{"
                + "  var dt=new DataTransfer();dt.setData('text/plain',t);"
                + "  var ev=new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true});"
                + "  el.dispatchEvent(ev);"
                + "}catch(e){}}"
                // 3) Обычные поля ввода сами по событию paste не вставляют
                //    (синтетическое событие не «доверенное») — вставляем вручную.
                //    Терминал/xterm и monaco вставляют сами из clipboardData.
                + "try{"
                + "  var tag=(el.tagName||'').toLowerCase();"
                + "  var inTerm=!!(el.closest&&el.closest('.xterm'));"
                + "  if(!inTerm&&(tag==='input'||tag==='textarea')){"
                + "    var s=el.selectionStart, e=el.selectionEnd;"
                + "    if(typeof s!=='number'){s=el.value.length;e=s;}"
                + "    el.value=el.value.slice(0,s)+t+el.value.slice(e);"
                + "    el.selectionStart=el.selectionEnd=s+t.length;"
                + "    el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "  }else if(!inTerm&&el.isContentEditable){"
                + "    document.execCommand('insertText',false,t);"
                + "  }"
                + "}catch(e2){}"
                + "})(" + quoted + ");";
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            CrashLogger.log(this, "вставка не удалась: " + t);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        CrashLogger.log(this, "onDestroy finishing=" + isFinishing());
        handler.removeCallbacksAndMessages(null);
        // 2.7: Activity уходит — heartbeat сторожу прекращаем. Пинги продолжит
        // (если процесс жив) только новый onCreate; если Activity кончилась
        // насмерть, пинги умолкнут → сторож через APP_TIMEOUT погасит всё.
        pingStop = true;
        if (pingThread != null) {
            pingThread.interrupt();
            pingThread = null;
        }
        wakePing();
        try {
            netExec.shutdownNow();
        } catch (Throwable ignored) {
            // Пул проверок уже мог быть остановлен.
        }
        if (taskReceiverRegistered) {
            try {
                unregisterReceiver(taskRemovedReceiver);
            } catch (Throwable ignored) {
                // Приёмник мог быть не зарегистрирован системой.
            }
            taskReceiverRegistered = false;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- internal

    /**
     * Строит сплэш-оверлей поверх WebView.
     *
     * Пока code-server поднимается и грузится страница, под лежит светлый фон
     * WebView (#F2F2F7). Показываем сплэш в гамме оболочки: холодный серый Apple + синий
     * знак VS Code Apple-градиента. Плавное проявление (fade + лёгкий scale), а
     * когда страница готова — плавное сокрытие. Само окно того же светлого
     * оттенка (@style/AppTheme), поэтому вспышек дефолтного белого нет с первого кадра.
     */
    private void setupSplashOverlay(FrameLayout root) {
        try {
            splashOverlay = getLayoutInflater().inflate(R.layout.splash, root, false);
            root.addView(splashOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View content = splashOverlay.findViewById(R.id.splash_content);
            if (content != null) {
                Animation anim = AnimationUtils.loadAnimation(this, R.anim.splash_fade_in);
                content.startAnimation(anim);
            }
            CrashLogger.log(this, "сплэш показан");
        } catch (Throwable t) {
            // Сплэш — косметика. Не показался — не беда, работаем без него.
            CrashLogger.log(this, "сплэш не построился: " + t);
            splashOverlay = null;
        }
    }

    /** Плавно убирает сплэш (вызывается, когда WebView загрузил страницу). */
    private void dismissSplash() {
        final View overlay = splashOverlay;
        if (overlay == null) {
            return;
        }
        splashOverlay = null; // сбрасываем сразу, чтобы не дёргать повторно
        try {
            AlphaAnimation fade = new AlphaAnimation(1f, 0f);
            fade.setDuration(320);
            fade.setFillAfter(true);
            fade.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation a) { }

                @Override
                public void onAnimationRepeat(Animation a) { }

                @Override
                public void onAnimationEnd(Animation a) {
                    overlay.setVisibility(View.GONE);
                }
            });
            overlay.startAnimation(fade);
            CrashLogger.log(this, "сплэш скрыт");
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось скрыть сплэш: " + t);
            overlay.setVisibility(View.GONE);
        }
    }

    /**
     * Матовый оверлей поверх всего контента для перезагрузки/выключения (2.7).
     *
     * Светлый холодный «матовый» фон #F2F2F7 (88% — слегка прозрачный),
     * Apple-надпись по центру (fade + мягкий overshoot-подъём) и мелкий
     * @4rlein снизу. Гаснет так же плавно — {@link #hideFadeOverlay()}.
     *
     * @param title    крупная надпись (например «Goodbye» / «Restarting…»)
     * @param subtitle мелкий серый поясняющий текст под ней (может быть null)
     */
    private void showFadeOverlay(String title, String subtitle) {
        if (contentRoot == null || fadeOverlay != null) {
            return;
        }
        try {
            float d = getResources().getDisplayMetrics().density;

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xE0F2F2F7); // 88% матового холодного серого

            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(0xFF1D1D1F);           // Apple Ink
            t.setTextSize(26);
            t.setGravity(Gravity.CENTER);
            t.setLetterSpacing(0.04f);
            box.addView(t);

            if (subtitle != null && !subtitle.isEmpty()) {
                TextView s = new TextView(this);
                s.setText(subtitle);
                s.setTextColor(0xFF8E8E93);       // серый подписи
                s.setTextSize(13);
                s.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.topMargin = (int) (8 * d);
                box.addView(s, slp);
            }

            TextView credit = new TextView(this);
            credit.setText("@4rlein");
            credit.setTextColor(0xFF8E8E93);
            credit.setTextSize(11);
            credit.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = (int) (24 * d);
            box.addView(credit, clp);

            contentRoot.addView(box, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fadeOverlay = box;

            // Фон: быстрый fade-in; заголовок: мягкий overshoot-подъём (Apple-стиль).
            AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
            fadeIn.setDuration(280);
            fadeIn.setInterpolator(new LinearInterpolator());
            box.startAnimation(fadeIn);

            ScaleAnimation pop = new ScaleAnimation(0.94f, 1f, 0.94f, 1f,
                    ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                    ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
            pop.setDuration(420);
            pop.setInterpolator(new OvershootInterpolator(1.15f));
            t.startAnimation(pop);

            CrashLogger.log(this, "fade-оверлей показан: " + title);
        } catch (Throwable t) {
            // Косметика — не показался, выходим без анимации.
            CrashLogger.log(this, "fade-оверлей не построился: " + t);
            fadeOverlay = null;
        }
    }

    /** Плавно гасит {@link #showFadeOverlay} (fade-out с ускорением) и убирает его. */
    private void hideFadeOverlay() {
        final View overlay = fadeOverlay;
        if (overlay == null) {
            return;
        }
        fadeOverlay = null; // сбрасываем сразу, чтобы не дёргать повторно
        try {
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(280);
            fadeOut.setInterpolator(new AccelerateInterpolator());
            fadeOut.setFillAfter(true);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation a) { }

                @Override
                public void onAnimationRepeat(Animation a) { }

                @Override
                public void onAnimationEnd(Animation a) {
                    contentRoot.removeView(overlay);
                }
            });
            overlay.startAnimation(fadeOut);
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось погасить fade-оверлей: " + t);
            try {
                contentRoot.removeView(overlay);
            } catch (Throwable ignored) {
                // Корень уже умер вместе с activity — оверлей уберётся сам.
            }
        }
    }

    /**
     * Показывает текст ошибки обычным TextView вместо мгновенного закрытия.
     * Так пользователь может прочитать причину прямо на экране.
     */
    private void showFatalError(Throwable t) {
        android.util.Log.e("OpenCodeLauncher", "Критическая ошибка при запуске", t);
        dismissSplash(); // иначе сплэш перекроет текст ошибки
        try {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText("Ошибка запуска:\n\n" + android.util.Log.getStackTraceString(t));
            tv.setTextColor(0xFF1D1D1F);          // Apple Ink (не чистый чёрный)
            tv.setBackgroundColor(0xFFF2F2F7);    // светлая матовая оболочка (не белая)
            tv.setTextSize(13);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextIsSelectable(true);
            setContentView(tv);
        } catch (Throwable ignored) {
            // Даже TextView не построился — тогда уже нечего делать.
        }
    }

    /** Показывает свою страницу-заглушку (без записи новой реальной неудачи в счётчик). */
    private void showPlaceholder(String html) {
        placeholderShown = true;
        if (webView != null) {
            webView.loadDataWithBaseURL(START_URL, html, "text/html", "utf-8", null);
        }
    }

    /**
     * Заглушка ожидания с номером текущей попытки.
     *
     * 2.7: цифра обёрнута в {@code #oclAttempt}, а флаг
     * {@code window.__oclPlaceholder} говорит Java, что документ заглушки ещё
     * жив (WebView не выкидывает показанную страницу при ошибке соединения) —
     * тогда счётчик просто переписывается скриптом, без новой полной загрузки
     * документа на каждую попытку (было 2 загрузки раз в 2.5 с — мерцание).
     */
    private static String waitingHtml(int attempt) {
        return PAGE_OPEN
                + "<b>VS Code</b><br>Запускаю сервер… "
                + "<span id=\"oclAttempt\">" + attempt + "</span>/" + MAX_ATTEMPTS + "<br>"
                + "<span class=\"url\">" + START_URL + "</span><br>"
                + "<span class=\"hint\">code-server поднимается в Termux</span>"
                + "<script>window.__oclPlaceholder=true;</script>"
                + PAGE_CLOSE;
    }

    /** Сервер не ответил за отведённое время. */
    private static final String ERROR_HTML = PAGE_OPEN
            + "<b>Сервер не запустился</b><br>"
            + "<span class=\"hint\">code-server не ответил на " + START_URL + " за минуту.<br>"
            + "Проверьте Termux: установлен code-server и существует<br>"
            + "~/.shortcuts/OpenCode-All</span><br><br>"
            + "<button onclick=\"Launcher.retry()\">Повторить</button>"
            + "<button onclick=\"Launcher.openTermux()\">Открыть Termux</button>"
            + PAGE_CLOSE;

    private void scheduleInitialLoad() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadServer();
            }
        }, INITIAL_DELAY_MS);
    }

    private void scheduleRetry() {
        if (pendingRetry != null) {
            handler.removeCallbacks(pendingRetry);
        }
        pendingRetry = new Runnable() {
            @Override
            public void run() {
                pendingRetry = null; // отработал — иначе флаг «есть отложенный» залипал
                loadServer();
            }
        };
        handler.postDelayed(pendingRetry, RETRY_DELAY_MS);
    }

    /** Переход на реальный адрес: сбрасываем признак заглушки перед загрузкой. */
    private void loadServer() {
        if (webView != null) {
            placeholderShown = false;
            webView.loadUrl(START_URL);
        }
    }

    /**
     * Полная перезагрузка реального адреса с обнулением счётчика.
     *
     * Вызывается, когда сервер заведомо поднят (после восстановления): так
     * снимается и наша заглушка, и «Connection Lost / reconnect»-оверлей
     * воркбенча VS Code, который сам по себе висит бесконечно, пока сервер
     * был мёртв.
     */
    private void reloadServer() {
        attempts = 0;
        if (pendingRetry != null) {
            handler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
        loadServer();
    }

    /**
     * Одноразовый сброс кеша WebView после установки новой версии APK.
     *
     * <p>code-server отдаёт статические файлы (включая xterm.js) с
     * {@code Cache-Control: public, max-age=31536000} — на целый год. Без сброса
     * WebView продолжал бы год отдавать старый кеш независимо от того, что
     * на диске уже лежит новая версия файла (это и есть ловушка 2.7.1).
     *
     * <p>Идемпотентен: флаг {@code cacheClearedForVer} в SharedPreferences
     * запоминает versionCode, для которого сброс уже был. Restart дополнительно
     * сбрасывает кеш каждый раз (см. {@link #restartEverything()}).
     */
    private void purgeStaleCacheAfterUpdate() {
        try {
            long ver = -1;
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Throwable ignored) {
            }
            android.content.SharedPreferences sp =
                    getSharedPreferences("ocl.prefs", MODE_PRIVATE);
            if (sp.getLong("cacheClearedForVer", -1) == ver) {
                return; // для этой версии сброс уже был — ничего не делаем
            }
            webView.clearCache(true);
            sp.edit().putLong("cacheClearedForVer", ver).apply();
            CrashLogger.log(this, "кеш WebView сброшен после обновления APK (v" + ver + ")");
        } catch (Throwable t) {
            CrashLogger.log(this, "purgeStaleCacheAfterUpdate: " + t);
        }
    }

    /**
     * Внедряет оболочные шимы: геометрия (GLASS_CSS), прижатие меню
     * (EDGE_CLAMP_JS) и след за кареткой (CURSOR_TRAIL_JS). Все идемпотентны —
     * флаги {@code #oclglass} / {@code window.__oclClamp} / {@code window.__oclTrail}.
     */
    private void injectShellStyles(WebView view) {
        try {
            view.evaluateJavascript(GLASS_CSS, null);
            view.evaluateJavascript(EDGE_CLAMP_JS, null);
            view.evaluateJavascript(CURSOR_TRAIL_JS, null);
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось внедрить оболочные стили: " + t);
        }
    }

    /** Ставит наш clipboard-шим в текущий документ (идемпотентно, см. CLIPBOARD_JS). */
    private void injectClipboardShim(WebView view) {
        try {
            view.evaluateJavascript(CLIPBOARD_JS, null);
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось внедрить clipboard-шим: " + t);
        }
    }

    /**
     * Разрешаем доступ к буферу только страницам с нашего локального сервера,
     * чтобы чужой сайт, открытый в этом же WebView, не прочитал буфер обмена.
     *
     * ⚠️ Вызывается из потока JavaBridge (JS-шим navigator.clipboard), где
     * нельзя трогать WebView: раньше здесь стоял {@code webView.getUrl()},
     * который с фонового потока бросает IllegalStateException, catch возвращал
     * false — и чтение буфера через шим молча отдавало «» (VS Code «вставлял»
     * пустоту, в том числе в консоли). Работаем только по кэшу currentUrl.
     */
    private boolean isLocalPage() {
        try {
            String url = currentUrl;
            if (url == null) {
                // Запасной путь — только с UI-потока (webView.getUrl() там безопасен).
                if (Thread.currentThread() == Looper.getMainLooper().getThread()
                        && webView != null) {
                    url = webView.getUrl();
                }
            }
            if (url == null) {
                return true; // документ ещё грузится — это наша страница
            }
            String host = android.net.Uri.parse(url).getHost();
            return host == null
                    || "127.0.0.1".equals(host)
                    || "localhost".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Читает текст из системного буфера. Вызывается из фонового потока WebView. */
    private String readClipboardText() {
        if (!isLocalPage() || clipboard == null) {
            return "";
        }
        try {
            CharSequence text = clipboard.getText();
            if (text != null) {
                return text.toString();
            }
            // В буфере не текст (картинка/ссылка) — отдаём первую строку описания.
            ClipData data = clipboard.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence coerced = data.getItemAt(0).coerceToText(this);
                return coerced != null ? coerced.toString() : "";
            }
        } catch (Throwable t) {
            CrashLogger.log(this, "чтение буфера не удалось: " + t);
        }
        return "";
    }

    /** Пишет текст в системный буфер. Вызывается из фонового потока WebView. */
    private void writeClipboardText(String text) {
        if (!isLocalPage() || clipboard == null) {
            return;
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenCode", text == null ? "" : text));
        } catch (Throwable t) {
            CrashLogger.log(this, "запись в буфер не удалась: " + t);
        }
    }

    /**
     * Просит Termux выполнить наш скрипт в фоне.
     * Требует allow-external-apps=true в ~/.termux/termux.properties
     * и разрешения {@link #TERMUX_RUN_PERMISSION}.
     * Если Termux недоступен — приложение всё равно откроет URL
     * (сервер может быть уже запущен).
     */
    private void startTermuxServices() {
        // Termux не установлен вовсе — сразу говорим об этом пользователю.
        if (!isPackageInstalled(TERMUX_PKG)) {
            showTermuxErrorDialog(
                    "Termux не установлен",
                    "Чтобы запустить сервер, установите приложение Termux из F-Droid.");
            return;
        }

        // RunCommandService защищён разрешением — без него система бросит
        // SecurityException ещё до старта сервиса. Сначала убеждаемся,
        // что оно выдано, и при необходимости запрашиваем у пользователя.
        if (checkSelfPermission(TERMUX_RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            CrashLogger.log(this, "разрешение " + TERMUX_RUN_PERMISSION + " не выдано");
            ensureRunPermission();
            return;
        }
        sendRunCommand();
    }

    /** Запрашивает разрешение RUN_COMMAND (один раз за сеанс) или объясняет отказ. */
    private void ensureRunPermission() {
        if (!runPermissionAsked) {
            runPermissionAsked = true;
            CrashLogger.log(this, "запрашиваю разрешение " + TERMUX_RUN_PERMISSION);
            try {
                requestPermissions(new String[]{TERMUX_RUN_PERMISSION}, REQ_RUN_PERMISSION);
                return;
            } catch (Throwable t) {
                CrashLogger.log(this, "requestPermissions не удалось: " + t);
            }
        }
        showRunPermissionDialog();
    }

    /** Результат системного запроса разрешения RUN_COMMAND. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RUN_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        CrashLogger.log(this, "разрешение RUN_COMMAND выдано=" + granted);
        if (granted) {
            startTermuxServices();
        } else {
            showRunPermissionDialog();
        }
    }

    /**
     * Объясняем, почему не стартует сервер, и даём «Повторить»
     * (повторный запрос) либо ручной запуск через Termux.
     */
    private void showRunPermissionDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Termux не получил разрешение")
                    .setMessage("Чтобы поднять code-server, приложению нужно разрешение "
                            + "«RUN_COMMAND» от Termux.\n\n"
                            + "Нажмите «Повторить» и разрешите доступ, либо откройте Termux "
                            + "и выполните вручную:\n"
                            + "bash ~/.shortcuts/OpenCode-All")
                    .setCancelable(false)
                    .setPositiveButton("Повторить", (d, w) -> {
                        runPermissionAsked = false;
                        ensureRunPermission();
                    })
                    .setNeutralButton("Открыть Termux", (d, w) -> openTermux())
                    .setNegativeButton("Закрыть", (d, w) -> d.dismiss())
                    .show();
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось показать диалог разрешения: " + t);
        }
    }

    /** Сама отправка RUN_COMMAND в RunCommandService. */
    private void sendRunCommand() {
        Intent intent = new Intent(TERMUX_RUN_ACTION);
        intent.setClassName(TERMUX_PKG, TERMUX_RUN_SERVICE);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                "-c",
                "termux-wake-lock >/dev/null 2>&1; exec bash " + HOME_DIR + "/.shortcuts/OpenCode-All"
        });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME_DIR);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

        try {
            startService(intent);
            termuxStarted = true; // команду приняли — повторные отправки не нужны
            lastStartRequestMs = SystemClock.uptimeMillis();
            CrashLogger.log(this, "RUN_COMMAND отправлен в " + TERMUX_RUN_SERVICE);
        } catch (IllegalStateException e) {
            // Мы ещё не в foreground — пробуем через foreground-сервис.
            try {
                startForegroundService(intent);
                termuxStarted = true;
                lastStartRequestMs = SystemClock.uptimeMillis();
                CrashLogger.log(this, "RUN_COMMAND отправлен (foreground)");
            } catch (Exception e2) {
                CrashLogger.log(this, "foreground-запуск отклонён: " + e2);
                showTermuxErrorDialog(
                        "Не удалось запустить Termux",
                        "Система запретила запуск сервиса Termux: " + e2);
            }
        } catch (SecurityException e) {
            // Система запретила доступ к сервису.
            CrashLogger.log(this, "RUN_COMMAND запрещён: " + e);
            showTermuxErrorDialog(
                    "Не удалось запустить Termux",
                    "Система запретила доступ к сервису Termux:\n" + e
                    + "\n\nЧаще всего это значит, что сервис TermuxService не exported — "
                    + "либо Termux старее 0.118 (нужен RunCommandService), "
                    + "либо запущена СТАРАЯ сборка этой обёртки и она целится в TermuxService.\n\n"
                    + "1) Обновите Termux (F-Droid, ≥ 0.118);\n"
                    + "2) Переустановите свежий APK этой обёртки;\n"
                    + "3) Выдайте разрешение RUN_COMMAND в системном запросе.");
        } catch (Exception e) {
            CrashLogger.log(this, "RUN_COMMAND отклонён: " + e);
            showTermuxErrorDialog(
                    "Не удалось запустить Termux",
                    "Команда не была отправлена: " + e
                    + "\n\nПроверьте, что Termux установлен и запущен,\n"
                    + "а в ~/.termux/termux.properties задано allow-external-apps=true\n"
                    + "(затем выполните termux-reload-settings).");
        }
    }

    // --- 2.7: heartbeat сторожу и «оживление» упавшего сервера -----------------

    /**
     * Фоновый heartbeat → {@code ~/.shortcuts/OpenCode-Watch} (порт {@link #WATCH_PORT}).
     *
     * <p>Каждые {@link #PING_INTERVAL_MS} шлём сторожу один байт:
     * {@code 'A'} — был ввод пользователя (для него это «активность»),
     * {@code 'P'} — просто жив. Если приложение убили/свайпнули/выкинули из
     * реестра — поток вместе с процессом исчезает, пинги прекращаются, и
     * сторож через {@code APP_TIMEOUT} (90 с) гасит сервисы, а после тишины
     * убивает и Termux. Это страховка там, где {@code onTaskRemoved} на
     * HyperOS не срабатывает.
     *
     * <p>Пинги идут из фонового потока (сокет на главном — NetworkOnMainThread),
     * все ошибки глотаем: если сторожа ещё нет (сервисы не поднимались) — это
     * норма, просто повторим через интервал.
     */
    /** Подпись значения ползунка «Auto‑off» в стиле Apple: «30 min» / «4 h». */
    private static String autoOffLabel(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h";
    }

    /** Текущий порог авто-выключения по простою, минуты (ocl.prefs). */
    private int autoOffMinutes() {
        return getSharedPreferences("ocl.prefs", MODE_PRIVATE)
                .getInt(AUTO_OFF_PREF, AUTO_OFF_OPTIONS[AUTO_OFF_DEFAULT_INDEX]);
    }

    /** Сохранить порог авто-выключения (минуты); сторожу дойдёт с heartbeat'ом. */
    private void setAutoOffMinutes(int minutes) {
        try {
            getSharedPreferences("ocl.prefs", MODE_PRIVATE)
                    .edit().putInt(AUTO_OFF_PREF, minutes).apply();
            CrashLogger.log(this, "порог авто-выключения = " + minutes + " мин");
        } catch (Throwable t) {
            CrashLogger.log(this, "setAutoOffMinutes: " + t);
        }
    }

    private void startWatchPing() {
        if (pingThread != null) {
            return; // уже крутится
        }
        pingStop = false;
        lastReportedForeground = !appForeground; // первый же тик отправит текущее состояние
        pingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int lastIdlePushedSec = -1;
                long lastIdlePushMs = 0;
                while (!pingStop) {
                    boolean fg = appForeground;
                    boolean stateChanged = fg != lastReportedForeground;
                    boolean active = SystemClock.uptimeMillis() - lastUserInputMs
                            < PING_INTERVAL_MS;
                    // Порог простоя пересылаем сторожу сразу после смены
                    // (и раз в ~2 мин — на случай перезапуска сторожа);
                    // команда 'R<секунды>' считается тем же heartbeat'ом.
                    int wantSec = autoOffMinutes() * 60;
                    boolean pushIdle = wantSec != lastIdlePushedSec
                            || SystemClock.uptimeMillis() - lastIdlePushMs
                                    > IDLE_PUSH_INTERVAL_MS;
                    // 'F'/'B' — приложение на экране/в фоне (чтобы сторож не
                    // считал замороженный фон убитым приложением).
                    String cmd = pushIdle
                            ? ("R" + wantSec + "\n")
                            : (stateChanged ? (fg ? "F" : "B") : (active ? "A" : "P"));

                    boolean ok = sendWatchSignal(cmd);
                    if (ok && pushIdle) {
                        lastIdlePushedSec = wantSec;
                        lastIdlePushMs = SystemClock.uptimeMillis();
                    } else if (ok && stateChanged) {
                        lastReportedForeground = fg;
                    }

                    // Со сменой состояния/порога ждём недолго (чтобы 'F' дошёл
                    // сразу после возврата в приложение), иначе — обычный интервал.
                    long waitMs = (stateChanged || pushIdle) ? 1000 : PING_INTERVAL_MS;
                    synchronized (pingWake) {
                        if (!pingStop) {
                            try {
                                pingWake.wait(waitMs);
                            } catch (InterruptedException e) {
                                return; // onDestroy попросил выйти
                            }
                        }
                    }
                }
            }
        }, "ocl-watch-ping");
        pingThread.setDaemon(true);
        pingThread.start();
        CrashLogger.log(this, "heartbeat сторожа запущен (порт " + WATCH_PORT
                + ", интервал " + PING_INTERVAL_MS + " мс)");
    }

    /** Будит heartbeat-поток (смена состояния/желание отправить внеочередной сигнал). */
    private void wakePing() {
        synchronized (pingWake) {
            pingWake.notifyAll();
        }
    }

    /**
     * Один heartbeat-байт сторожу ({@code R<sec>} / {@code F} / {@code B} /
     * {@code A} / {@code P}), с подтверждением 'O'.
     *
     * Блокируется (сокет) — вызывать только из фонового потока. Ошибки гасим:
     * если сторожа нет/порт занят, это норма, повторим на следующем тике.
     */
    private boolean sendWatchSignal(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", WATCH_PORT), 1500);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            out.write(cmd.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            try {
                s.getInputStream().read(new byte[8]); // подтверждение 'O' от сторожа
            } catch (Throwable ignored) {
                // Ответ не обязателен — главное, что соединение прошло.
            }
            lastPingSentMs = SystemClock.uptimeMillis();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Проверяет, слушает ли code-server порт {@link #SERVER_PORT}.
     *
     * Простое TCP-подключение к localhost: код-сервер жив уже тогда, когда
     * порт открыт (до полного прогрева HTTP). Блокируется — только из фона.
     */
    private boolean isServerPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", SERVER_PORT), 900);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Разовая проверка живости сервера в фоне (не чаще одной параллельно).
     *
     * Зачем: когда приложение в фоне, Android может заморозить процесс — мы не
     * видим, что сторож погасил code-server. На возврате (или по фоновому
     * таймеру) проверяем порт: жив — ничего, мёртв — {@link #startRecovery}.
     */
    private void checkServerAlive(final String reason) {
        if (probeInFlight || recovering) {
            return;
        }
        probeInFlight = true;
        try {
            netExec.execute(new Runnable() {
                @Override
                public void run() {
                    final boolean up = isServerPortOpen();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            probeInFlight = false;
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (up) {
                                if (serverDown) {
                                    // Сервер вернулся сам — снимаем нашу заглушку
                                    // и «reconnect»-оверлей воркбенча.
                                    serverDown = false;
                                    CrashLogger.log(MainActivity.this,
                                            "сервер отвечает — перезагружаю страницу");
                                    reloadServer();
                                }
                            } else {
                                startRecovery(reason);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            probeInFlight = false;
            CrashLogger.log(this, "checkServerAlive: " + t);
        }
    }

    /**
     * Сервер упал — поднимаем его заново и ждём, затем перезагружаем страницу.
     *
     * Пока идёт восстановление, поверх экрана висит матовый оверлей
     * «Restarting…»; пробуем порт каждые 1.5 с на протяжении ~2 минут, после
     * чего показываем честную страницу ошибки с кнопкой «Повторить».
     */
    private void startRecovery(final String reason) {
        if (recovering || isFinishing() || isDestroyed()) {
            return;
        }
        recovering = true;
        serverDown = true;
        CrashLogger.log(this, "сервер не отвечает (" + reason + ") — поднимаю заново");
        showFadeOverlay("Restarting…", "One moment");
        kickServicesNow(reason);
        probeRecovery(0);
    }

    /** Ждёт подъёма порта после {@link #startRecovery} (шаг 1.5 с). */
    private void probeRecovery(final int step) {
        if (!recovering) {
            return;
        }
        if (step > RECOVERY_PROBE_STEPS) {
            CrashLogger.log(this, "сервер не поднялся за ~2 мин — показываю экран ошибки");
            recovering = false;
            hideFadeOverlay();
            showPlaceholder(ERROR_HTML);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!recovering) {
                    return;
                }
                try {
                    netExec.execute(new Runnable() {
                        @Override
                        public void run() {
                            final boolean up = isServerPortOpen();
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!recovering) {
                                        return;
                                    }
                                    if (up) {
                                        recovering = false;
                                        serverDown = false;
                                        CrashLogger.log(MainActivity.this,
                                                "сервер поднялся — открываю страницу");
                                        hideFadeOverlay();
                                        reloadServer();
                                    } else {
                                        probeRecovery(step + 1);
                                    }
                                }
                            });
                        }
                    });
                } catch (Throwable t) {
                    // Пул уже погашен (onDestroy) — просто выходим.
                    recovering = false;
                }
            }
        }, 1500);
    }

    /**
     * Принудительно отправляет OpenCode-All (минуя анти-спам).
     *
     * Нужно восстановлению и кнопке «Повторить»: там команда должна уйти
     * обязательно, даже если недавно уже слали. OpenCode-All идемпотентен —
     * он сам проверит, какие порты уже подняты.
     */
    private void kickServicesNow(String reason) {
        lastServiceKickMs = SystemClock.uptimeMillis();
        termuxStarted = false; // разрешаем повторную отправку
        try {
            startupAttempted = true;
            startTermuxServices();
        } catch (Throwable t) {
            CrashLogger.log(this, "kickServicesNow (" + reason + "): " + t);
        }
    }

    /**
     * Сервер лёг (авто-выключение по простою, phantom-killer) — поднимаем заново.
     *
     * <p>Вызывается из касаний/клавиш, поэтому жёсткий анти-спам: не чаще
     * {@link #SERVICE_KICK_INTERVAL_MS}, иначе каждое касание слало бы
     * RUN_COMMAND в Termux.
     */
    private void kickServicesIfDown(String reason) {
        if (!serverDown || !termuxStarted) {
            return; // сервер либо жив, либо старт ещё не отправлялся (им занимается onResume)
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastServiceKickMs < SERVICE_KICK_INTERVAL_MS) {
            return;
        }
        lastServiceKickMs = now;
        startRecovery(reason);
    }

    /**
     * Фоновый таймер: пока приложение на экране, раз в
     * {@link #HEALTH_CHECK_INTERVAL_MS} проверяем, жив ли сервер.
     *
     * Это ловит случай «code-server упал, пока мы смотрели»: WebView уже
     * показывает страницу, ошибки загрузки нет, а воркбенч висит в
     * «Connection Lost» и сам бесконечно «reconnect»-ит. Пока приложение в
     * фоне, проверку не делаем (там счёт пингов и сторож).
     */
    private void startHealthCheck() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pingStop) {
                    return;
                }
                try {
                    if (appForeground && termuxStarted && !recovering && !probeInFlight
                            && SystemClock.uptimeMillis() - lastStartRequestMs > START_GRACE_MS) {
                        checkServerAlive("фоновая проверка");
                    }
                } catch (Throwable t) {
                    CrashLogger.log(MainActivity.this, "health-check: " + t);
                }
                handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
            }
        }, HEALTH_CHECK_INTERVAL_MS);
    }

    /**
     * Обновляет номер попытки на УЖЕ показанной заглушке, не перезагружая её.
     *
     * <p>WebView при ошибке соединения не выкидывает текущий документ, поэтому
     * если наша заглушка ещё в документе ({@code window.__oclPlaceholder}),
     * достаточно переписать {@code #oclAttempt} — раньше же каждая попытка
     * стоила полной перезагрузки (мигание раз в 2.5 с). Если же документ
     * ушёл/флага нет — грузим заглушку по-честному.
     */
    private void refreshWaitingPlaceholder(final int attempt) {
        if (webView == null) {
            return;
        }
        String js = "(function(){var e=document.getElementById('oclAttempt');"
                + "if(window.__oclPlaceholder&&e){e.textContent='" + attempt + "';return '1';}"
                + "return '0';})()";
        try {
            webView.evaluateJavascript(js, value -> {
                if ("1".equals(value)) {
                    placeholderShown = true; // заглушка видна — учитываем в onPageFinished
                } else {
                    showPlaceholder(waitingHtml(attempt));
                }
            });
        } catch (Throwable t) {
            CrashLogger.log(this, "обновить счётчик попыток не вышло: " + t);
            showPlaceholder(waitingHtml(attempt));
        }
    }

    /** Версия этой сборки — чтобы по логу сразу видно было, старая APK стоит или новая. */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Диалог с объяснением, почему сервер не стартовал, и кнопкой открыть Termux. */
    private void showTermuxErrorDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Открыть Termux", (d, w) -> openTermux())
                    .setNegativeButton("Закрыть", (d, w) -> d.dismiss())
                    .show();
        } catch (Throwable t) {
            // Окно ещё не готово (частая причина вылета при показе из onResume) —
            // логгируем вместо падения.
            CrashLogger.log(this, "не удалось показать диалог '" + title + "': " + t);
        }
    }

    /** Скрывает статус-бар и навигационную панель (полноэкранный режим). */
    private void hideSystemBars() {
        Window w = getWindow();
        if (w == null) {
            return;
        }
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * Растягивает WebView под всплывающую клавиатуру (IME) в immersive-режиме.
     *
     * Активность идёт с скрытыми системными панелями и
     * {@code setDecorFitsSystemWindows(false)} — в этом режиме Android не подгоняет
     * окно под клавиатуру, и на планшете софтверная клавиатура либо не выезжает,
     * либо перекрывает поле ввода. Решение: слушаем инсеты и вручную ставим
     * нижний padding у корневого контейнера равным высоте клавиатуры, чтобы
     * WebView ужался и поле ввода доскролливалось над клавиатурой.
     *
     * Если клавиатуры нет (физическая подключена) — padding = 0, ничего не меняется.
     */
    private void setupImeResize(final View rootView) {
        try {
            rootView.setOnApplyWindowInsetsListener((v, insets) -> {
                int imeBottom = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsets wi = insets;
                    if (wi.isVisible(WindowInsets.Type.ime())) {
                        imeBottom = wi.getInsets(WindowInsets.Type.ime()).bottom;
                    }
                    // Вручную растягиваем ТОЛЬКО на Android 11+: там
                    // setDecorFitsSystemWindows(false) глушит нативный adjustResize.
                    // Ниже 11 штатный adjustResize из манифеста справляется сам,
                    // и ручной padding лишь удвоил бы ужатие.
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                            v.getPaddingRight(), imeBottom);
                }
                // Возвращаем непоглощённые инсеты, чтобы система не ломала раскладку.
                return insets;
            });
            // Просим пересчитать инсеты (на случай, если клавиатура уже видна).
            rootView.requestApplyInsets();
        } catch (Throwable t) {
            // Не каждый WebView/API позволяет слушать инсеты — не повод падать.
            CrashLogger.log(this, "setupImeResize не удалось: " + t);
        }
    }

    /** Открывает Termux, если он установлен. */
    private void openTermux() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(TERMUX_PKG, "com.termux.app.TermuxActivity");
        try {
            startActivity(i);
        } catch (Exception ignored) {
            // Termux не установлен или отключён — нечего и открывать.
        }
    }

    // ------------------------------------------------- выход / перезапуск

    /**
     * Стеклянная нижняя шторка (≈1/3 экрана) с двумя кнопками:
     * перезапустить сервер и выключить всё.
     *
     * Стилизована под «liquid glass»: полупрозрачная подложка с размытием
     * фона за ней (на API 31+ — настоящее размытие окна, ниже — затемнение),
     * скруглённые верхние углы, иконки с мелкой английской подписью сверху.
     *
     * Закрывается по Esc, кнопке «Назад» и повторному свайпу вверх.
     */
    private void showExitSheet() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (exitSheet != null && exitSheet.isShowing()) {
            return; // уже показана
        }
        try {
            int screenH = getResources().getDisplayMetrics().heightPixels;
            // + стока на ряд «Auto-off» (разделяющая полоса + ползунок).
            int sheetH = Math.max((int) (screenH / 3f + 90 * density),
                    (int) (200 * density));

            final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, sheetH);
                window.setGravity(Gravity.BOTTOM);
                window.setDimAmount(0.35f);
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                if (Build.VERSION.SDK_INT >= 31) {
                    // Настоящее размытие фона — только на Android 12+.
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.setBlurBehindRadius(40);
                    window.setAttributes(lp);
                }
            }

            // Скруглённая «стеклянная» панель: светлое матовое холодное стекло Apple —
            // НЕ чистый белый (#F2F2F7 с прозрачностью), холодная рамка Apple.
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(236, 242, 242, 247));
            bg.setCornerRadii(new float[]{
                    28 * density, 28 * density, 28 * density, 28 * density, 0, 0, 0, 0});
            bg.setStroke((int) (1 * density), Color.argb(120, 199, 199, 204));

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackground(bg);
            int pad = (int) (16 * density);
            root.setPadding(pad, pad, pad, pad);

            // Ряд кнопок: «Перезапустить сервер» (гасит всё и заново поднимает)
            // и «Выключить всё» (гасит сервисы и закрывает приложение).
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);

            row.addView(makeSheetButton(
                    android.R.drawable.ic_menu_rotate,
                    "Restart",
                    density,
                    v -> {
                        dismissExitSheet();
                        restartEverything();
                    }));

            row.addView(makeSheetButton(
                    android.R.drawable.ic_lock_power_off,
                    "Exit",
                    density,
                    v -> {
                        dismissExitSheet();
                        exitEverything();
                    }));

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            root.addView(row, rowLp);

            // --- Ряд «Auto‑off»: дискретный ползунок в стиле iOS —
            // выбор: 15/30 мин, 1/2/4/6/12 ч до авто-выключения
            // всего (code-server, zen-proxy, Termux) при простое.
            View divider = new View(this);
            GradientDrawable divBg = new GradientDrawable();
            divBg.setColor(Color.argb(70, 199, 199, 204));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
            divLp.topMargin = (int) (22 * density);
            divLp.bottomMargin = (int) (14 * density);
            root.addView(divider, divLp);

            LinearLayout tuneRow = new LinearLayout(this);
            tuneRow.setOrientation(LinearLayout.HORIZONTAL);
            tuneRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tuneLabel = new TextView(this);
            tuneLabel.setText("Auto‑off");
            tuneLabel.setTextColor(0xFF1D1D1F);
            tuneLabel.setTextSize(13);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.rightMargin = (int) (12 * density);
            tuneRow.addView(tuneLabel, labelLp);

            int curMin = autoOffMinutes();
            int curIdx = AUTO_OFF_DEFAULT_INDEX;
            for (int i = 0; i < AUTO_OFF_OPTIONS.length; i++) {
                if (AUTO_OFF_OPTIONS[i] == curMin) {
                    curIdx = i;
                    break;
                }
            }

            TextView tuneValue = new TextView(this);
            tuneValue.setText(autoOffLabel(curMin));
            tuneValue.setTextColor(0xFF6E6E73);
            tuneValue.setTextSize(13);
            tuneValue.setMinWidth((int) (38 * density));
            tuneValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

            SeekBar tuneSlider = new SeekBar(this);
            tuneSlider.setMax(AUTO_OFF_OPTIONS.length - 1);
            tuneSlider.setProgress(curIdx);
            tuneSlider.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFF0A84FF));
            tuneSlider.setThumbTintList(
                    android.content.res.ColorStateList.valueOf(0xFF0A84FF));
            tuneSlider.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFD1D1D6));
            tuneSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    tuneValue.setText(autoOffLabel(AUTO_OFF_OPTIONS[val]));
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    setAutoOffMinutes(AUTO_OFF_OPTIONS[sb.getProgress()]);
                }
            });
            LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sliderLp.leftMargin = (int) (4 * density);
            sliderLp.rightMargin = (int) (12 * density);
            tuneRow.addView(tuneSlider, sliderLp);

            LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tuneRow.addView(tuneValue, valueLp);
            root.addView(tuneRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Лаконичная подпись @4rlein внизу шторки.
            TextView credit = new TextView(this);
            credit.setText("@4rlein");
            credit.setTextColor(0xFF8E8E93);
            credit.setTextSize(10);
            credit.setGravity(Gravity.CENTER);
            credit.setLetterSpacing(0.1f);
            LinearLayout.LayoutParams creditLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            creditLp.topMargin = (int) (4 * density);
            root.addView(credit, creditLp);

            // Повторный свайп вверх по шторке — закрыть (как swipe-back).
            root.setOnTouchListener(new View.OnTouchListener() {
                private float startY;

                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startY = ev.getY();
                            return false;
                        case MotionEvent.ACTION_UP:
                            if (startY - ev.getY() > 60 * density) {
                                dialog.dismiss();
                                return true;
                            }
                            return false;
                        default:
                            return false;
                    }
                }
            });

            dialog.setContentView(root);
            dialog.setOnDismissListener(d -> exitSheet = null);
            dialog.show();
            exitSheet = dialog;
            CrashLogger.log(this, "Шторка выхода показана");
        } catch (Throwable t) {
            CrashLogger.log(this, "не удалось показать шторку выхода: " + t);
        }
    }

    private void dismissExitSheet() {
        if (exitSheet != null) {
            try {
                exitSheet.dismiss();
            } catch (Throwable ignored) {
                // Диалог мог быть уже уничтожен системой.
            }
            exitSheet = null;
        }
    }

    /** Кнопка шторки: иконка сверху, мелкая английская подпись под ней. */
    private View makeSheetButton(int iconRes, String label, float density,
                                 View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(onClick);

        int itemPad = (int) (24 * density);
        box.setPadding(itemPad, itemPad, itemPad, itemPad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF1D1D1F); // графит на светлом стекле (оболочка — светлая)
        int iconSize = (int) (30 * density);
        box.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(0xFF1D1D1F);
        text.setTextSize(11);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * density);
        box.addView(text, lp);

        // Растягиваем обе кнопки поровну по ширине шторки.
        LinearLayout.LayoutParams boxLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.setLayoutParams(boxLp);
        return box;
    }

    /** Кнопка «Restart»: гасит всё и заново поднимает сервисы, потом перезагружает страницу. */
    private void restartEverything() {
        CrashLogger.log(this, "Перезапуск серверов");
        recovering = false; // отменяем возможный цикл автовосстановления
        // Плавная маскировочная анимация вместо «мигания»: матовый оверлей
        // «Restarting…» висит, пока серверы не поднимутся и страница не загрузится
        // (гасится в onPageFinished, см. hideFadeOverlay) + страховка по таймауту.
        showFadeOverlay("Restarting…", "One moment");
        handler.postDelayed(this::hideFadeOverlay, 45000); // страховка: страница не пришла
        // Кеш WebView держит code-server'овские assets год (Cache-Control: 1y);
        // при рестарте сбрасываем — обновлённые файлы (например, новый xterm.js)
        // гарантированно перечитаются с сервера. Всё локальное, дорогого не стоит.
        try {
            if (webView != null) {
                webView.clearCache(true);
                CrashLogger.log(this, "restart: кеш WebView сброшен");
            }
        } catch (Throwable t) {
            CrashLogger.log(this, "restart: clearCache fail: " + t);
        }
        sendStopScript(""); // мягкая остановка: Termux живёт, его мы поднимем заново
        // Даём стопперу убить процессы, затем заново поднимаем и перезагружаем страницу.
        handler.postDelayed(() -> {
            termuxStarted = false; // разрешаем повторную отправку OpenCode-All
            try {
                startTermuxServices();
            } catch (Throwable t) {
                CrashLogger.log(this, "restart: startTermuxServices failed: " + t);
            }
            handler.postDelayed(() -> {
                attempts = 0;
                loadServer();
            }, 1500);
        }, 2500);
    }

    /** Кнопка «Exit»: гасит все сервисы и закрывает приложение. */
    private void exitEverything() {
        CrashLogger.log(this, "Выключение: останавливаю сервисы (включая Termux) и закрываюсь");
        recovering = false; // цикл автовосстановления больше не нужен
        // Прощальный матовый экран Apple-стиля вместо резкого исчезновения:
        // «Goodbye» проявляется, стоит ~0.7 с — и только потом мы уходим.
        showFadeOverlay("Goodbye", "Until next time");
        sendStopScript(STOP_ALL_ARGS); // приложение выключили — и Termux не нужен
        // Пауза, чтобы команда успела уйти в Termux, а оверлей — показаться.
        handler.postDelayed(() -> {
            try {
                stopService(new Intent(this, ExitService.class));
            } catch (Throwable ignored) {
                // Сервис мог и не запуститься — не страшно.
            }
            finishAndRemoveTask();
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 1100);
    }

    /**
     * Убивает code-server/zen-proxy/opencode, выполнив OpenCode-Stop через Termux.
     *
     * @param args доп. флаги стопперу; {@link #STOP_ALL_ARGS} — полное выключение
     *             вместе с Termux (Exit, свайп из реестра), "" — мягкая остановка
     *             (Restart: Termux должен остаться живым).
     */
    private void sendStopScript(String args) {
        if (!isPackageInstalled(TERMUX_PKG)) {
            CrashLogger.log(this, "stop: Termux не установлен — некуда слать команду");
            return;
        }
        if (checkSelfPermission(TERMUX_RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            CrashLogger.log(this, "stop: нет разрешения RUN_COMMAND");
            return;
        }
        Intent intent = new Intent(TERMUX_RUN_ACTION);
        intent.setClassName(TERMUX_PKG, TERMUX_RUN_SERVICE);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                "-c", "exec bash " + STOP_SCRIPT + args
        });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME_DIR);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            startService(intent);
            CrashLogger.log(this, "RUN_COMMAND (stop) отправлен");
        } catch (Throwable t) {
            CrashLogger.log(this, "stop: команда не ушла: " + t);
        }
    }

    /** Запускает «невидимый» сервис, который поймает onTaskRemoved при свайпе из реестра. */
    private void startExitService() {
        if (exitServiceStarted) {
            return;
        }
        try {
            startService(new Intent(this, ExitService.class));
            exitServiceStarted = true;
            CrashLogger.log(this, "ExitService запущен");
        } catch (Throwable t) {
            CrashLogger.log(this, "startExitService failed: " + t);
        }
    }

    /** Приёмник: задачу выкинули из реестра → гасим всё. */
    private final BroadcastReceiver taskRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ExitService.ACTION_TASK_REMOVED.equals(intent.getAction())) {
                CrashLogger.log(MainActivity.this,
                        "TASK_REMOVED получен — отправляю стоп-команду (с убийством Termux)");
                sendStopScript(STOP_ALL_ARGS);
            }
        }
    };

    private void registerTaskRemovedReceiver() {
        if (taskReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ExitService.ACTION_TASK_REMOVED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(taskRemovedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(taskRemovedReceiver, filter);
        }
        taskReceiverRegistered = true;
    }

    /**
     * Мостик для кнопок на страницах-заглушках.
     * Вызывается из JS в потоке WebView — всё возвращаем в главный через handler.
     */
    private final class Bridge {
        @JavascriptInterface
        public void retry() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    attempts = 0;
                    if (pendingRetry != null) {
                        handler.removeCallbacks(pendingRetry);
                        pendingRetry = null;
                    }
                    // «Повторить» ДОЛЖНО реально перезапустить сервер, если он
                    // лёг, а не просто перезагрузить URL: иначе WebView крутит
                    // бесконечную загрузку мёртвого адреса. Сервер жив и просто
                    // не открылся — обновляем страницу.
                    if (serverDown || !termuxStarted) {
                        startRecovery("кнопка «Повторить»");
                    } else {
                        reloadServer();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openTermux() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.openTermux();
                }
            });
        }

        /** Текст системного буфера — используется JS-шимом CLIPBOARD_JS. */
        @JavascriptInterface
        public String readClipboardText() {
            // Явно через MainActivity.this — иначе рекурсия на самом себе.
            return MainActivity.this.readClipboardText();
        }

        /** Записывает текст в системный буфер (вызывается из JS-шима). */
        @JavascriptInterface
        public void writeClipboardText(String text) {
            MainActivity.this.writeClipboardText(text);
        }
    }
}
