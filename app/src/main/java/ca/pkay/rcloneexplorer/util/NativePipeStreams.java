package ca.pkay.rcloneexplorer.util;

import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-backed streams with concurrent pipe draining and checked terminal outcomes. */
public final class NativePipeStreams {
    private NativePipeStreams() {}
    private static final long EXIT_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static void discard(InputStream stream) {
        Thread reader = new Thread(() -> {
            try (InputStream input = stream) {
                byte[] bytes = new byte[8192];
                while (input.read(bytes) != -1) { }
            } catch (IOException ignored) { }
        }, "rclone-pipe-drain");
        reader.setDaemon(true);
        reader.start();
    }
    private static int await(Process process, AtomicBoolean aborted) throws IOException {
        long deadline = System.nanoTime() + EXIT_TIMEOUT_NANOS;
        while (true) {
            if (aborted.get()) throw new IOException("Transfer cancelled");
            try { return process.exitValue(); }
            catch (IllegalThreadStateException running) {
                if (System.nanoTime() - deadline >= 0) {
                    process.destroy();
                    throw new IOException("Transfer completion timed out");
                }
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    process.destroy();
                    Thread.currentThread().interrupt();
                    throw new IOException("Transfer interrupted", interrupted);
                }
            }
        }
    }
    private static void abortUpload(Process process, OutputStream input) {
        // Never supply EOF to a still-running rcat during cancellation: that can finalize partial data.
        try { Process.class.getMethod("destroyForcibly").invoke(process); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { process.destroy(); }
        Thread cleanup = new Thread(() -> {
            boolean interrupted = false;
            while (true) {
                try { process.waitFor(); break; }
                catch (InterruptedException retry) { interrupted = true; }
            }
            closeQuietly(input);
            if (interrupted) Thread.currentThread().interrupt();
        }, "rclone-upload-abort");
        cleanup.setDaemon(true);
        cleanup.start();
    }
    private static void closeQuietly(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }
    public static InputStream download(Process process) {
        discard(process.getErrorStream());
        closeQuietly(process.getOutputStream());
        return new Download(process);
    }
    public static OutputStream upload(Process process) {
        discard(process.getErrorStream());
        discard(process.getInputStream());
        return new Upload(process);
    }
    private static final class Download extends InputStream implements TransferControl {
        private final Process process;
        private final InputStream input;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private boolean eof;
        Download(Process process) { this.process = process; input = process.getInputStream(); }
        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (closed.get() || aborted.get()) throw new IOException("Transfer stream closed");
            int count = input.read(bytes, offset, length);
            if (count == -1 && !eof) {
                if (await(process, aborted) != 0) throw new IOException("Remote download failed");
                eof = true;
            }
            return count;
        }
        @Override public void abort() { aborted.set(true); process.destroy(); closeQuietly(input); }
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            if (!eof) abort();
            input.close();
        }
    }
    private static final class Upload extends OutputStream implements TransferControl {
        private final Process process;
        private final OutputStream output;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        Upload(Process process) { this.process = process; output = process.getOutputStream(); }
        @Override public void write(int value) throws IOException {
            if (closed.get() || aborted.get()) throw new IOException("Transfer stream closed");
            output.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed.get() || aborted.get()) throw new IOException("Transfer stream closed");
            output.write(bytes, offset, length);
        }
        @Override public void flush() throws IOException { output.flush(); }
        @Override public void abort() {
            if (aborted.compareAndSet(false, true)) abortUpload(process, output);
        }
        @Override public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            if (aborted.get()) throw new IOException("Upload cancelled");
            try {
                output.close();
                if (await(process, aborted) != 0) throw new IOException("Remote upload failed");
            } catch (IOException failure) {
                abort();
                throw failure;
            }
        }
    }
}
