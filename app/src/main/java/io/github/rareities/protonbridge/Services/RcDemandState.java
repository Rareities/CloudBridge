package io.github.rareities.protonbridge.Services;

import java.util.concurrent.atomic.AtomicBoolean;

/** Counts document pipe leases independently of the daemon's active RC calls/jobs. */
public final class RcDemandState {
    private int streams;

    public synchronized Lease openStream() {
        streams++;
        return new Lease();
    }

    public synchronized int streamCount() { return streams; }

    public synchronized boolean isBusy(boolean rcActive) { return rcActive || streams > 0; }

    public final class Lease implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            synchronized (RcDemandState.this) { streams--; }
        }
    }
}
