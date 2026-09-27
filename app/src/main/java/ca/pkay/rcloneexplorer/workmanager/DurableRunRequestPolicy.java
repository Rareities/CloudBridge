package ca.pkay.rcloneexplorer.workmanager;

/** Validates the immutable task and owner identity carried by a sync work request. */
final class DurableRunRequestPolicy {
    private DurableRunRequestPolicy() {
    }

    static boolean hasCompleteIdentity(String runId, String ownerToken) {
        return runId != null && !runId.trim().isEmpty()
                && ownerToken != null && !ownerToken.trim().isEmpty();
    }

    static boolean isValidRequest(boolean hasPersistentTask, boolean hasEphemeralTask,
                                  String runId, String ownerToken) {
        return hasPersistentTask != hasEphemeralTask
                && hasCompleteIdentity(runId, ownerToken);
    }
}
