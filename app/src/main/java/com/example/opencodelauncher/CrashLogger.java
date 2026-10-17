package com.example.opencodelauncher;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ловит любое необработанное исключение и пишет полный стек + трассировку
 * жизненного цикла в три места сразу:
 *
 *   1) logcat (тег {@code OpenCodeLauncher}) — видно через `adb logcat`;
 *   2) /storage/emulated/0/Download/opencodelauncher.log — ЕДИНСТВЕННОЕ место,
 *      которое Termux может прочитать напрямую. Папка
 *      Android/data/&lt;pkg&gt;/files на Android 11+ закрыта scoped storage,
 *      поэтому старый вариант с внешней папкой приложения был бесполезен:
 *      лог писался, но прочитать его из Termux было нельзя;
 *   3) внешняя папка приложения — запасной вариант.
 *
 * Каждое событие (onCreate/onResume/ошибки загрузки страницы) тоже попадает
 * в этот файл, чтобы по трассе было видно, на каком шаге приложение встало.
 */
final class CrashLogger {

    private static final String TAG = "OpenCodeLauncher";

    /** Имя файла в Download — Termux читает его как ~/storage/shared/Download/... */
    private static final String PUBLIC_LOG_NAME = "opencodelauncher.log";
    /** Не даём файлу расти бесконечно: при старте обрезаем, если он уже большой. */
    private static final long MAX_PUBLIC_LOG_BYTES = 256 * 1024;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private static volatile boolean installed;
    /** URI файла в Download; резолвим один раз, а не на каждую запись. */
    private static volatile Uri publicLogUri;

    private CrashLogger() {
    }

    /** Ставит глобальный обработчик. Идемпотентно, безопасно вызывать в onCreate. */
    static void install(final Context context) {
        if (installed) {
            return;
        }
        installed = true;

        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    // Пишем СИНХРОННО: процесс вот-вот умрёт, фоновый поток может не успеть.
                    writeCrash(context, t, e);
                } catch (Throwable ignored) {
                    // Ничего не поделать — падаем дальше.
                } finally {
                    // Отдаём обработку прежнему обработчику, чтобы система показала свой диалог.
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            }
        });
    }

    /**
     * Пишет одно строковое событие в лог (в фоне).
     * Вызывается из onCreate/onResume/колбэков WebView для трассировки.
     */
    static void log(final Context context, final String message) {
        final String line = stamp() + " " + message + "\n";
        Log.i(TAG, message);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                appendAll(context, line);
            }
        });
    }

    /** Сбрасывает публичный лог, если он разросся, и пишет заголовок с версией. */
    static void resetIfTooBig(final Context context) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri uri = resolvePublicLogUri(context);
                    if (uri == null) {
                        return;
                    }
                    ContentResolver cr = context.getContentResolver();
                    long size = -1;
                    Cursor c = null;
                    try {
                        String[] proj = {MediaStore.MediaColumns.SIZE};
                        c = cr.query(uri, proj, null, null, null);
                        if (c != null && c.moveToFirst()) {
                            size = c.getLong(0);
                        }
                    } finally {
                        if (c != null) {
                            c.close();
                        }
                    }
                    if (size > MAX_PUBLIC_LOG_BYTES) {
                        // Перезаписываем «пустым» хвостом: append ниже продолжит в этот же файл.
                        writePublic(context, uri,
                                "\n\n=== ЛОГ ОБРЕЗАН (был " + size + " байт) ===\n\n");
                    }
                } catch (Throwable ignored) {
                    // Диагностика не должна ронять приложение.
                }
            }
        });
    }

    // ------------------------------------------------------------- internals

    private static void writeCrash(Context context, Thread t, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CRASH @ ").append(stamp()).append(" ===\n");
        sb.append("thread: ").append(t.getName()).append('\n');
        sb.append("device: ").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL)
                .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n");

        // NB: PrintWriter(StringBuilder) — это API Java 10, в Android его НЕТ.
        // Поэтому пишем в StringWriter, а не в StringBuilder (иначе сборка падает).
        java.io.StringWriter sw = new java.io.StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        // Причина цепочки исключений (cause) — часто в ней и кроется корень проблемы.
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            pw.println("\nCaused by:");
            c.printStackTrace(pw);
        }
        pw.flush();

        String body = sb.toString() + sw.toString();
        Log.e(TAG, body);
        appendAll(context, body);
    }

    /** Пишет текст во все доступные места. Вызывается только из фонового потока (или синхронно при краше). */
    private static void appendAll(Context context, String text) {
        // 1) Папка приложения — работает всегда, но Termux её не прочитает.
        try {
            File file = resolveLogFile(context);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write(text);
            }
        } catch (Throwable ignored) {
            // Диагностика не должна ронять приложение.
        }

        // 2) Download/ — сюда Termux имеет доступ.
        appendPublic(context, text);
    }

    /** Куда пишем запасной лог: внешняя папка приложения, с фолбэком на внутреннюю. */
    static File resolveLogFile(Context context) {
        File[] externals = context.getExternalFilesDirs(null);
        if (externals != null) {
            for (File dir : externals) {
                if (dir != null) {
                    return new File(dir, "crash.log");
                }
            }
        }
        return new File(context.getFilesDir(), "crash.log");
    }

    // ------------------------------------------------- публичный лог в Download

    private static void appendPublic(Context context, String text) {
        if (Build.VERSION.SDK_INT < 29) {
            return; // До Android 10 MediaStore.Downloads нет; оставляем logcat + папку приложения.
        }
        try {
            Uri uri = resolvePublicLogUri(context);
            if (uri != null) {
                writePublic(context, uri, text);
            }
        } catch (Throwable ignored) {
            // Диагностика не должна ронять приложение.
        }
    }

    private static void writePublic(Context context, Uri uri, String text) throws Exception {
        ContentResolver cr = context.getContentResolver();
        byte[] bytes = text.getBytes(Charset.forName("UTF-8"));
        // "wa" = write-append; без этого каждая запись затирала бы весь лог.
        // Часть MediaStore не умеет append и бросает исключение — тогда берём
        // "rw" и сами ставим позицию записи в конец файла.
        try {
            appendWith(cr, uri, "wa", bytes, false);
        } catch (Throwable primary) {
            Log.w(TAG, "запись в режиме \"wa\" не удалась (" + primary + "), пробую \"rw\"");
            appendWith(cr, uri, "rw", bytes, true);
        }
    }

    private static void appendWith(ContentResolver cr, Uri uri, String mode,
                                   byte[] bytes, boolean seekToEnd) throws Exception {
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, mode);
             OutputStream os = new FileOutputStream(pfd.getFileDescriptor())) {
            if (seekToEnd) {
                long size = pfd.getStatSize();
                if (size > 0) {
                    ((FileOutputStream) os).getChannel().position(size);
                }
            }
            os.write(bytes);
            os.flush();
        }
    }

    /**
     * Находит или создаёт файл в Download и возвращает его URI.
     * Скрываем IS_PENDING, пока файл не готов — иначе он не виден вне приложения.
     */
    private static synchronized Uri resolvePublicLogUri(Context context) {
        if (publicLogUri != null) {
            return publicLogUri;
        }
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }

        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        ContentResolver cr = context.getContentResolver();

        // Ищем уже созданный файл, чтобы не плодить "opencodelauncher (1).log".
        Cursor c = null;
        try {
            String[] proj = {MediaStore.Downloads._ID};
            String sel = MediaStore.Downloads.DISPLAY_NAME + "=?";
            c = cr.query(collection, proj, sel, new String[]{PUBLIC_LOG_NAME}, null);
            if (c != null && c.moveToFirst()) {
                publicLogUri = ContentUris.withAppendedId(collection, c.getLong(0));
                return publicLogUri;
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, PUBLIC_LOG_NAME);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri created = cr.insert(collection, values);
        if (created == null) {
            return null;
        }
        publicLogUri = created;

        // Снимаем IS_PENDING, иначе файл невидим для других приложений (Termux).
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(created, done, null, null);
        return created;
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
