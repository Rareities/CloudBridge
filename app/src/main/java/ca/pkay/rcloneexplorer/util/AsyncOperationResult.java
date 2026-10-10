package ca.pkay.rcloneexplorer.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Separates completion from successful completion; timeout never means success. */
public final class AsyncOperationResult {
    public enum Outcome { SUCCESS, FAILED, TIMED_OUT }
    private final CountDownLatch finished = new CountDownLatch(1);
    private final long timeoutMillis;
    private volatile Outcome outcome;
    public AsyncOperationResult(long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative timeout");
        this.timeoutMillis = timeoutMillis;
    }
    public synchronized void complete(boolean success) {
        if (outcome != null) return;
        outcome = success ? Outcome.SUCCESS : Outcome.FAILED;
        finished.countDown();
    }
    public Outcome await() throws InterruptedException {
        return finished.await(timeoutMillis, TimeUnit.MILLISECONDS) ? outcome : Outcome.TIMED_OUT;
    }
}
