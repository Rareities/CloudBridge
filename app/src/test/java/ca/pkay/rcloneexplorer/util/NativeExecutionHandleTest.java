package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import org.junit.Test;

public class NativeExecutionHandleTest {

    @Test
    public void drainsBothPipesAndAcceptsOnlyOneTerminalOutcome() {
        ScriptedProcess process = new ScriptedProcess(4000, false, false);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "pipe-test", 100);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));

        AtomicInteger lines = new AtomicInteger();
        NativeExecutionHandle.Outcome outcome = handle.await(
                5000,
                line -> { },
                line -> lines.incrementAndGet());

        assertTrue(outcome.isSuccess());
        assertEquals(4000, lines.get());
        assertFalse(outcome.isOutputTruncated());
        assertEquals(1, resource.closeCount.get());
        assertSame(outcome, handle.await(1, null, null));

        handle.cancel();
        assertEquals(1, resource.closeCount.get());
    }

    @Test
    public void drainsFullStdoutPipeBeforeReportingSuccess() {
        ScriptedProcess process = new ScriptedProcess(4000, false, false, false, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "stdout-pipe-test", 100);
        AtomicInteger lines = new AtomicInteger();

        NativeExecutionHandle.Outcome outcome = handle.await(5000,
                line -> lines.incrementAndGet(), null);

        assertTrue(outcome.isSuccess());
        assertEquals(4000, lines.get());
    }

    @Test
    public void interactivePipeHandoffResumesOwnedDraining() throws Exception {
        ScriptedProcess process = new ScriptedProcess(4000, false, false);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "interactive-handoff-test", 100);
        NativeExecutionHandle.InteractiveSession session = handle.openInteractiveSession();
        AtomicInteger lines = new AtomicInteger();
        CompletableFuture<NativeExecutionHandle.Outcome> completion = CompletableFuture.supplyAsync(
                () -> handle.await(5000, null, line -> lines.incrementAndGet()));

        try {
            Thread.sleep(50);
            assertFalse("await must not steal pipes from the interactive session", completion.isDone());
        } finally {
            session.close();
        }

        assertTrue(completion.get(5, TimeUnit.SECONDS).isSuccess());
        assertEquals(4000, lines.get());
    }

    @Test
    public void interactivePipesCanOnlyHaveOneOwner() {
        ScriptedProcess process = new ScriptedProcess(0, true, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "interactive-exclusive-test", 20);
        NativeExecutionHandle.InteractiveSession session = handle.openInteractiveSession();
        try {
            try {
                handle.openInteractiveSession();
                throw new AssertionError("A second interactive pipe owner was accepted");
            } catch (IllegalStateException expected) {
                // The first session remains the exclusive owner.
            }
        } finally {
            session.close();
            process.complete(0);
        }
        assertTrue(handle.await(1000, null, null).isSuccess());
    }

    @Test
    public void externallyConsumedOutputPipeRemainsOwnedThroughEofAndReap() throws Exception {
        ScriptedProcess process = new ScriptedProcess(512, false, false, false, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "external-output-pipe-test", 100);
        InputStream output = handle.openOutputPipe();
        byte[] buffer = new byte[1024];
        int total = 0;
        for (int read; (read = output.read(buffer)) >= 0; ) {
            total += read;
        }
        output.close();

        assertTrue(total > 0);
        assertTrue(handle.await(5000, null, null).isSuccess());
    }

    @Test
    public void earlyOutputPipeCloseIsNotReportedAsSuccessfulTransfer() throws Exception {
        ScriptedProcess process = new ScriptedProcess(10, false, false, false, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "early-output-pipe-test", 100);
        InputStream output = handle.openOutputPipe();
        assertTrue(output.read() >= 0);
        output.close();

        NativeExecutionHandle.Outcome outcome = handle.await(5000, null, null);
        assertEquals(NativeExecutionHandle.TerminalState.FAILED, outcome.getState());
        assertTrue(outcome.isOutputTruncated());
    }

    @Test
    public void cancellationReapsProcessBeforeReleasingResource() {
        ScriptedProcess process = new ScriptedProcess(0, true, false);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "cancel-test", 50);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));

        NativeExecutionHandle.Outcome outcome = handle.cancelAndAwait(null, null);

        assertEquals(NativeExecutionHandle.TerminalState.CANCELLED, outcome.getState());
        assertTrue(outcome.isConfirmed());
        assertTrue(handle.isExitConfirmed());
        assertEquals(1, resource.closeCount.get());
        assertFalse(process.isAlive());
    }

    @Test
    public void unconfirmedExitAcceptsLateResourcesUntilTheReaperConfirmsExit() throws Exception {
        ScriptedProcess process = new ScriptedProcess(0, true, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "unconfirmed-test", 10);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));

        NativeExecutionHandle.Outcome outcome = handle.await(5, null, null);

        assertEquals(NativeExecutionHandle.TerminalState.UNCONFIRMED, outcome.getState());
        assertFalse(outcome.isConfirmed());
        assertFalse(handle.isExitConfirmed());
        assertEquals(0, resource.closeCount.get());

        CountingResource lateResource = new CountingResource();
        assertTrue(handle.attachResource(lateResource));
        assertEquals(0, lateResource.closeCount.get());
        process.complete(0);
        assertTrue(resource.closed.await(2, TimeUnit.SECONDS));
        assertTrue(lateResource.closed.await(2, TimeUnit.SECONDS));
        assertTrue(handle.isExitConfirmed());
    }

    @Test
    public void lateConfirmedExitReleasesRetainedResource() throws Exception {
        ScriptedProcess process = new ScriptedProcess(0, true, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "late-reap-test", 10);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));

        assertEquals(NativeExecutionHandle.TerminalState.UNCONFIRMED,
                handle.await(5, null, null).getState());
        assertFalse(handle.hasConfirmedReap());
        assertFalse(handle.isExitConfirmed());
        assertEquals(0, resource.closeCount.get());

        process.complete(0);
        assertTrue(resource.closed.await(2, TimeUnit.SECONDS));
        assertTrue(handle.hasConfirmedReap());
        assertTrue(handle.isExitConfirmed());
        assertEquals(1, resource.closeCount.get());
    }

    @Test
    public void stagedSourceCleanupWaitsForLateConfirmedReap() throws Exception {
        ScriptedProcess process = new ScriptedProcess(0, true, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "late-share-reap-test", 10);
        File cache = new File(System.getProperty("java.io.tmpdir"), "share-cache-" + UUID.randomUUID());
        assertTrue(cache.mkdir());
        File invocationDirectory = new File(cache, "share-" + UUID.randomUUID());
        assertTrue(invocationDirectory.mkdir());
        File stagedFile = new File(invocationDirectory, "staged.bin");
        assertTrue(stagedFile.createNewFile());

        StagedUploadSourceCleanup cleanup = new StagedUploadSourceCleanup(cache, stagedFile);
        CountingResource resourceReleasedLast = new CountingResource();

        try {
            assertEquals(NativeExecutionHandle.TerminalState.UNCONFIRMED,
                    handle.await(5, null, null).getState());
            assertFalse(handle.isExitConfirmed());
            assertTrue("cleanup may attach until the late reaper starts releasing resources",
                    cleanup.attachTo(handle));
            assertTrue(handle.attachResource(resourceReleasedLast));
            cleanup.close();
            assertTrue("staged bytes must remain while the native process may read them", stagedFile.isFile());

            process.complete(0);
            assertTrue(resourceReleasedLast.closed.await(2, TimeUnit.SECONDS));
            assertTrue(handle.isExitConfirmed());
            assertFalse("late confirmed reaping should trigger the attached cleanup", stagedFile.exists());
            assertFalse(invocationDirectory.exists());
        } finally {
            process.complete(0);
            if (stagedFile.exists()) stagedFile.delete();
            if (invocationDirectory.exists()) invocationDirectory.delete();
            if (cache.exists()) cache.delete();
        }
    }

    @Test
    public void noTimeoutWaitsUntilProcessActuallyExits() throws Exception {
        ScriptedProcess process = new ScriptedProcess(0, true, true);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "no-deadline-test", 10);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));
        CompletableFuture<NativeExecutionHandle.Outcome> completion = CompletableFuture.supplyAsync(
                () -> handle.await(NativeExecutionHandle.NO_TIMEOUT, null, null));

        try {
            completion.get(50, TimeUnit.MILLISECONDS);
            throw new AssertionError("No-timeout await returned before native exit");
        } catch (TimeoutException expected) {
            assertEquals(0, resource.closeCount.get());
        }

        process.complete(0);
        assertTrue(completion.get(2, TimeUnit.SECONDS).isSuccess());
        assertEquals(1, resource.closeCount.get());
    }

    @Test
    public void timeoutEscalatesToForcedKillAndNeverReportsSuccess() {
        ScriptedProcess process = new ScriptedProcess(0, true, true, false);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "forced-kill-test", 10);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));

        NativeExecutionHandle.Outcome outcome = handle.await(5, null, null);

        assertEquals(NativeExecutionHandle.TerminalState.TIMED_OUT, outcome.getState());
        assertEquals(Integer.valueOf(137), outcome.getExitCode());
        assertEquals(1, resource.closeCount.get());
        assertFalse(process.isAlive());
    }

    @Test
    public void concurrentStopAndFinishShareOneTerminalOutcome() throws Exception {
        ScriptedProcess process = new ScriptedProcess(0, true, false);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "concurrent-stop-test", 20);
        CountingResource resource = new CountingResource();
        assertTrue(handle.attachResource(resource));
        CompletableFuture<NativeExecutionHandle.Outcome> normalWait = CompletableFuture.supplyAsync(
                () -> handle.await(NativeExecutionHandle.NO_TIMEOUT, null, null));

        NativeExecutionHandle.Outcome cancelled = handle.cancelAndAwait(null, null);
        NativeExecutionHandle.Outcome completed = normalWait.get(2, TimeUnit.SECONDS);

        assertSame(cancelled, completed);
        assertEquals(NativeExecutionHandle.TerminalState.CANCELLED, completed.getState());
        assertEquals(1, resource.closeCount.get());
    }

    @Test
    public void launchFailureDoesNotInventAProcessOrSuccessfulOutcome() {
        try {
            NativeExecutionHandle.launch(
                    new String[]{"cloudbridge-native-missing-command-for-test"}, null,
                    "prelaunch-failure-test");
            throw new AssertionError("Missing native command unexpectedly launched");
        } catch (IOException expected) {
            // The caller keeps responsibility for resources acquired before launch.
        }
    }

    @Test
    public void ownedLaunchFailureAfterChildCreationDoesNotReleaseClaim() {
        CountingResource owner = new CountingResource();
        Process[] escapedChild = new Process[1];

        try {
            NativeExecutionHandle.launchOwned(
                    new String[]{"cloudbridge-native-test"}, null, "ambiguous-launch-test", owner,
                    (command, environment) -> {
                        // Model an OS child created before Java fails to construct/return Process.
                        escapedChild[0] = new ScriptedProcess(0, true, false);
                        throw new IOException("process handle construction failed after child start");
                    });
            throw new AssertionError("Ambiguous launch unexpectedly returned a handle");
        } catch (IOException expected) {
            assertTrue(escapedChild[0].isAlive());
            assertEquals("the durable claim must remain held without exit evidence", 0,
                    owner.closeCount.get());
        } finally {
            if (escapedChild[0] instanceof ScriptedProcess) {
                ((ScriptedProcess) escapedChild[0]).complete(0);
            }
            // The production owner intentionally has no release authority without a Process handle.
            // Close this in-memory test fixture only after asserting the fail-closed behavior.
            try {
                owner.close();
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    @Test
    public void ownedLaunchAttachesClaimBeforeReturningHandle() throws Exception {
        CountingResource owner = new CountingResource();
        ScriptedProcess process = new ScriptedProcess(0, true, false);

        NativeExecutionHandle handle = NativeExecutionHandle.launchOwned(
                new String[]{"cloudbridge-native-test"}, null, "owned-launch-test", owner,
                (command, environment) -> process);

        assertFalse(handle.isExitConfirmed());
        assertEquals(0, owner.closeCount.get());
        NativeExecutionHandle.Outcome outcome = handle.cancelAndAwait(null, null);
        assertTrue(outcome.isConfirmed());
        assertEquals(1, owner.closeCount.get());
    }

    @Test
    public void stagedSourceRemainsQuarantinedWhenLaunchMayHaveStartedWithoutHandle() throws Exception {
        File cache = new File(System.getProperty("java.io.tmpdir"), "share-cache-" + UUID.randomUUID());
        assertTrue(cache.mkdir());
        File invocationDirectory = new File(cache, "share-" + UUID.randomUUID());
        assertTrue(invocationDirectory.mkdir());
        File stagedFile = new File(invocationDirectory, "staged.bin");
        assertTrue(stagedFile.createNewFile());

        StagedUploadSourceCleanup cleanup = new StagedUploadSourceCleanup(cache, stagedFile);
        try {
            cleanup.markLaunchAttempted();
            assertFalse(cleanup.cleanupAfter(null));
            assertTrue("without a handle, possible native launch must preserve source bytes",
                    stagedFile.isFile());

            cleanup.confirmNoProcessStarted();
            assertFalse("confirmed no-child launch failure can safely clean staging", stagedFile.exists());
            assertFalse(invocationDirectory.exists());
        } finally {
            if (stagedFile.exists()) stagedFile.delete();
            if (invocationDirectory.exists()) invocationDirectory.delete();
            if (cache.exists()) cache.delete();
        }
    }

    @Test
    public void slowOutputCallbackCannotTurnIncompleteDrainIntoSuccess() throws Exception {
        ScriptedProcess process = new ScriptedProcess(1, false, false);
        NativeExecutionHandle handle = NativeExecutionHandle.adopt(process, "late-callback-test", 10);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CompletableFuture<NativeExecutionHandle.Outcome> completion = CompletableFuture.supplyAsync(
                () -> handle.await(1000, null, line -> {
                    callbackEntered.countDown();
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));

        try {
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            NativeExecutionHandle.Outcome outcome = completion.get(8, TimeUnit.SECONDS);
            assertEquals(NativeExecutionHandle.TerminalState.FAILED, outcome.getState());
            assertTrue(outcome.isOutputTruncated());
        } finally {
            releaseCallback.countDown();
        }
    }

    private static final class CountingResource implements AutoCloseable {
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closed.countDown();
        }
    }

    /** A small fake process whose stderr writer blocks if the owner stops draining. */
    private static final class ScriptedProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedInputStream stderr = new PipedInputStream();
        private final PipedOutputStream stdoutWriter;
        private final PipedOutputStream stderrWriter;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final boolean ignoreDestroy;
        private final boolean ignoreForcedDestroy;
        private final Thread writer;
        private volatile boolean alive = true;
        private volatile int exitCode = 0;

        private ScriptedProcess(int lines, boolean waitForDestroy, boolean ignoreDestroy) {
            this(lines, waitForDestroy, ignoreDestroy, ignoreDestroy);
        }

        private ScriptedProcess(int lines, boolean waitForDestroy, boolean ignoreDestroy,
                                boolean ignoreForcedDestroy) {
            this(lines, waitForDestroy, ignoreDestroy, ignoreForcedDestroy, false);
        }

        private ScriptedProcess(int lines, boolean waitForDestroy, boolean ignoreDestroy,
                                boolean ignoreForcedDestroy, boolean writeStdout) {
            try {
                stdoutWriter = new PipedOutputStream(stdout);
                stderrWriter = new PipedOutputStream(stderr);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            this.ignoreDestroy = ignoreDestroy;
            this.ignoreForcedDestroy = ignoreForcedDestroy;
            writer = new Thread(() -> {
                try {
                    PipedOutputStream output = writeStdout ? stdoutWriter : stderrWriter;
                    for (int i = 0; i < lines; i++) {
                        output.write(("{\"line\":" + i + "}\n").getBytes());
                    }
                    output.flush();
                    if (waitForDestroy) {
                        finished.await();
                    } else {
                        complete(0);
                    }
                } catch (IOException ignored) {
                    // The handle may close a pipe during cancellation.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "fake-native-writer");
            writer.setDaemon(true);
            writer.start();
        }

        private void complete(int result) {
            exitCode = result;
            alive = false;
            try { stdoutWriter.close(); } catch (IOException ignored) { }
            try { stderrWriter.close(); } catch (IOException ignored) { }
            finished.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            finished.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            if (!ignoreDestroy) {
                complete(143);
            }
        }

        @Override
        public Process destroyForcibly() {
            if (!ignoreForcedDestroy) {
                complete(137);
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
