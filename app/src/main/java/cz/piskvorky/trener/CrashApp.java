package cz.piskvorky.trener;

import android.app.Application;
import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    getSharedPreferences("crash", MODE_PRIVATE).edit()
                            .putString("trace", "Vlakno: " + t.getName() + "\n" + sw).commit();
                } catch (Throwable ignored) {
                }
                if (old != null) old.uncaughtException(t, e);
            }
        });
    }
}
