package ca.pkay.rcloneexplorer.workmanager;

import java.util.Locale;

/** Classifies native probe output without retaining or exposing provider text. */
public final class SessionProbeFailureClassifier {
    public enum FailureCategory { NONE, NETWORK, AUTHENTICATION, RATE_LIMITED, INTEGRITY, OTHER }

    private SessionProbeFailureClassifier() {}

    public static FailureCategory classifyLine(String text) {
        if (text == null || text.isEmpty()) return FailureCategory.OTHER;
        String lower = text.toLowerCase(Locale.ROOT);
        if (looksLikeNetworkError(lower)) return FailureCategory.NETWORK;
        if (lower.contains("invalid_grant")
                || lower.contains("invalid token")
                || lower.contains("token expired")
                || lower.contains("token has expired")
                || lower.contains("authentication failed")
                || lower.contains("auth exceeded max retries")
                || lower.contains("unauthorized")) {
            return FailureCategory.AUTHENTICATION;
        }
        if (lower.contains("too many requests")
                || lower.contains("rate limit")
                || lower.contains("rate-limit")
                || lower.matches(".*\\b429\\b.*")) {
            return FailureCategory.RATE_LIMITED;
        }
        if (lower.contains("checksum mismatch")
                || lower.contains("hash mismatch")
                || lower.contains("integrity check")
                || lower.contains("corrupt data")
                || lower.contains("data corruption")) {
            return FailureCategory.INTEGRITY;
        }
        return FailureCategory.OTHER;
    }

    private static boolean looksLikeNetworkError(String lower) {
        return lower.contains("dial tcp")
                || lower.contains("connection refused")
                || lower.contains("no such host")
                || lower.contains("i/o timeout")
                || lower.contains("network is unreachable")
                || lower.contains("tls handshake timeout")
                || lower.contains("dns")
                || lower.contains("lookup")
                || lower.contains("no address associated");
    }
}
