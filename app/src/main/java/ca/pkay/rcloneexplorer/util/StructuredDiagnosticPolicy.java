package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ca.pkay.rcloneexplorer.util.LogRedactor;
import ca.pkay.rcloneexplorer.util.RemoteErrorMessagePolicy;

/**
 * Bounds and sanitizes structured remote-operation errors before they are retained for
 * notifications, reports, or logging. The policy intentionally has no Android dependencies
 * beyond the nullable annotation so it can be tested in the JVM test source set.
 */
public final class StructuredDiagnosticPolicy {

    public static final int MAX_RECORDS = 32;
    public static final int MAX_FIELD_CHARS = 512;
    public static final int MAX_AGGREGATE_CHARS = 8 * 1024;

    private static final String FIELD_TRUNCATION_MARKER = "…";
    private static final String OMITTED_FORMAT =
            "\n… %d additional diagnostic error(s) omitted";

    private StructuredDiagnosticPolicy() {
    }

    /** Return a redacted, single-line, bounded representation of an offending object name. */
    public static String sanitizeObject(@Nullable String rawObject) {
        return normalizeAndBound(LogRedactor.redact(rawObject));
    }

    /** Return the existing user-safe remote error representation, with a final field bound. */
    public static String sanitizeMessage(@Nullable String rawMessage) {
        return normalizeAndBound(RemoteErrorMessagePolicy.forUser(rawMessage));
    }

    /** Sanitize both fields at the ingestion boundary. */
    public static SanitizedError sanitize(
            @Nullable String rawObject,
            @Nullable String rawMessage) {
        return new SanitizedError(sanitizeObject(rawObject), sanitizeMessage(rawMessage));
    }

    /**
     * Append one already-sanitized record without exceeding the aggregate output limit.
     *
     * @return true when the complete record was appended; false when it would exceed the limit.
     */
    public static boolean appendRecord(
            StringBuilder target,
            @Nullable String offendingFileLabel,
            SanitizedError record) {
        if (target == null || record == null) {
            return false;
        }

        // This label is an app-owned localized resource, not remote input. Preserve its
        // spacing so existing notification/report text remains compatible, while still
        // bounding it independently from the remote fields.
        String safeLabel = boundTrustedLabel(offendingFileLabel);
        String message = record.getMessage();
        String object = record.getObjectName();
        int required = message.length() + 1 + safeLabel.length() + object.length() + 1;
        if (required > MAX_AGGREGATE_CHARS
                || target.length() > MAX_AGGREGATE_CHARS - required) {
            return false;
        }

        target.append(message).append('\n')
                .append(safeLabel).append(object).append('\n');
        return true;
    }

    /** Append a fixed, non-user-controlled omission summary while preserving the aggregate bound. */
    public static String appendOmittedSummary(StringBuilder target, int omittedCount) {
        if (target == null) {
            return "";
        }
        if (omittedCount <= 0) {
            return target.toString();
        }

        String summary = String.format(Locale.ROOT, OMITTED_FORMAT, omittedCount);
        if (summary.length() >= MAX_AGGREGATE_CHARS) {
            target.setLength(0);
            return summary.substring(0, MAX_AGGREGATE_CHARS);
        }
        if (target.length() > MAX_AGGREGATE_CHARS - summary.length()) {
            target.setLength(MAX_AGGREGATE_CHARS - summary.length());
        }
        target.append(summary);
        return target.toString();
    }

    private static String normalizeAndBound(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }

        StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_FIELD_CHARS));
        boolean pendingSpace = false;
        boolean truncated = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isWhitespace(current) || Character.isISOControl(current)) {
                pendingSpace = safe.length() > 0;
                continue;
            }

            if (pendingSpace) {
                if (safe.length() >= MAX_FIELD_CHARS - FIELD_TRUNCATION_MARKER.length()) {
                    truncated = true;
                    break;
                }
                safe.append(' ');
                pendingSpace = false;
            }
            if (safe.length() >= MAX_FIELD_CHARS - FIELD_TRUNCATION_MARKER.length()) {
                truncated = true;
                break;
            }
            safe.append(current);
        }

        if (truncated) {
            safe.setLength(Math.min(
                    safe.length(), MAX_FIELD_CHARS - FIELD_TRUNCATION_MARKER.length()));
            safe.append(FIELD_TRUNCATION_MARKER);
        }
        return safe.toString();
    }

    private static String boundTrustedLabel(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= MAX_FIELD_CHARS) {
            return value;
        }
        return value.substring(0, MAX_FIELD_CHARS - FIELD_TRUNCATION_MARKER.length())
                + FIELD_TRUNCATION_MARKER;
    }

    /** Immutable sanitized error fields. */
    public static final class SanitizedError {
        private final String objectName;
        private final String message;

        private SanitizedError(String objectName, String message) {
            this.objectName = objectName;
            this.message = message;
        }

        public String getObjectName() {
            return objectName;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Bounded in-memory collection for structured diagnostics. */
    public static final class Collector {
        private final ArrayList<SanitizedError> records = new ArrayList<>();
        private int aggregateChars;
        private int omittedCount;

        public boolean add(@Nullable String rawObject, @Nullable String rawMessage) {
            return add(sanitize(rawObject, rawMessage));
        }

        public boolean add(@Nullable SanitizedError record) {
            if (record == null) {
                omittedCount++;
                return false;
            }
            if (records.size() >= MAX_RECORDS) {
                omittedCount++;
                return false;
            }

            int recordCost = record.getObjectName().length() + record.getMessage().length() + 2;
            if (recordCost > MAX_AGGREGATE_CHARS
                    || aggregateChars > MAX_AGGREGATE_CHARS - recordCost) {
                omittedCount++;
                return false;
            }

            records.add(record);
            aggregateChars += recordCost;
            return true;
        }

        public int getOmittedCount() {
            return omittedCount;
        }

        public int size() {
            return records.size();
        }

        public List<SanitizedError> getRecords() {
            return Collections.unmodifiableList(new ArrayList<>(records));
        }

        /** Format the retained records and include one bounded omission summary when needed. */
        public String format(@Nullable String offendingFileLabel) {
            StringBuilder result = new StringBuilder(Math.min(MAX_AGGREGATE_CHARS, aggregateChars));
            int outputOmitted = 0;
            for (SanitizedError record : records) {
                if (!appendRecord(result, offendingFileLabel, record)) {
                    outputOmitted++;
                }
            }
            return appendOmittedSummary(result, omittedCount + outputOmitted);
        }
    }
}
