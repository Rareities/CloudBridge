package ca.pkay.rcloneexplorer.Database.json;

/** Runs independent backup-import rollback steps even when an earlier store cannot be restored. */
public final class ImportRollbackCoordinator {

    @FunctionalInterface
    public interface RestoreAction {
        void restore() throws Exception;
    }

    private ImportRollbackCoordinator() {}

    /**
     * Restores stores in the supplied order and returns the first failure with later failures
     * attached as suppressed exceptions. A failed database restore must not skip preferences or
     * config recovery, since those stores have independent rollback operations.
     */
    public static Exception restoreAll(RestoreAction... actions) {
        if (actions == null) {
            return new IllegalArgumentException("Restore actions are missing");
        }

        Exception firstFailure = null;
        for (RestoreAction action : actions) {
            if (action == null) {
                firstFailure = addFailure(firstFailure,
                        new IllegalArgumentException("Restore action is missing"));
                continue;
            }
            try {
                action.restore();
            } catch (Exception failure) {
                firstFailure = addFailure(firstFailure, failure);
            }
        }
        return firstFailure;
    }

    /**
     * The pre-import config snapshot is disposable after a successful commit, when the config
     * was never replaced, or after both the config and matching secret were restored. Preserve
     * it whenever a changed config has an incomplete rollback.
     */
    public static boolean canDiscardConfigSnapshot(boolean importCommitted,
                                                   boolean configWasReplaced,
                                                   boolean configRestoreAttempted,
                                                   boolean configRestoreSucceeded,
                                                   boolean secretRestoreSucceeded) {
        return importCommitted || !configWasReplaced || (configRestoreAttempted
                && configRestoreSucceeded && secretRestoreSucceeded);
    }

    private static Exception addFailure(Exception firstFailure, Exception nextFailure) {
        if (firstFailure == null) {
            return nextFailure;
        }
        if (firstFailure != nextFailure) {
            firstFailure.addSuppressed(nextFailure);
        }
        return firstFailure;
    }
}
