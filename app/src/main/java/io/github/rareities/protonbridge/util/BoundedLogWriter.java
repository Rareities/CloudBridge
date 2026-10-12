package io.github.rareities.protonbridge.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Batches bounded diagnostic records on one demand-created thread, with no idle polling. */
public final class BoundedLogWriter {
    private static final int MAX_RECORD_CHARS = 8192;
    private static final int MAX_PENDING = 128;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private Thread writer;
    private int dropped;

    private static final class Entry {
        final File file;
        final byte[] bytes;
        Entry(File file, byte[] bytes) { this.file = file; this.bytes = bytes; }
    }

    public synchronized void append(File file, String message) {
        String bounded = DiagnosticRedactor.redact(message);
        if (bounded.length() > MAX_RECORD_CHARS) bounded = bounded.substring(0, MAX_RECORD_CHARS) + " [truncated]";
        byte[] bytes = (bounded + "\n").getBytes(StandardCharsets.UTF_8);
        if (pending.size() == MAX_PENDING) { pending.removeFirst(); dropped++; }
        pending.addLast(new Entry(file, bytes));
        if (writer == null) {
            writer = new Thread(this::drain, "protonbridge-diagnostics");
            writer.setDaemon(true);
            writer.start();
        }
    }

    private void drain() {
        for (;;) {
            List<Entry> batch = new ArrayList<>(32);
            int omitted;
            synchronized (this) {
                if (pending.isEmpty()) { writer = null; notifyAll(); return; }
                while (!pending.isEmpty() && batch.size() < 32) batch.add(pending.removeFirst());
                omitted = dropped;
                dropped = 0;
            }
            FileOutputStream stream = null;
            File current = null;
            long written = 0;
            try {
                for (Entry entry : batch) {
                    if (!entry.file.equals(current) || written + entry.bytes.length > MAX_FILE_BYTES) {
                        if (stream != null) { stream.close(); stream = null; }
                        current = null;
                        File parent = entry.file.getParentFile();
                        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) continue;
                        if (entry.file.length() + entry.bytes.length > MAX_FILE_BYTES) {
                            File previous = new File(parent, entry.file.getName() + ".previous");
                            if (previous.exists() && !previous.delete()) continue;
                            if (entry.file.exists() && !entry.file.renameTo(previous)) continue;
                        }
                        current = entry.file;
                        written = current.length();
                        stream = new FileOutputStream(current, true);
                    }
                    if (omitted > 0) {
                        byte[] marker = ("[" + omitted + " diagnostic records omitted under load]\n").getBytes(StandardCharsets.UTF_8);
                        stream.write(marker); written += marker.length; omitted = 0;
                    }
                    stream.write(entry.bytes); written += entry.bytes.length;
                }
            } catch (IOException | SecurityException ignored) {
                // Diagnostics must not recursively log failures or block a transfer.
            } finally {
                if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** A bounded test/support barrier, never used to poll or hold up UI work. */
    public synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (writer != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    synchronized int pendingCount() { return pending.size(); }
}
