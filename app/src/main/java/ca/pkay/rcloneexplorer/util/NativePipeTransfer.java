package ca.pkay.rcloneexplorer.util;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns a streaming rclone child from creation until its actual process exit.
 * Callers must consume download input (or close upload output) before awaiting
 * success, and awaitSuccessfulExit() must run off the UI thread.
 */
public final class NativePipeTransfer {
    private static final int MAX_DIAGNOSTIC_BYTES = 8192;
    private final Process child;
    private final boolean upload;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean streamFailed = new AtomicBoolean();
    private final CountDownLatch childExited = new CountDownLatch(1);
    private final Object callbackLock = new Object();
    private final List<Runnable> callbacks = new ArrayList<>();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final Thread stderrPump;
    private final Thread stdoutPump;
    private final InputStream input;
    private final OutputStream output;
    private volatile boolean exited;
    private volatile int exitCode;

    public NativePipeTransfer(Process child, boolean upload) {
        if (child == null) throw new IllegalArgumentException("A native process is required");
        this.child = child;
        this.upload = upload;
        this.input = upload ? null : new FilterInputStream(child.getInputStream()) {
            private boolean eof;
            private boolean closed;

            @Override public int read() throws IOException {
                try {
                    int value = super.read();
                    if (value == -1) eof = true;
                    return value;
                } catch (IOException failure) {
                    streamFailed.set(true);
                    throw failure;
                }
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    int count = in.read(bytes, offset, length);
                    if (count == -1) eof = true;
                    return count;
                } catch (IOException failure) {
                    streamFailed.set(true);
                    throw failure;
                }
            }

            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try { super.close(); }
                catch (IOException failure) { streamFailed.set(true); throw failure; }
                finally {
                    if (!eof) {
                        streamFailed.set(true);
                        cancel();
                    }
                }
            }
        };
        this.output = upload ? new FilterOutputStream(child.getOutputStream()) {
            @Override public void write(int value) throws IOException {
                try { out.write(value); }
                catch (IOException failure) { streamFailed.set(true); throw failure; }
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                try { out.write(bytes, offset, length); }
                catch (IOException failure) { streamFailed.set(true); throw failure; }
            }

            @Override public void flush() throws IOException {
                try { out.flush(); }
                catch (IOException failure) { streamFailed.set(true); throw failure; }
            }

            @Override public void close() throws IOException {
                try { out.close(); }
                catch (IOException failure) { streamFailed.set(true); throw failure; }
            }
        } : null;

        stderrPump = pump(child.getErrorStream(), true, "rclone-pipe-stderr");
        stdoutPump = upload ? pump(child.getInputStream(), false, "rclone-pipe-stdout") : null;
        if (!upload) {
            try { child.getOutputStream().close(); }
            catch (IOException ignored) { /* cat does not consume stdin. */ }
        }
        Thread watcher = new Thread(this::watchExit, "rclone-pipe-exit");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Streaming rclone cat output. The caller must read until EOF for success. */
    public InputStream input() {
        if (upload) throw new IllegalStateException("Upload has no download input");
        return input;
    }

    /** Streaming rclone rcat input. Close this to tell rcat to finish its upload. */
    public OutputStream output() {
        if (!upload) throw new IllegalStateException("Download has no upload output");
        return output;
    }

    /**
     * Invoked exactly once per registration once the OS reports that the child
     * has exited. Late registrations are invoked immediately.
     */
    public void onExit(Runnable callback) {
        if (callback == null) throw new IllegalArgumentException("Callback is required");
        synchronized (callbackLock) {
            if (!exited) {
                callbacks.add(callback);
                return;
            }
        }
        runCallback(callback);
    }

    /** Request termination, with a bounded escalation for an unresponsive child. */
    public void cancel() {
        if (exited) return;
        try { child.exitValue(); return; }
        catch (IllegalThreadStateException running) { /* Actual child is still live. */ }
        if (!cancelled.compareAndSet(false, true)) return;
        child.destroy();
        closeQuietly(child.getOutputStream());
        closeQuietly(child.getInputStream());
        closeQuietly(child.getErrorStream());
        Thread escalation = new Thread(() -> {
            try { Thread.sleep(2000L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            try { child.exitValue(); return; }
            catch (IllegalThreadStateException stillRunning) {
                try { Process.class.getMethod("destroyForcibly").invoke(child); }
                catch (ReflectiveOperationException unavailable) { child.destroy(); }
            }
        }, "rclone-pipe-cancel");
        escalation.setDaemon(true);
        escalation.start();
    }

    /** Checks actual exit status, stream errors and cancellation. Call off the UI thread. */
    public void awaitSuccessfulExit() throws IOException {
        try { childExited.await(); }
        catch (InterruptedException interrupted) {
            cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for native transfer", interrupted);
        }
        joinPump(stderrPump);
        if (stdoutPump != null) joinPump(stdoutPump);
        if (cancelled.get()) throw new IOException("Native transfer cancelled");
        if (streamFailed.get()) throw new IOException("Native transfer stream failed" + diagnosticSuffix());
        if (exitCode != 0) throw new IOException("Native transfer exited with code " + exitCode + diagnosticSuffix());
    }

    private void watchExit() {
        int code;
        boolean interrupted = false;
        for (;;) {
            try { code = child.waitFor(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        List<Runnable> completed;
        synchronized (callbackLock) {
            exitCode = code;
            exited = true;
            completed = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        childExited.countDown();
        for (Runnable callback : completed) runCallback(callback);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Thread pump(InputStream source, boolean diagnostic, String name) {
        Thread thread = new Thread(() -> {
            try (InputStream input = source) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) {
                    if (diagnostic) {
                        synchronized (stderr) {
                            int available = MAX_DIAGNOSTIC_BYTES - stderr.size();
                            if (available > 0) stderr.write(bytes, 0, Math.min(count, available));
                        }
                    }
                }
            } catch (IOException failure) {
                if (!cancelled.get()) streamFailed.set(true);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void joinPump(Thread thread) throws IOException {
        try { thread.join(5000L); }
        catch (InterruptedException interrupted) {
            cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking native transfer output", interrupted);
        }
        if (thread.isAlive()) streamFailed.set(true);
    }

    private String diagnosticSuffix() {
        String raw;
        synchronized (stderr) {
            raw = new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim();
        }
        String safe = DiagnosticRedactor.redact(raw);
        return safe.isEmpty() ? "" : ": " + safe;
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static void runCallback(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException ignored) { /* One broken observer cannot hide process exit. */ }
    }
}
