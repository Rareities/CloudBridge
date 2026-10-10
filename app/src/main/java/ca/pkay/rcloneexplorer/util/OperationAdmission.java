package ca.pkay.rcloneexplorer.util;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exclusive sidecar ownership retained until the parent and every child process finish. */
public final class OperationAdmission {
    private OperationAdmission() {}
    private static final ThreadLocal<Lease> CONTEXT = new ThreadLocal<>();

    /** Opens a parent scope. Nested parent scopes are rejected; children use processLease. */
    public static Lease acquire(File sidecar) throws IOException {
        if (activeContext() != null) throw new IOException("An operation already owns this thread's admission scope");
        Lease parent = new Lease(open(sidecar), true);
        CONTEXT.set(parent);
        return parent;
    }

    /** Retains the current parent, or independently owns admission for an unscoped process. */
    public static Lease processLease(File sidecar) throws IOException {
        File identity = sidecar.getCanonicalFile();
        Lease parent = activeContext();
        if (parent != null) {
            if (!parent.group.identity.equals(identity)) throw new IOException("Admission sidecar differs from parent scope");
            synchronized (parent.group) {
                if (!parent.closed.get() && !parent.detached.get()) {
                    parent.group.references++;
                    return new Lease(parent.group, false);
                }
            }
        }
        return new Lease(open(identity), false);
    }

    private static Lease activeContext() {
        Lease parent = CONTEXT.get();
        if (parent != null && (parent.closed.get() || parent.detached.get())) {
            CONTEXT.remove();
            return null;
        }
        return parent;
    }

    private static Group open(File sidecar) throws IOException {
        File identity = sidecar.getCanonicalFile();
        File directory = identity.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create admission directory");
        }
        RandomAccessFile file = new RandomAccessFile(identity, "rw");
        try {
            FileLock lock = file.getChannel().tryLock();
            if (lock == null) throw new IOException("Another operation owns admission");
            return new Group(identity, file, lock);
        } catch (OverlappingFileLockException e) {
            file.close();
            throw new IOException("Another operation owns admission", e);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private static final class Group {
        final File identity;
        final RandomAccessFile file;
        final FileLock lock;
        int references = 1;
        Group(File identity, RandomAccessFile file, FileLock lock) {
            this.identity = identity; this.file = file; this.lock = lock;
        }
        synchronized void release() throws IOException {
            if (--references == 0) {
                try { lock.release(); } finally { file.close(); }
            }
        }
    }

    public static final class Lease implements Closeable {
        private final Group group;
        private final boolean parent;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean detached = new AtomicBoolean();
        private Lease(Group group, boolean parent) { this.group = group; this.parent = parent; }

        /** Removes inheritance for future launches while preserving this lease's ownership. */
        public void detachContext() {
            if (!parent) return;
            detached.set(true);
            // A cross-thread handoff cannot remove another thread's ThreadLocal. activeContext
            // clears the detached marker lazily there; it can never retain this scope again.
            if (CONTEXT.get() == this) CONTEXT.remove();
        }
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            detachContext();
            group.release();
        }
    }
}
