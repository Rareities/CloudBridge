package ca.pkay.rcloneexplorer.util;

/** Pure validation helpers for data consumed by the provider setup UI. */
public final class ProviderMetadataPolicy {
    private ProviderMetadataPolicy() {
    }

    /** Provider names must be strings containing at least one non-whitespace character. */
    public static boolean hasValidProviderName(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty();
    }
}
