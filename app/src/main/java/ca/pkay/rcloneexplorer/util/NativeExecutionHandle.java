package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the lifetime of one native rclone execution.
 *
 * <p>The handle is deliberately small and Android-independent apart from the nullable annotation:
 * it can be exercised by JVM tests with a fake {@link Process}.  Both process pipes are drained
 * concurrently, output is never retained without a bound, and attached resources are released
 * only after the process has been reaped.  A caller must treat {@link TerminalState#UNCONFIRMED}
 * as a conservative ownership failure and must not start a conflicting operation.</p>
 *
 * <p>{@link #adopt(Process, String)} is the compatibility bridge for the existing Rclone API,
 * whose operation methods still return a Process.  New callers should prefer
 * {@link #launch(String[], String[], String)} or an Rclone owned-operation adapter.</p>
 */
public final class NativeExecutionHandle implements AutoCloseable {

    public static final long NO_TIMEOUT = 0L;
    public static final long DEFAULT_TERMINATION_GRACE_MILLIS = 1500L;
    public static final int MAX_CALLBACK_LINE_CHARS = 64 * 1024;

    public enum TerminalState {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        INTERRUPTED,
        UNCONFIRMED
    }

    /** Receives bounded, line-oriented output while the process is running. */
    public interface LineSink {
        void onLine(String line);
    }

    public static final class Outcome {
        private final TerminalState state;
        private final Integer exitCode;
        private final boolean outputTruncated;

        private Outcome(TerminalState state, Integer exitCode, boolean outputTruncated) {
            this.state = state;
            this.exitCode = exitCode;
            this.outputTruncated = outputTruncated;
        }

        public TerminalState getState() {
            return state;
        }

        @Nullable
        public Integer getExitCode() {
            return exitCode;
        }

        public boolean isOutputTruncated() {
            return outputTruncated;
        }

        public boolean isConfirmed() {
            return state != TerminalState.UNCONFIRMED;
        }

        public boolean isSuccess() {
            return state == TerminalState.SUCCEEDED;
        }
    }

    private final Process process;
    private final String label;
    private final long terminationGraceMillis;
    private final AtomicBoolean terminalAccepted = new AtomicBoolean(false);
    private final AtomicBoolean resourcesReleased = new AtomicBoolean(false);
    private final AtomicBoolean pumpsStarted = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean timeoutRequested = new AtomicBoolean(false);
    private final CountDownLatch pumpsFinished = new CountDownLatch(2);
    private final CountDownLatch processReaped = new CountDownLatch(1);
    private final AtomicBoolean reaperStarted = new AtomicBoolean(false);
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Object resourceLock = new Object();
    private volatile boolean outputTruncated;
    private volatile Outcome outcome;
    private volatile Thread stdoutPump;
    private volatile Thread stderrPump;

    private NativeExecutionHandle(Process process, String label, long terminationGraceMillis) {
        this.process = process;
        this.label = label == null || label.trim().isEmpty() ? "native" : label;
        this.terminationGraceMillis = terminationGraceMillis > 0
                ? terminationGraceMillis : DEFAULT_TERMINATION_GRACE_MILLIS;
    }

    /** Adopts a process launched by a legacy Rclone method. */
    @Nullable
    public static NativeExecutionHandle adopt(@Nullable Process process, String label) {
        return adopt(process, label, DEFAULT_TERMINATION_GRACE_MILLIS);
    }

    /** Testable/embedded variant with a caller-selected bounded termination grace. */
    @Nullable
    public static NativeExecutionHandle adopt(@Nullable Process process, String label,
                                               long terminationGraceMillis) {
        return process == null ? null : new NativeExecutionHandle(process, label, terminationGraceMillis);
    }

    /** Launches a process and transfers launch, drain and cleanup ownership to the handle. */
    public static NativeExecutionHandle launch(String[] command, String[] environment, String label)
            throws IOException {
        Process process = Runtime.getRuntime().exec(command, environment);
        return new NativeExecutionHandle(process, label, DEFAULT_TERMINATION_GRACE_MILLIS);
    }

    /**
     * Attaches a resource such as a wake/Wi-Fi lock. It is closed only after confirmed reap.
     * Returns false when the process has already reached a terminal state; in that case the
     * caller remains responsible for the resource.
     */
    public boolean attachResource(@Nullable AutoCloseable resource) {
        if (resource == null) {
            return true;
        }
        synchronized (resourceLock) {
            if (terminalAccepted.get()) {
                return false;
            }
            resources.add(resource);
            return true;
        }
    }

    /** Requests termination without claiming that native work has already stopped. */
    public void cancel() {
        cancelRequested.set(true);
        destroyProcess(false);
    }

    /** Starts bounded drainers for a long-lived process that is not being awaited yet. */
    public void startDrainers() {
        startPumps(null, null);
    }

    /** Cancels and waits for a bounded reap attempt. */
    public Outcome cancelAndAwait(@Nullable LineSink stdoutSink, @Nullable LineSink stderrSink) {
        cancel();
        return await(terminationGraceMillis * 2L, stdoutSink, stderrSink);
    }

    /**
     * Drains both pipes and waits for terminal state. A zero/negative timeout means no execution
     * deadline. Output callbacks run on dedicated pump threads and must not block indefinitely.
     */
    public Outcome await(long timeoutMillis, @Nullable LineSink stdoutSink, @Nullable LineSink stderrSink) {
        Outcome already = outcome;
        if (already != null) {
            return already;
        }

        startPumps(stdoutSink, stderrSink);
        boolean reaped = false;
        boolean interrupted = false;
        try {
            if (timeoutMillis > 0L) {
                reaped = waitForReap(timeoutMillis);
            } else {
                waitForReap(NO_TIMEOUT);
                reaped = true;
            }
        } catch (InterruptedException e) {
            interrupted = true;
            cancelRequested.set(true);
            // Clear the interrupted flag while doing the bounded reap, then restore it for
            // callers. CountDownLatch.await would otherwise fail immediately on each attempt.
            Thread.interrupted();
            reaped = terminateAndReap();
            Thread.currentThread().interrupt();
        }

        if (!reaped && !interrupted) {
            timeoutRequested.set(true);
            cancelRequested.set(true);
            reaped = terminateAndReap();
        }

        if (!reaped) {
            return acceptTerminal(new Outcome(TerminalState.UNCONFIRMED, null, outputTruncated), false);
        }

        joinPumps(terminationGraceMillis);
        Integer exitCode = readExitCode();
        TerminalState state;
        if (interrupted) {
            state = TerminalState.INTERRUPTED;
        } else if (timeoutRequested.get()) {
            state = TerminalState.TIMED_OUT;
        } else if (cancelRequested.get()) {
            state = TerminalState.CANCELLED;
        } else {
            state = exitCode != null && exitCode == 0 ? TerminalState.SUCCEEDED : TerminalState.FAILED;
        }
        return acceptTerminal(new Outcome(state, exitCode, outputTruncated), true);
    }

    /** Releases a completed handle; an unconfirmed process intentionally retains ownership. */
    @Override
    public void close() {
        Outcome result = await(terminationGraceMillis * 2L, null, null);
        if (result.isConfirmed()) {
            releaseResources();
        }
    }

    @Nullable
    public Outcome getOutcome() {
        return outcome;
    }

    /** Returns the live state without transferring ownership of the underlying Process. */
    public boolean isRunning() {
        return outcome == null && isProcessAlive(process);
    }

    private void startPumps(@Nullable LineSink stdoutSink, @Nullable LineSink stderrSink) {
        if (!pumpsStarted.compareAndSet(false, true)) {
            return;
        }
        stdoutPump = startPump("stdout", process.getInputStream(), stdoutSink);
        stderrPump = startPump("stderr", process.getErrorStream(), stderrSink);
    }

    private Thread startPump(String streamName, InputStream stream, @Nullable LineSink sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(reader)) != null) {
                    if (sink != null) {
                        try {
                            sink.onLine(line);
                        } catch (RuntimeException callbackFailure) {
                            FLog.e("NativeExecution", label + " " + streamName + " callback failed", callbackFailure);
                        }
                    }
                }
            } catch (IOException streamFailure) {
                // A destroy/close during cancellation commonly closes the pipe. The process
                // outcome remains the source of truth; do not turn a normal cancel into success.
                FLog.d("NativeExecution", label + " " + streamName + " drain ended: " + streamFailure.getMessage());
            } finally {
                pumpsFinished.countDown();
            }
        }, "cloudbridge-native-" + streamName + "-" + label);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Reads one line without ever retaining more than MAX_CALLBACK_LINE_CHARS characters. */
    @Nullable
    private String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(256, MAX_CALLBACK_LINE_CHARS));
        boolean sawCharacter = false;
        boolean truncated = false;
        int value;
        while ((value = reader.read()) != -1) {
            sawCharacter = true;
            if (value == '\n') {
                break;
            }
            if (value == '\r') {
                continue;
            }
            if (line.length() < MAX_CALLBACK_LINE_CHARS) {
                line.append((char) value);
            } else {
                truncated = true;
            }
        }
        if (!sawCharacter && value == -1) {
            return null;
        }
        if (truncated) {
            outputTruncated = true;
            line.append("\n***line-truncated***");
        }
        return line.toString();
    }

    private void destroyProcess(boolean force) {
        try {
            if (isProcessAlive(process)) {
                if (force) {
                    forceDestroy(process);
                } else {
                    process.destroy();
                }
            }
        } catch (RuntimeException failure) {
            FLog.e("NativeExecution", label + " process termination failed", failure);
        }
    }

    private boolean waitForReap(long timeoutMillis) throws InterruptedException {
        startReaper();
        if (timeoutMillis <= 0L) {
            processReaped.await();
            return true;
        }
        return processReaped.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private boolean terminateAndReap() {
        try {
            destroyProcess(false);
            if (waitForReap(terminationGraceMillis)) {
                return true;
            }
            destroyProcess(true);
            return waitForReap(terminationGraceMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcess(true);
            return false;
        }
    }

    private void startReaper() {
        if (!reaperStarted.compareAndSet(false, true)) {
            return;
        }
        Thread reaper = new Thread(() -> {
            boolean reaped = false;
            while (!reaped) {
                try {
                    process.waitFor();
                    reaped = true;
                } catch (InterruptedException ignored) {
                    // The reaper is the sole waitFor owner. An interruption is not proof of exit.
                }
            }
            processReaped.countDown();
            // A bounded caller may already have reported UNCONFIRMED. Keep that conservative
            // result, but stop retaining wake/Wi-Fi resources once exit is actually proved.
            if (outcome != null && outcome.getState() == TerminalState.UNCONFIRMED) {
                releaseResources();
            }
        }, "cloudbridge-native-reaper-" + label);
        reaper.setDaemon(true);
        reaper.start();
    }

    private void joinPumps(long timeoutMillis) {
        try {
            pumpsFinished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Closing the streams unblocks a late callback/pump without affecting a reaped process.
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    @Nullable
    private Integer readExitCode() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException notReaped) {
            return null;
        }
    }

    private synchronized Outcome acceptTerminal(Outcome candidate, boolean releaseResources) {
        if (terminalAccepted.compareAndSet(false, true)) {
            outcome = candidate;
            if ((releaseResources && candidate.isConfirmed())
                    || (candidate.getState() == TerminalState.UNCONFIRMED
                    && processReaped.getCount() == 0L)) {
                releaseResources();
            }
        }
        return outcome;
    }

    private void releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) {
            return;
        }
        List<AutoCloseable> toClose;
        synchronized (resourceLock) {
            toClose = new ArrayList<>(resources);
            resources.clear();
        }
        for (AutoCloseable resource : toClose) {
            try {
                resource.close();
            } catch (Exception failure) {
                FLog.e("NativeExecution", label + " resource release failed", failure);
            }
        }
    }

    private static void forceDestroy(Process process) {
        try {
            process.getClass().getMethod("destroyForcibly").invoke(process);
        } catch (ReflectiveOperationException ignored) {
            // Android API levels without destroyForcibly still get the best-effort destroy().
            process.destroy();
        }
    }

    /** Avoids the API-26 Process.isAlive() call on the app's minSdk 23 path. */
    private static boolean isProcessAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }
}
