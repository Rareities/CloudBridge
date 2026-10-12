package io.github.rareities.protonbridge.util;

import java.util.regex.Pattern;

/** Redact before collecting logs or placing an error into persistent history. */
public final class DiagnosticRedactor {
    private DiagnosticRedactor() {}
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern URL_AUTH = Pattern.compile("(?i)(https?|socks5?)://[^\\s/@]+:[^\\s/@]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)((?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern HEADER = Pattern.compile("(?im)((?:proxy-)?authorization|(?:set-)?cookie)\\s*:\\s*[^\\r\\n]*");
    private static final String KEYS = "(?:[a-z0-9_-]*(?:password|passwd|pass|token|secret|private_key|api_key)|authorization|cookie|set-cookie|api-key)";
    private static final Pattern JSON_SECRET = Pattern.compile("(?i)(\"" + KEYS + "\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*+\"?");
    private static final Pattern SECRET = Pattern.compile("(?i)(?<![a-z0-9_\"])(" + KEYS + "\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^,\\s}]+)");
    public static String redact(String value) {
        if (value == null) return "";
        value = ANSI.matcher(value).replaceAll("");
        value = HEADER.matcher(value).replaceAll("$1: [REDACTED]");
        String result = URL_AUTH.matcher(value).replaceAll("$1://[redacted]@");
        result = BEARER.matcher(result).replaceAll("$1[redacted]");
        result = JSON_SECRET.matcher(result).replaceAll("$1\"[redacted]\"");
        return SECRET.matcher(result).replaceAll("$1[redacted]");
    }
}
