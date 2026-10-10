package ca.pkay.rcloneexplorer.util;

import java.util.regex.Pattern;

/** Defense in depth; callers must still avoid collecting raw credentials or HTTP headers. */
public final class SecretRedactor {
    private SecretRedactor() {}
    private static final String KEYS = "(?:[A-Za-z0-9_-]*(?:password|passwd|token|secret|salted[-_]key[-_]pass|private[-_]key|config[-_]pass|rc[-_]pass|passphrase|api[-_]key)[A-Za-z0-9_-]*|authorization|cookie|set-cookie|otp|otp_secret_key)";
    private static final Pattern JSON_SECRET = Pattern.compile("(?i)(\\\"" + KEYS + "\\\"\\s*:\\s*)\\\"(?:\\\\.|[^\\\"\\\\]++)*+\\\"");
    private static final Pattern FIELD_SECRET = Pattern.compile("(?i)((?:--)?" + KEYS + "\\s*(?:=|:|\\s)\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\]++)*+\\\"|'[^']*'|[^\\s,;\\]\\}]+)");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/=_~.:-]+");
    private static final Pattern URL_USER = Pattern.compile("(?i)(https?|socks5h?|ftp)://[^\\s/@]+(?::[^\\s/@]*)?@");
    private static final Pattern HEADERS = Pattern.compile("(?im)((?:authorization|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]*");
    private static final Pattern PEM = Pattern.compile("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");

    public static String redact(String text) {
        if (text == null) return "";
        // Redact before truncating: a cutoff inside a quoted value could otherwise evade matching.
        String safe = PEM.matcher(text).replaceAll("[REDACTED KEY]");
        safe = JSON_SECRET.matcher(safe).replaceAll("$1\"[REDACTED]\"");
        safe = HEADERS.matcher(safe).replaceAll("$1[REDACTED]");
        safe = AUTH.matcher(safe).replaceAll("$1 [REDACTED]");
        safe = FIELD_SECRET.matcher(safe).replaceAll("$1[REDACTED]");
        safe = URL_USER.matcher(safe).replaceAll("$1://[REDACTED]@");
        return safe.length() > 16384 ? safe.substring(0, 16384) + " [truncated]" : safe;
    }
}
