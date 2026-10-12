package io.github.rareities.protonbridge.util;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes configuration changes with validation and native launch. */
public final class ConfigurationExclusion {
    private ConfigurationExclusion() {}
    private static volatile boolean restoreRecoveryRequired;
    public static boolean isRestoreRecoveryRequired() { return restoreRecoveryRequired; }
    public static void requireRestoreRecovery() { restoreRecoveryRequired = true; }
    public static void restoreRecovered() { restoreRecoveryRequired = false; }
    public static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);

    /** Same-thread lease. Release after spawn; NativeSessionGate then owns the child lifetime. */
    public static Preparation prepare() throws IOException {
        if (!LOCK.writeLock().tryLock()) throw new IOException("Configuration is busy; retry after the current operation");
        if (restoreRecoveryRequired || NativeSessionGate.GLOBAL.isBusy()) {
            LOCK.writeLock().unlock();
            throw new IOException(restoreRecoveryRequired ? "Restore recovery is required before preparing this task" : "Finish active native operations before preparing this task");
        }
        return new Preparation();
    }

    public static final class Preparation implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private Preparation() {}
        @Override public void close() {
            if (closed.compareAndSet(false, true)) LOCK.writeLock().unlock();
        }
    }
}
