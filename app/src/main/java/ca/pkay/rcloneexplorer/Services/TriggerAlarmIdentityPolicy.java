package ca.pkay.rcloneexplorer.Services;

/**
 * Provides a lossless PendingIntent identity for long-lived trigger alarms.
 * PendingIntent request codes are only 32-bit; the trigger ID is a SQLite long.
 */
final class TriggerAlarmIdentityPolicy {
    private TriggerAlarmIdentityPolicy() {
    }

    static String dataUri(long triggerId) {
        return "cloudbridge://trigger/" + triggerId;
    }
}
