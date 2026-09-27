package ca.pkay.rcloneexplorer.workmanager;

/** Serializes the final notification decision for one ephemeral worker. */
public final class EphemeralTerminalNotificationPolicy {
    public enum Outcome {
        SUCCESS,
        CANCELLED,
        FAILURE,
        ALREADY_CLAIMED
    }

    private boolean cancellationRequested;
    private boolean finalNotificationClaimed;

    public synchronized void requestCancellation() {
        if (!finalNotificationClaimed) {
            cancellationRequested = true;
        }
    }

    /** Run a small state update atomically with respect to the terminal claim. */
    public synchronized boolean updateIfPending(Runnable update) {
        if (finalNotificationClaimed || cancellationRequested) {
            return false;
        }
        update.run();
        return true;
    }

    public synchronized Outcome claim(
            boolean failureReported,
            boolean cancellationReported,
            boolean nativeExitConfirmed,
            boolean nativeExitSucceeded) {
        if (finalNotificationClaimed) {
            return Outcome.ALREADY_CLAIMED;
        }

        finalNotificationClaimed = true;
        if (cancellationRequested || cancellationReported) {
            return Outcome.CANCELLED;
        }
        if (failureReported || !nativeExitConfirmed || !nativeExitSucceeded) {
            return Outcome.FAILURE;
        }
        return Outcome.SUCCESS;
    }

    public synchronized boolean isClaimed() {
        return finalNotificationClaimed;
    }
}
