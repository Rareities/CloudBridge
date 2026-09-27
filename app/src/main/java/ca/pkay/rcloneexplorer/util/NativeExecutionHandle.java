package ca.pkay.rcloneexplorer.util;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    public static final long MIN_OUTPUT_DRAIN_GRACE_MILLIS = 5000L;
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

    /** Exclusive access to the three pipes for a command that requires interactive prompts. */
    public static final class InteractiveSession implements AutoCloseable {
        private final NativeExecutionHandle owner;
        private final InputStream stdout;
        private final InputStream stderr;
        private final OutputStream stdin;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private InteractiveSession(NativeExecutionHandle owner, Process process) {
            this.owner = owner;
            stdout = process.getInputStream();
            stderr = process.getErrorStream();
            stdin = process.getOutputStream();
        }

        public InputStream getStdout() { return stdout; }
        public InputStream getStderr() { return stderr; }
        public OutputStream getStdin() { return stdin; }

        /**
         * Ends interactive access without closing the pipes. The owning handle takes over
         * draining them when its normal await begins.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.finishInteractiveSession();
            }
        }
    }

    private static final class OwnedOutputPipe extends FilterInputStream {
        private final NativeExecutionHandle owner;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile boolean reachedEof;

        private OwnedOutputPipe(NativeExecutionHandle owner, InputStream delegate) {
            super(delegate);
            this.owner = owner;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                reachedEof = true;
                finish();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count < 0) {
                reachedEof = true;
                finish();
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                finish();
            }
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                owner.finishExternalOutput(reachedEof);
            }
        }
    }

    private static final class OwnedInputPipe extends FilterOutputStream {
        private OwnedInputPipe(OutputStream delegate) {
            super(delegate);
        }
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
    // 0 = unclaimed, 1 = interactive session owns the pipes, 2 = bounded drainers own them.
    private final Object pipeLock = new Object();
    private int pipeOwner;
    private final AtomicBoolean inputPipeClaimed = new AtomicBoolean(false);
    private final AtomicBoolean externalOutputFinished = new AtomicBoolean(false);
    private final AtomicBoolean externalOutputAbandoned = new AtomicBoolean(false);
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
    private volatile CountDownLatch interactiveSessionClosed = new CountDownLatch(0);

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
     * Launches native work and transfers a pre-acquired owner to the handle before returning.
     * If the starter throws before returning a Process, the owner is deliberately not closed:
     * callers cannot infer from a missing handle that no child escaped during process creation.
     */
    public static NativeExecutionHandle launchOwned(String[] command, String[] environment,
            String label, @Nullable AutoCloseable owner) throws IOException {
        return launchOwned(command, environment, label, owner,
                (launchCommand, launchEnvironment) ->
                        Runtime.getRuntime().exec(launchCommand, launchEnvironment));
    }

    /** Process-creation seam for package tests of the no-handle ownership boundary. */
    interface ProcessStarter {
        Process start(String[] command, String[] environment) throws IOException;
    }

    static NativeExecutionHandle launchOwned(String[] command, String[] environment,
            String label, @Nullable AutoCloseable owner, ProcessStarter starter) throws IOException {
        if (starter == null) {
            throw new NullPointerException("process starter is required");
        }
        Process process = starter.start(command, environment);
        if (process == null) {
            // A null handle after crossing the launch boundary is ambiguous just like a thrown
            // process-construction failure; preserve the pre-acquired owner.
            throw new IOException("Native process starter returned no process handle");
        }

        NativeExecutionHandle execution = new NativeExecutionHandle(
                process, label, DEFAULT_TERMINATION_GRACE_MILLIS);
        if (!execution.attachResource(owner)) {
            Outcome stopped = execution.cancelAndAwait(null, null);
            if (stopped.isConfirmed() && owner != null) {
                try {
                    owner.close();
                } catch (Exception releaseFailure) {
                    FLog.e("NativeExecution", label + " owner release failed", releaseFailure);
                }
            }
            throw new IOException("Unable to attach native execution owner");
        }
        return execution;
    }

    /**
     * Attaches a resource such as a wake/Wi-Fi lock. It is closed only after confirmed reap.
     * A bounded UNCONFIRMED result still permits late resource attachment until release begins;
     * this lets owners register cleanup with the background reaper. Returns false once resources
     * are already being released, at which point process exit is confirmed.
     */
    public boolean attachResource(@Nullable AutoCloseable resource) {
        if (resource == null) {
            return true;
        }
        synchronized (resourceLock) {
            Outcome terminal = outcome;
            if (resourcesReleased.get() ||
                    (terminalAccepted.get() && terminal != null && terminal.isConfirmed())) {
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
        if (!startPumps(null, null)) {
            cancel();
        }
    }

    /** Opens process pipes for one interactive runner; they remain owned by this handle. */
    public InteractiveSession openInteractiveSession() {
        synchronized (pipeLock) {
            if (outcome != null || pipeOwner != 0) {
                throw new IllegalStateException("Native process pipes are already owned");
            }
            pipeOwner = 1;
            interactiveSessionClosed = new CountDownLatch(1);
            return new InteractiveSession(this, process);
        }
    }

    private void finishInteractiveSession() {
        CountDownLatch release = null;
        synchronized (pipeLock) {
            if (pipeOwner == 1) {
                pipeOwner = 0;
                release = interactiveSessionClosed;
            }
        }
        if (release != null) {
            release.countDown();
        }
    }

    /** Gives one transfer consumer stdout while this handle owns stderr draining and process reap. */
    public InputStream openOutputPipe() {
        return openOutputPipe(null);
    }

    /** Same as {@link #openOutputPipe()}, with a bounded stderr callback selected before draining. */
    public InputStream openOutputPipe(@Nullable LineSink stderrSink) {
        OwnedOutputPipe output;
        synchronized (pipeLock) {
            if (outcome != null || pipeOwner != 0) {
                throw new IllegalStateException("Native process pipes are already owned");
            }
            pipeOwner = 3;
            output = new OwnedOutputPipe(this, process.getInputStream());
        }
        if (!startPumps(null, stderrSink)) {
            cancel();
            throw new IllegalStateException("Interrupted while starting native pipe drains");
        }
        return output;
    }

    /** Gives one transfer producer stdin while this handle drains process output. */
    public OutputStream openInputPipe() {
        return openInputPipe(null);
    }

    /** Same as {@link #openInputPipe()}, with a bounded stderr callback selected before draining. */
    public OutputStream openInputPipe(@Nullable LineSink stderrSink) {
        if (outcome != null || !inputPipeClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("Native process stdin is already owned");
        }
        OutputStream input = new OwnedInputPipe(process.getOutputStream());
        if (!startPumps(null, stderrSink)) {
            cancel();
            throw new IllegalStateException("Interrupted while starting native pipe drains");
        }
        return input;
    }

    private void finishExternalOutput(boolean reachedEof) {
        if (!reachedEof) {
            externalOutputAbandoned.set(true);
            outputTruncated = true;
        }
        if (externalOutputFinished.compareAndSet(false, true)) {
            pumpsFinished.countDown();
        }
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

        if (!startPumps(stdoutSink, stderrSink)) {
            // A caller interrupted while an interactive runner still owns the streams cannot
            // safely hand them to pump threads. Cancel and reap without releasing early.
            Thread.interrupted();
            cancelRequested.set(true);
            boolean reaped = terminateAndReap();
            Thread.currentThread().interrupt();
            if (!reaped) {
                return acceptTerminal(new Outcome(TerminalState.UNCONFIRMED, null, outputTruncated), false);
            }
            return acceptTerminal(new Outcome(TerminalState.INTERRUPTED, readExitCode(), outputTruncated), true);
        }
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

        boolean outputComplete = joinPumps(Math.max(terminationGraceMillis, MIN_OUTPUT_DRAIN_GRACE_MILLIS));
        if (!outputComplete) {
            outputTruncated = true;
        }
        Integer exitCode = readExitCode();
        TerminalState state;
        if (interrupted) {
            state = TerminalState.INTERRUPTED;
        } else if (timeoutRequested.get()) {
            state = TerminalState.TIMED_OUT;
        } else if (cancelRequested.get()) {
            state = TerminalState.CANCELLED;
        } else if (!outputComplete || externalOutputAbandoned.get()) {
            state = TerminalState.FAILED;
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

    /** A later reap may confirm exit after a bounded caller reported UNCONFIRMED. */
    public boolean hasConfirmedReap() {
        return processReaped.getCount() == 0L && !isProcessAlive(process);
    }

    /** True only after the process exit is confirmed, including a late background reap. */
    public boolean isExitConfirmed() {
        Outcome terminal = outcome;
        return (terminal != null && terminal.isConfirmed()) || hasConfirmedReap();
    }

    private boolean startPumps(@Nullable LineSink stdoutSink, @Nullable LineSink stderrSink) {
        boolean externalStdout = false;
        while (true) {
            CountDownLatch release = null;
            synchronized (pipeLock) {
                if (pipeOwner == 0) {
                    pipeOwner = 2;
                    break;
                }
                if (pipeOwner == 2) {
                    break;
                }
                if (pipeOwner == 3) {
                    externalStdout = true;
                    break;
                }
                // InteractiveRunner owns prompt parsing until it hands the pipes back.
                release = interactiveSessionClosed;
            }
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!pumpsStarted.compareAndSet(false, true)) {
            return true;
        }
        if (!externalStdout) {
            stdoutPump = startPump("stdout", process.getInputStream(), stdoutSink);
        }
        stderrPump = startPump("stderr", process.getErrorStream(), stderrSink);
        return true;
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

    private boolean joinPumps(long timeoutMillis) {
        boolean complete = false;
        try {
            complete = pumpsFinished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Closing the streams unblocks a late callback/pump without affecting a reaped process.
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        if (pipeOwner == 3 && !externalOutputFinished.get()) {
            finishExternalOutput(false);
        }
        return complete;
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
            // Reflect on the public Process API, not the concrete Android implementation:
            // its runtime class may be package-private and reject reflective invocation.
            Process.class.getMethod("destroyForcibly").invoke(process);
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
