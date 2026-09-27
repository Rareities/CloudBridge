package ca.pkay.rcloneexplorer.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks whether a synchronous SAF operation has a confirmed terminal RCD result. */
public final class VcpJobCompletion {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    private final CountDownLatch completed = new CountDownLatch(1);
    private final AtomicBoolean accepted = new AtomicBoolean();
    private volatile Outcome outcome = Outcome.UNKNOWN;

    /** Grant revocation is permitted only after a terminal response explicitly reports success. */
    public static boolean isConfirmedSuccess(boolean finished, boolean success) {
        return finished && success;
    }

    /** Records the first callback. A missing or non-terminal response remains unknown. */
    public void complete(boolean finished, boolean success) {
        if (!accepted.compareAndSet(false, true)) {
            return;
        }
        outcome = !finished ? Outcome.UNKNOWN
                : isConfirmedSuccess(finished, success) ? Outcome.SUCCEEDED : Outcome.FAILED;
        completed.countDown();
    }

    /**
     * Waits for the terminal callback. Timeout or interruption is not evidence of failure
     * or success; a later callback may still arrive because RCD jobs are not cancelled here.
     */
    public Outcome await(long timeoutMillis) {
        try {
            if (!completed.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS)) {
                return Outcome.UNKNOWN;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.UNKNOWN;
        }
        return outcome;
    }
}
