package ca.pkay.rcloneexplorer.Services;

/** Pure retry gate for schedule-alarm reconciliation after exact-alarm permission changes. */
public final class TriggerPermissionRecoveryPolicy {
    private TriggerPermissionRecoveryPolicy() {}

    public static boolean shouldAttempt(boolean reconciliationPending, boolean permissionGranted) {
        return reconciliationPending && permissionGranted;
    }

    public static boolean canClearPending(boolean permissionGranted, boolean allTriggersReconciled) {
        return permissionGranted && allTriggersReconciled;
    }
}
