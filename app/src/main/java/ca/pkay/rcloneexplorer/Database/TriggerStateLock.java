package ca.pkay.rcloneexplorer.Database;

/** Serializes trigger edits with alarm reconciliation and the final scheduled-run admission check. */
public final class TriggerStateLock {
    public static final Object MONITOR = new Object();

    private TriggerStateLock() {}
}
