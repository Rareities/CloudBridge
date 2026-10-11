package io.github.rareities.protonbridge;

import android.content.Context;
import io.github.rareities.protonbridge.util.BoundedLogWriter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Log2File {

    private static final BoundedLogWriter WRITER = new BoundedLogWriter();
    private final File logFile;

    public Log2File(Context context) {
        logFile = new File(context.getFilesDir(), "logs/log.txt");
    }

    public void log(String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        WRITER.append(logFile, timestamp + " - " + message);
    }
}
