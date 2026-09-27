package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounds and redacts caller-provided text before it reaches notification/report sinks. */
public final class NotificationSinkPolicy {

    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_CONTENT_CHARS = 2 * 1024;
    public static final int MAX_DETAILS_LINES = 24;
    public static final int MAX_DETAILS_TOTAL_CHARS = 8 * 1024;
    /** Bound inspection as well as accepted output when callers provide sparse/blank lists. */
    public static final int MAX_DETAIL_SCAN_ITEMS = MAX_DETAILS_LINES * 4;
    public static final int MAX_REPORT_ENTRIES = 50;
    public static final int MAX_REPORT_BYTES = 16 * 1024;

    private static final int MAX_REDACTION_INPUT_CHARS = 16 * 1024;
    private static final String TRUNCATION_MARKER = "…";
    private static final String DEFAULT_TITLE = "CloudBridge";

    private NotificationSinkPolicy() {
    }

    public static String sanitizeTitle(@Nullable String raw) {
        return sanitizeSingleLine(raw, MAX_TITLE_CHARS, DEFAULT_TITLE);
    }

    public static String sanitizeContent(@Nullable String raw) {
        return sanitizeSingleLine(raw, MAX_CONTENT_CHARS, "");
    }

    /** Return a bounded copy so caller-owned mutable lists cannot enlarge the notification. */
    public static ArrayList<String> sanitizeDetails(@Nullable List<String> details) {
        ArrayList<String> result = new ArrayList<>();
        if (details == null) {
            return result;
        }

        int totalChars = 0;
        int inspected = 0;
        for (String detail : details) {
            if (inspected++ >= MAX_DETAIL_SCAN_ITEMS) {
                break;
            }
            if (result.size() >= MAX_DETAILS_LINES || totalChars >= MAX_DETAILS_TOTAL_CHARS) {
                break;
            }
            String safe = sanitizeContent(detail);
            if (safe.isEmpty()) {
                continue;
            }
            int remaining = MAX_DETAILS_TOTAL_CHARS - totalChars;
            if (safe.length() > remaining) {
                safe = truncateWithMarker(safe, remaining);
            }
            result.add(safe);
            totalChars += safe.length();
        }
        return result;
    }

    /**
     * Prepend a sanitized entry to newest-first history while retaining only bounded rows
     * and UTF-8 bytes. Existing persisted rows are re-sanitized on read during migration.
     */
    public static String prependReport(
            @Nullable String existingHistory,
            @Nullable String rawTitle,
            @Nullable String rawContent) {
        String newest = sanitizeTitle(rawTitle) + ": " + sanitizeContent(rawContent) + "\n";
        StringBuilder result = new StringBuilder(Math.min(MAX_REPORT_BYTES, newest.length() * 2));
        int resultBytes = appendIfFits(result, newest, 0);
        int entries = resultBytes == 0 ? 0 : 1;

        if (existingHistory == null || existingHistory.isEmpty() || entries == 0) {
            return result.toString();
        }

        // Only inspect a bounded prefix. History is newest-first, so older data beyond this
        // window can be discarded without scanning an arbitrarily large legacy preference.
        int scanLimit = Math.min(existingHistory.length(), MAX_REPORT_BYTES + 1);
        int cursor = 0;
        while (cursor < scanLimit && entries < MAX_REPORT_ENTRIES
                && resultBytes < MAX_REPORT_BYTES) {
            int newline = existingHistory.indexOf('\n', cursor);
            boolean completeRow = newline >= 0 && newline < scanLimit;
            int end = completeRow ? newline : scanLimit;
            String rawRow = existingHistory.substring(cursor, end);
            cursor = completeRow ? end + 1 : scanLimit;
            if (rawRow.isEmpty()) {
                continue;
            }

            String safeRow = sanitizeContent(rawRow) + "\n";
            int updatedBytes = appendIfFits(result, safeRow, resultBytes);
            if (updatedBytes == resultBytes) {
                break;
            }
            resultBytes = updatedBytes;
            entries++;
            if (!completeRow) {
                break;
            }
        }
        return result.toString();
    }

    /** Number of non-empty report rows in a sanitized history value. */
    public static int reportEntryCount(@Nullable String history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int count = 0;
        int rowStart = 0;
        for (int i = 0; i < history.length(); i++) {
            if (history.charAt(i) == '\n') {
                if (i > rowStart) {
                    count++;
                }
                rowStart = i + 1;
            }
        }
        if (rowStart < history.length()) {
            count++;
        }
        return count;
    }

    /** Keeps the legacy aggregator's empty-history value and trailing-line counting behavior. */
    public static int aggregationLineCount(@Nullable String history) {
        if (history == null || history.isEmpty()) {
            return 1;
        }
        int count = 1;
        int scanLimit = Math.min(history.length(), MAX_REPORT_BYTES + 1);
        for (int i = 0; i < scanLimit && count <= MAX_REPORT_ENTRIES + 1; i++) {
            if (history.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    public static int utf8Bytes(@Nullable String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int appendIfFits(
            StringBuilder target,
            String value,
            int currentBytes) {
        int valueBytes = utf8Bytes(value);
        if (valueBytes > MAX_REPORT_BYTES || currentBytes > MAX_REPORT_BYTES - valueBytes) {
            return currentBytes;
        }
        target.append(value);
        return currentBytes + valueBytes;
    }

    private static String truncateWithMarker(String value, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        int prefixLength = Math.max(0, maxChars - TRUNCATION_MARKER.length());
        if (prefixLength > 0 && Character.isHighSurrogate(value.charAt(prefixLength - 1))) {
            prefixLength--;
        }
        return value.substring(0, prefixLength) + TRUNCATION_MARKER;
    }

    private static String sanitizeSingleLine(
            @Nullable String raw,
            int maxChars,
            String fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }

        boolean inputTruncated = raw.length() > MAX_REDACTION_INPUT_CHARS;
        String boundedInput = inputTruncated
                ? raw.substring(0, MAX_REDACTION_INPUT_CHARS)
                : raw;
        String redacted = LogRedactor.redact(boundedInput);
        if (redacted == null || redacted.isEmpty()) {
            return fallback;
        }

        StringBuilder safe = new StringBuilder(Math.min(redacted.length(), maxChars));
        boolean pendingSpace = false;
        boolean truncated = inputTruncated;
        for (int offset = 0; offset < redacted.length();) {
            int codePoint = redacted.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                pendingSpace = safe.length() > 0;
                continue;
            }

            int pendingChars = pendingSpace ? 1 : 0;
            int codePointChars = Character.charCount(codePoint);
            if (safe.length() + pendingChars + codePointChars > maxChars) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                safe.append(' ');
                pendingSpace = false;
            }
            safe.appendCodePoint(codePoint);
        }

        if (safe.length() == 0) {
            return fallback;
        }
        if (truncated) {
            while (safe.length() > maxChars - TRUNCATION_MARKER.length()) {
                safe.setLength(safe.length() - 1);
                if (safe.length() > 0 && Character.isHighSurrogate(safe.charAt(safe.length() - 1))) {
                    safe.setLength(safe.length() - 1);
                }
            }
            safe.append(TRUNCATION_MARKER);
        }
        return safe.toString();
    }
}
