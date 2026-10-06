package com.linuxsuren.tvuvc;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 闪退防护网：未捕获异常写入文件，下次启动时显示在状态页上。
 * 无调试通道的定制设备上，这是唯一可见的错误报告途径。
 */
public final class CrashGuard {

    private static final String FILE = "last_crash.txt";

    private CrashGuard() {
    }

    public static void install() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File dir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/com.linuxsuren.tvuvc/files");
                if (!dir.exists() && !dir.mkdirs()) {
                    dir = new File("/sdcard/tvuvc_crash");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                File out = new File(dir, FILE);
                FileWriter writer = new FileWriter(out, false);
                writer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + " thread=" + thread.getName() + "\n");
                writer.write(throwable.getClass().getName() + ": " + throwable.getMessage() + "\n");
                for (StackTraceElement e : throwable.getStackTrace()) {
                    writer.write("  at " + e + "\n");
                }
                writer.close();
            } catch (IOException ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /** 读取上次崩溃记录（读后即清）。 */
    public static String lastCrash(Context context) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/com.linuxsuren.tvuvc/files");
            File file = new File(dir, FILE);
            if (!file.exists()) {
                file = new File("/sdcard/tvuvc_crash", FILE);
            }
            if (!file.exists()) {
                return null;
            }
            byte[] bytes = new byte[(int) file.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            //noinspection ResultOfMethodCallIgnored
            in.read(bytes);
            in.close();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return new String(bytes);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
