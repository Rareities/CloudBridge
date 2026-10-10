package ca.pkay.rcloneexplorer.util;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Monotonic, reference-counted demand; idle decisions cannot terminate a held lease. */
public final class DemandTracker {
    private long lastUseNanos = System.nanoTime();
    private int count;
    public synchronized void touch(long nowNanos) { lastUseNanos = nowNanos; }
    public synchronized boolean isIdle(long nowNanos, long graceNanos) {
        return count == 0 && nowNanos - lastUseNanos >= graceNanos;
    }
    public synchronized boolean hasDemand() { return count > 0; }
    public synchronized Closeable acquire() {
        count++;
        lastUseNanos = System.nanoTime();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                synchronized (DemandTracker.this) {
                    count--;
                    lastUseNanos = System.nanoTime();
                }
            }
        };
    }
}
