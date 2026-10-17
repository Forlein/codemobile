package com.example.opencodelauncher;

import android.app.Application;
import android.os.Process;

/**
 * Точка входа процесса.
 *
 * Ставит ловушку падений и пишет «флаг» в лог РАНЬШЕ, чем создаётся Activity:
 * так в трассе видно, что процесс вообще запустился. Если приложение закрывается
 * до onCreate Activity, файл в Download это покажет, а если и он пуст —
 * значит, дело не в нашем коде, а в системе/пакете.
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
        CrashLogger.log(this, "=== PROCESS START pid=" + Process.myPid()
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " ver=" + versionName() + " ===");
        CrashLogger.resetIfTooBig(this);
    }

    /** Версия сборки — попадает в первую же строку лога (см. onCreate). */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
