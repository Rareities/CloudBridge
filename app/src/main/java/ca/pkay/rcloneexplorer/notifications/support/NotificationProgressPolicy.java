package ca.pkay.rcloneexplorer.notifications.support;

/** Bounds native transfer progress before it reaches Android notification APIs. */
public final class NotificationProgressPolicy {

    private NotificationProgressPolicy() {}

    /**
     * Returns a finite progress value in the range accepted by NotificationCompat.
     * Unknown/zero totals are deliberately reported as zero rather than guessed complete.
     */
    public static int percent(long bytes, long totalBytes) {
        if (bytes <= 0L || totalBytes <= 0L) {
            return 0;
        }
        if (bytes >= totalBytes) {
            return 100;
        }
        int result = (int) ((bytes * 100.0d) / totalBytes);
        return Math.max(0, Math.min(100, result));
    }
}
