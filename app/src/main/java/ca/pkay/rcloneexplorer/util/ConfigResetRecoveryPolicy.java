package ca.pkay.rcloneexplorer.util;

/** Fail-closed recovery rules for config reset failures. */
public final class ConfigResetRecoveryPolicy {
    public interface RecoveryAction {
        boolean run();
    }

    private ConfigResetRecoveryPolicy() {
    }

    /**
     * Restores saved credentials and releases the revision barrier only while the old config
     * is positively known to remain active and its AtomicFile backup is absent.
     */
    public static boolean restoreIfConfigUnchanged(
            boolean configFileExists,
            boolean atomicBackupExists,
            boolean invalidationTokenAvailable,
            RecoveryAction restoreInvalidation,
            RecoveryAction finishRevisionMutation) {
        if (!configFileExists || atomicBackupExists || !invalidationTokenAvailable) {
            return false;
        }
        if (!restoreInvalidation.run()) {
            return false;
        }
        return finishRevisionMutation.run();
    }
}
