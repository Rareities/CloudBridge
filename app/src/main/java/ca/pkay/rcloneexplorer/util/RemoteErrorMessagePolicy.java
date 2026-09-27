package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

/** Builds a short, single-line error message safe to return across the document-provider IPC boundary. */
public final class RemoteErrorMessagePolicy {

    public static final int MAX_MESSAGE_CHARS = 512;
    private static final String GENERIC_ERROR = "Remote operation failed";
    private static final String TRUNCATION_MARKER = "…";

    private RemoteErrorMessagePolicy() {
    }

    public static String forUser(@Nullable String rawMessage) {
        String redacted = LogRedactor.redact(rawMessage);
        if (redacted == null || redacted.trim().isEmpty()) {
            return GENERIC_ERROR;
        }

        StringBuilder safe = new StringBuilder(Math.min(redacted.length(), MAX_MESSAGE_CHARS));
        boolean pendingSpace = false;
        boolean truncated = false;
        for (int i = 0; i < redacted.length(); i++) {
            char value = redacted.charAt(i);
            if (Character.isISOControl(value) || Character.isWhitespace(value)) {
                pendingSpace = safe.length() > 0;
                continue;
            }
            if (safe.length() == MAX_MESSAGE_CHARS) {
                truncated = true;
                break;
            }
            if (pendingSpace) {
                safe.append(' ');
                pendingSpace = false;
                if (safe.length() == MAX_MESSAGE_CHARS) {
                    truncated = i + 1 < redacted.length();
                    break;
                }
            }
            safe.append(value);
        }

        if (safe.length() == 0) {
            return GENERIC_ERROR;
        }
        if (truncated) {
            safe.setLength(Math.min(safe.length(), MAX_MESSAGE_CHARS - TRUNCATION_MARKER.length()));
            safe.append(TRUNCATION_MARKER);
        }
        return safe.toString();
    }
}
