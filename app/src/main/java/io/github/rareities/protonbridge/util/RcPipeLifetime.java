package io.github.rareities.protonbridge.util;

import java.util.concurrent.atomic.AtomicBoolean;

/** Release an admission only after both pipe I/O and native process exit. */
public final class RcPipeLifetime implements AutoCloseable {
    private final Runnable release;
    private final AtomicBoolean pipeFinished = new AtomicBoolean();
    private final AtomicBoolean processFinished = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();

    public RcPipeLifetime(Runnable release) { this.release = release; }

    public void pipeFinished() {
        pipeFinished.set(true);
        maybeRelease();
    }

    public void processFinished() {
        processFinished.set(true);
        maybeRelease();
    }

    private void maybeRelease() {
        if (pipeFinished.get() && processFinished.get()) close();
    }

    @Override public void close() {
        if (released.compareAndSet(false, true)) release.run();
    }
}
