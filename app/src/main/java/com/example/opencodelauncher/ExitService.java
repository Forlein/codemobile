package com.example.opencodelauncher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;

/**
 * «Невидимый» сервис, который переживает закрытие Activity и уведомляет
 * систему, когда задачу приложения выкинули из списка недавних (свайп).
 *
 * Нужен только ради {@link #onTaskRemoved(Intent)}: Activity такого хука не
 * имеет, а при свайпе приложения из реестра задач Android убивает процесс
 * немедленно — поднять сервер или показать диалог уже некогда. Сервис же
 * получает {@code onTaskRemoved} ДО того, как задача исчезнет, и может
 * выслать RUN_COMMAND в Termux, чтобы погасить code-server/zen-proxy/opencode.
 *
 * Стоп-команда шлётся НАПРЯМУЮ из {@link #onTaskRemoved} (без гонки
 * broadcast → Activity → startService: на MIUI/HyperOS Activity часто умирает
 * раньше, чем успевает отработать приёмник). Broadcast остаётся запасным
 * путём для случая, когда процесс ещё жив.
 *
 * Запускается из {@link MainActivity#onCreate} через startService и держится
 * живым всё время работы обёртки.
 */
public class ExitService extends Service {

    /** Сигнатура нашего broadcast'а — на неё подписана MainActivity. */
    static final String ACTION_TASK_REMOVED = "com.example.opencodelauncher.TASK_REMOVED";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Никогда не пересоздаём: сервису нечего делать, кроме хука onTaskRemoved.
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        CrashLogger.log(this, "onTaskRemoved: задача выкинута из реестра — "
                + "шлю стоп-команду напрямую и рассылаю TASK_REMOVED");
        // 1) Основной путь: сразу сами, пока процесс ещё жив.
        sendStopNow();
        // 2) Запасной: broadcast — вдруг Activity ещё живёт и шлёт свой вариант.
        try {
            Intent i = new Intent(ACTION_TASK_REMOVED);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) {
            CrashLogger.log(this, "onTaskRemoved: broadcast не ушёл: " + t);
        }
        super.onTaskRemoved(rootIntent);
    }

    /** Прямая отправка OpenCode-Stop в Termux (та же команда, что и в MainActivity). */
    private void sendStopNow() {
        try {
            if (checkSelfPermission(MainActivity.TERMUX_RUN_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                CrashLogger.log(this, "onTaskRemoved: нет разрешения RUN_COMMAND — "
                        + "стоп-команда не уйдёт");
                return;
            }
            Intent intent = new Intent(MainActivity.TERMUX_RUN_ACTION);
            intent.setClassName(MainActivity.TERMUX_PKG, MainActivity.TERMUX_RUN_SERVICE);
            intent.putExtra("com.termux.RUN_COMMAND_PATH", MainActivity.TERMUX_BASH);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                    "-c", "exec bash " + MainActivity.STOP_SCRIPT + MainActivity.STOP_ALL_ARGS
            });
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", MainActivity.HOME_DIR);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(intent);
            CrashLogger.log(this, "onTaskRemoved: стоп-команда отправлена напрямую");
        } catch (Throwable t) {
            CrashLogger.log(this, "onTaskRemoved: прямая отправка не удалась: " + t);
        }
    }

    @Override
    public void onDestroy() {
        CrashLogger.log(this, "ExitService.onDestroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Нам не нужен привязанный сервис — только запущенный.
        return null;
    }
}
