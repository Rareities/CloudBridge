package ca.pkay.rcloneexplorer.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Native admission ends only when process exit is confirmed, including pipe/cancellation paths. */
public final class AdmittedProcess extends Process {
    private final Process delegate;
    private final Closeable admission;
    private boolean released;

    AdmittedProcess(Process delegate, Closeable admission) {
        this.delegate = delegate;
        this.admission = admission;
        Thread reaper = new Thread(() -> {
            boolean interrupted = false;
            while (true) {
                try { delegate.waitFor(); release(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }, "rclone-admission-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** Takes ownership of admission even when native startup fails. */
    public static Process start(String[] command, String[] environment, Closeable admission) throws IOException {
        try {
            return new AdmittedProcess(Runtime.getRuntime().exec(command, environment), admission);
        } catch (IOException | RuntimeException e) {
            try { admission.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    private synchronized void release() {
        if (!released) {
            released = true;
            try { admission.close(); }
            catch (IOException e) { throw new IllegalStateException("Cannot release native admission", e); }
        }
    }

    @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
    @Override public InputStream getInputStream() { return delegate.getInputStream(); }
    @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
    @Override public int waitFor() throws InterruptedException {
        int code = delegate.waitFor();
        release();
        return code;
    }
    @Override public int exitValue() {
        int code = delegate.exitValue();
        release();
        return code;
    }
    @Override public void destroy() { delegate.destroy(); }

    /** Forces the owned child where supported; admission remains held until verified exit. */
    @Override public Process destroyForcibly() {
        try {
            Process.class.getMethod("destroyForcibly").invoke(delegate);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            delegate.destroy();
        }
        return this;
    }
}
