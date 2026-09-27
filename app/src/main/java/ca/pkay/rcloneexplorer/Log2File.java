package ca.pkay.rcloneexplorer;

import android.annotation.SuppressLint;
import android.content.Context;
import ca.pkay.rcloneexplorer.util.BoundedFileAppender;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.LogRedactor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Log2File {

    private static final String TAG = "Log2File";
    static final int MAX_PENDING_LOG_RECORDS = 128;
    private static final long MAX_LOG_FILE_BYTES = 10L * 1024L * 1024L;
    private static final AtomicBoolean QUEUE_FULL_WARNING_REPORTED = new AtomicBoolean();
    private static final ThreadPoolExecutor LOG_WRITER = createLogWriter();
    private Context context;

    static ThreadPoolExecutor createLogWriter() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_LOG_RECORDS), runnable -> {
                    Thread thread = new Thread(runnable, "CloudBridge-log-writer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Log2File(Context context) {
        this.context = context;
    }

    public void log(String message) {
        File path = context.getExternalFilesDir("logs");
        if (path == null) {
            return;
        }
        File logFile = new File(path, "log.txt");

        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentDateTime = dateFormat.format(new Date());

        String logMessage = currentDateTime + " - " + safeLogMessage(message) + "\n";
        try {
            LOG_WRITER.execute(() -> writeRecord(logFile, logMessage));
        } catch (RejectedExecutionException e) {
            // Keep logging asynchronous and bounded under bursts; never block the caller or
            // enqueue an unbounded number of records. Report saturation once per process.
            if (QUEUE_FULL_WARNING_REPORTED.compareAndSet(false, true)) {
                FLog.w(TAG, "Diagnostic log queue is full; dropping new records");
            }
        }
    }

    static String safeLogMessage(String message) {
        // LogRedactor also caps output at MAX_DIAGNOSTIC_CHARS, bounding each queued record.
        return LogRedactor.redact(message);
    }

    private static void writeRecord(File logFile, String logMessage) {
        try {
            if (!BoundedFileAppender.append(logFile,
                    logMessage.getBytes(StandardCharsets.UTF_8), MAX_LOG_FILE_BYTES)) {
                FLog.w(TAG, "Diagnostic log record exceeds the configured size limit");
            }
        } catch (IOException e) {
            FLog.e(TAG, "Could not write log file", e);
        }
    }
}
