package ca.pkay.rcloneexplorer.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates native configuration users without holding a thread-owned lock across a child. */
public final class NativeSessionGate {
    public static final NativeSessionGate GLOBAL = new NativeSessionGate();
    private boolean commandActive;
    private int rcUsers;
    private long generation;

    public synchronized long generation() { return generation; }
    public synchronized boolean isBusy() { return commandActive || rcUsers > 0; }

    public synchronized Lease acquireCommand() throws IOException {
        if (commandActive || rcUsers != 0)
            throw new IOException("Another native operation is active. Retry when it finishes.");
        commandActive = true;
        generation++;
        return new Lease(false);
    }

    public synchronized Lease acquireRc(long expectedGeneration) throws IOException {
        if (commandActive || expectedGeneration != generation)
            throw new IOException("The remote service configuration changed or another transfer is active. Reopen the folder and retry.");
        rcUsers++;
        return new Lease(true);
    }

    public synchronized boolean isCurrent(long expectedGeneration) {
        return !commandActive && expectedGeneration == generation;
    }

    public final class Lease implements AutoCloseable {
        private final boolean rc;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(boolean rc) { this.rc = rc; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (NativeSessionGate.this) {
                if (rc) rcUsers--; else commandActive = false;
            }
        }

        /** A stop request is not proof of exit; ownership lasts until waitFor succeeds. */
        public void closeAfterExit(Process process) {
            if (process == null) { close(); return; }
            try { process.exitValue(); close(); return; }
            catch (IllegalThreadStateException running) { }
            Thread waiter = new Thread(() -> {
                boolean interrupted = false;
                for (;;) {
                    try { process.waitFor(); break; }
                    catch (InterruptedException retry) { interrupted = true; }
                }
                close();
                if (interrupted) Thread.currentThread().interrupt();
            }, "rclone-session-release");
            waiter.setDaemon(true);
            waiter.start();
        }
    }

    /** These local metadata commands do not construct cloud filesystem sessions. */
    public static boolean isLocalMetadata(String[] command) {
        int n = command.length;
        if (n < 2) return false;
        String last = command[n - 1];
        if (last.equals("--help") || last.equals("--version")) return true;
        int verb = verbIndex(command);
        if (verb == n - 1 && (last.equals("version") || last.equals("listremotes"))) return true;
        return verb == n - 2 && command[verb].equals("config") &&
                (last.equals("dump") || last.equals("providers"));
    }

    public static boolean isDaemon(String[] command) {
        return command.length > 1 && verbIndex(command) == command.length - 1 &&
                command[command.length - 1].equals("rcd");
    }

    private static int verbIndex(String[] command) {
        int n = command.length;
        int verb = 1;
        while (verb < n) {
            String token = command[verb];
            if (token.equals("--config") || token.equals("--cache-chunk-path") || token.equals("--cache-db-path") ||
                    token.equals("--rc-addr") || token.equals("--transfers") || token.equals("--buffer-size") ||
                    token.equals("--multi-thread-streams") || token.equals("--log-level")) {
                verb += 2;
            } else if ((token.startsWith("--") && token.contains("=")) || token.matches("-v{1,3}") || token.equals("--rc-serve")) {
                verb++;
            } else break;
        }
        return verb;
    }
}
