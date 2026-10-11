package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns both pipes and the process exit result. Log messages never determine success. */
public final class ProcessRunner {
    public interface LineHandler { void accept(String line); }
    public interface StopCheck { boolean stopped(); }

    public static final class Outcome {
        public final Integer exitCode;
        public final boolean cancelled;
        public final boolean streamFailure;
        public final boolean forced;
        public final boolean exited;
        public final boolean timedOut;
        Outcome(Integer exitCode, boolean cancelled, boolean streamFailure, boolean forced, boolean exited, boolean timedOut) {
            this.exitCode = exitCode;
            this.cancelled = cancelled;
            this.streamFailure = streamFailure;
            this.forced = forced;
            this.exited = exited;
            this.timedOut = timedOut;
        }
        public boolean success() { return exited && exitCode != null && exitCode == 0 && !cancelled && !streamFailure; }
    }

    private final AtomicBoolean cancel = new AtomicBoolean();
    private final AtomicBoolean signalled = new AtomicBoolean();
    private volatile Process process;

    public void requestStop() {
        cancel.set(true);
        Process child = process;
        if (child != null && signalled.compareAndSet(false, true)) signal(child, false);
    }

    public Process child() { return process; }

    public Outcome run(Process child, StopCheck stop, LineHandler stdout, LineHandler stderr) {
        return run(child, stop, stdout, stderr, 0L);
    }

    /** Bounds local checks only; zero leaves transfers without an overall deadline. */
    public Outcome run(Process child, StopCheck stop, LineHandler stdout, LineHandler stderr, long timeoutMillis) {
        if (child == null) return new Outcome(null, cancel.get(), false, false, true, false);
        long started = System.nanoTime();
        boolean timedOut = false;
        process = child;
        AtomicBoolean streamFailure = new AtomicBoolean();
        Thread out = drain(child.getInputStream(), stdout, streamFailure, "rclone-stdout");
        Thread err = drain(child.getErrorStream(), stderr, streamFailure, "rclone-stderr");
        try { child.getOutputStream().close(); } catch (IOException ignored) { }
        Integer exit = null;
        boolean forced = false;
        boolean interrupted = false;
        long cancelStarted = 0L;
        while (exit == null) {
            if (timeoutMillis > 0 && (System.nanoTime() - started) / 1_000_000L >= timeoutMillis) timedOut = true;
            if (timedOut || stop.stopped() || cancel.get() || Thread.currentThread().isInterrupted()) {
                if (Thread.interrupted()) interrupted = true;
                requestStop();
                if (cancelStarted == 0L) cancelStarted = System.nanoTime();
                long elapsed = (System.nanoTime() - cancelStarted) / 1_000_000L;
                if (elapsed >= 10_000L && !forced) {
                    forced = true;
                    // Available on recent Android releases. A second signal is also
                    // understood by rclone when the older Process API lacks this method.
                    signal(child, true);
                }
                if (elapsed >= 15_000L) break;
            }
            try { exit = child.exitValue(); }
            catch (IllegalThreadStateException running) {
                try { Thread.sleep(100L); }
                catch (InterruptedException e) { interrupted = true; requestStop(); }
            }
        }
        if (exit != null) {
            try { out.join(5000L); err.join(5000L); }
            catch (InterruptedException e) { interrupted = true; requestStop(); }
            if (out.isAlive() || err.isAlive()) streamFailure.set(true);
        }
        if (interrupted) Thread.currentThread().interrupt();
        return new Outcome(exit, cancel.get() || stop.stopped(), streamFailure.get(), forced, exit != null, timedOut);
    }

    private static Thread drain(InputStream input, LineHandler handler, AtomicBoolean failed, String name) {
        Thread thread = new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                StringBuilder line = new StringBuilder();
                boolean truncated = false;
                int count;
                while ((count = reader.read(chunk)) != -1) {
                    for (int i = 0; i < count; i++) {
                        char c = chunk[i];
                        if (c == '\n') {
                            deliver(handler, truncated ? "[oversized diagnostic omitted]" : line.toString(), failed);
                            line.setLength(0);
                            truncated = false;
                        } else if (c != '\r') {
                            if (line.length() < 65536) line.append(c); else truncated = true;
                        }
                    }
                }
                if (line.length() > 0) deliver(handler, truncated ? "[oversized diagnostic omitted]" : line.toString(), failed);
            } catch (IOException e) { failed.set(true); }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void signal(Process child, boolean force) {
        Thread signal = new Thread(() -> {
            if (force) {
                try { Process.class.getMethod("destroyForcibly").invoke(child); return; }
                catch (ReflectiveOperationException ignored) { }
            }
            child.destroy();
        }, force ? "rclone-force-stop" : "rclone-request-stop");
        signal.setDaemon(true);
        signal.start();
    }

    private static void deliver(LineHandler handler, String line, AtomicBoolean failed) {
        if (handler == null) return;
        try { handler.accept(line); }
        catch (RuntimeException e) { failed.set(true); }
    }
}
