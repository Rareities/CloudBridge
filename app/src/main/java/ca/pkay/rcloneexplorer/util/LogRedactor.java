package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Redacts secrets and private paths before diagnostic data reaches logcat or a
 * user-visible log file. This is deliberately small and dependency-free so it
 * can be used at every logging sink.
 */
public final class LogRedactor {

    public static final int MAX_DIAGNOSTIC_CHARS = 16 * 1024;
    private static final String TRUNCATION_MARKER = "\n***diagnostic-output-truncated***";
    private static final String REDACTED = "***redacted***";
    private static final String REDACTED_PATH = "***redacted-path***";
    private static final String REDACTED_URI = "***redacted-uri***";

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)((?:rclone_config_pass|client_secret|access_token|refresh_token|authorization"
                    + "|password|passwd|token|secret|api[_-]?key)\\s*[:=]\\s*)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s,;\\]}]+)");
    private static final Pattern OPTION_SECRET = Pattern.compile(
            "(?i)((?:--rc-pass|--password|--token|--secret)\\s+)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s,;\\]}]+)");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(\\bBearer\\s+)([^\\s,;\\]}]+)");
    private static final Pattern CONTENT_URI = Pattern.compile(
            "(?i)(content://)[^\\s\\r\\n,;\\]}]+");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9/:])((?:/|[A-Za-z]:\\\\)[^\\s\\r\\n,;\\]}]+)");

    private LogRedactor() {
    }

    /**
     * Return a bounded, diagnostic-safe representation of arbitrary text.
     * Null is preserved so callers can use this at exception and log sinks.
     */
    @Nullable
    public static String redact(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        String redacted = BEARER_TOKEN.matcher(value).replaceAll("$1" + REDACTED);
        redacted = KEY_VALUE_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = OPTION_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = CONTENT_URI.matcher(redacted).replaceAll("$1" + REDACTED_URI);
        redacted = ABSOLUTE_PATH.matcher(redacted).replaceAll(REDACTED_PATH);
        if (redacted.length() > MAX_DIAGNOSTIC_CHARS) {
            int contentLength = MAX_DIAGNOSTIC_CHARS - TRUNCATION_MARKER.length();
            return redacted.substring(0, Math.max(0, contentLength)) + TRUNCATION_MARKER;
        }
        return redacted;
    }
}
