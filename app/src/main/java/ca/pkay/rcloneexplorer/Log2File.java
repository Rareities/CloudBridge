package ca.pkay.rcloneexplorer;

import android.content.Context;
import ca.pkay.rcloneexplorer.util.BoundedLogFile;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SecretRedactor;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Log2File {
    private final Context context;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
        new ThreadPoolExecutor.DiscardOldestPolicy());
    static { WRITER.allowCoreThreadTimeOut(true); }
    public Log2File(Context context) { this.context = context.getApplicationContext(); }
    public void log(String message) {
        final File file = new File(context.getFilesDir(), "logs/log.txt");
        final String safe = SecretRedactor.redact(message);
        WRITER.execute(() -> {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
            try { BoundedLogFile.append(file, timestamp + " - " + safe); }
            catch (IOException e) { FLog.e("Log2File", "Could not write log file"); }
        });
    }
}
