package ca.pkay.rcloneexplorer.util;

import java.util.UUID;

/** Pure validation for the opaque revision attached to a remote-list snapshot. */
public final class ConfigRevisionPolicy {
    private ConfigRevisionPolicy() {
    }

    public static boolean isValidRevision(String revision) {
        if (revision == null) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(revision);
            return parsed.toString().equalsIgnoreCase(revision);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    public static boolean matches(String expected, String current) {
        return isValidRevision(expected) && isValidRevision(current) && expected.equals(current);
    }
}
