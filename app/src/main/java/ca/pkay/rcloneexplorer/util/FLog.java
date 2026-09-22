package ca.pkay.rcloneexplorer.util;

import android.util.Log;

import java.util.IllegalFormatException;

import ca.pkay.rcloneexplorer.BuildConfig;

/**
 * A simple wrapper around {@link Log} to enable easier debugging without
 * slowing down all app functions.
 */
public abstract class FLog {

    private static final String PATTERN_PATH = "/";
    private static final String REPLACE_PATH = "***anonymized_path***";
    private static final String PATTERN_URI = "content://";
    private static final String REPLACE_URI = "***anonymized_uri***";
    
    /**
     * Use this log tag to set a lower than default (<=INFO) log tag for all
     * app log messages.
     * <br><br><b>Example</b><br>
     *     <code>adb shell setprop log.tag.APP_MIN VERBOSE</code>
     */
    public static final String LOGGING_MIN_LEVEL_TAG = "APP_MIN";

    public static void v(String tag, String message, Object... args) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (isLoggable(tag, Log.VERBOSE)) {
            Log.v(tag, applyFormatting(message, args));
        }
    }

    public static void d(String tag, String message, Object... args) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, applyFormatting(message, args));
        }
    }

    public static void i(String tag, String message, Object... args) {
        if (isLoggable(tag, Log.INFO)) {
            Log.i(tag, applyFormatting(message, args));
        }
    }

    public static void w(String tag, String message, Object... args) {
        if (isLoggable(tag, Log.WARN)) {
            Log.w(tag, applyFormatting(message, args));
        }
    }

    public static void w(String tag, String message, Exception e, Object... args) {
        if (isLoggable(tag, Log.WARN)) {
            Log.w(tag, withThrowable(applyFormatting(message, args), e));
        }
    }

    private static final class LoggedError extends Throwable {

        public LoggedError() {
            super();
            removeLogTrace();
        }

        private void removeLogTrace() {
            StackTraceElement[] traces = getStackTrace();
            // The constuructor is called indirectly, i.e. rewind by 2
            int trim = 1;
            if (traces.length > trim) {
                StackTraceElement[] trimmed = new StackTraceElement[traces.length - trim];
                for (int i = trim; i < traces.length; i++) {
                    trimmed[i - trim] = traces[i];
                }
                setStackTrace(trimmed);
            }
        }
    }

    public static void e(String tag, String message, Object... args) {
        if (isLoggable(tag, Log.ERROR)) {
            Log.e(tag, applyFormatting(message, args));
        }
    }

    public static void e(String tag, String message, Throwable e, Object... args) {
        String formatted = applyFormatting(message, args);
        if (isLoggable(tag, Log.ERROR)) {
            Log.e(tag, withThrowable(formatted, e));
        }
    }

    private static String applyFormatting(String message, Object... args) {
        if (args.length == 0) {
            return LogRedactor.redact(message);
        } else {
            try {
                return LogRedactor.redact(String.format(message, args));
            } catch (IllegalFormatException e) {
                // We really shouldn't crash here even if there is a format
                // error since this is usally used without in error logging
                // itself.
                return LogRedactor.redact(message);
            }
        }
    }

    private static String withThrowable(String message, Throwable throwable) {
        if (throwable == null) {
            return message;
        }
        String detail = throwable.getMessage();
        if (detail == null || detail.isEmpty()) {
            return message + " [" + throwable.getClass().getSimpleName() + "]";
        }
        return message + " [" + throwable.getClass().getSimpleName() + ": "
                + LogRedactor.redact(detail) + "]";
    }
    
    private static final boolean isLoggable(String tag, int level){
        if(BuildConfig.DEBUG) {
            return Log.isLoggable(tag, level) || level != Log.INFO && Log.isLoggable(LOGGING_MIN_LEVEL_TAG, level);
        } else {
            return Log.isLoggable(tag, level);
        }
    }
}
