package ca.pkay.rcloneexplorer.RemoteConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ca.pkay.rcloneexplorer.util.NativeExecutionHandle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class OauthProcessTokenTest {

    @Test
    public void nextAttemptWaitsForPreviousProcessToBeReaped() {
        OauthHelper.OauthProcessToken token = new OauthHelper.OauthProcessToken();
        CountingProcess firstProcess = new CountingProcess(false);
        NativeExecutionHandle first = token.startAttempt(
                () -> NativeExecutionHandle.adopt(firstProcess, "oauth-first", 100));
        assertNotNull(first);

        AtomicInteger launches = new AtomicInteger();
        NativeExecutionHandle second = token.startAttempt(() -> {
            assertTrue("the previous native owner must be reaped before launch",
                    first.hasConfirmedReap());
            launches.incrementAndGet();
            return NativeExecutionHandle.adopt(new CountingProcess(false), "oauth-second", 100);
        });

        assertNotNull(second);
        assertEquals(1, launches.get());
        assertEquals(NativeExecutionHandle.TerminalState.CANCELLED,
                first.getOutcome().getState());
        assertTrue(first.hasConfirmedReap());
        assertTrue(token.forceRelease());
    }

    @Test
    public void unconfirmedPriorProcessBlocksLaunchUntilLateReap() throws Exception {
        OauthHelper.OauthProcessToken token = new OauthHelper.OauthProcessToken();
        CountingProcess firstProcess = new CountingProcess(true);
        NativeExecutionHandle first = token.startAttempt(
                () -> NativeExecutionHandle.adopt(firstProcess, "oauth-unconfirmed", 10));
        assertNotNull(first);

        AtomicInteger launches = new AtomicInteger();
        NativeExecutionHandle blocked = token.startAttempt(() -> {
            launches.incrementAndGet();
            return NativeExecutionHandle.adopt(new CountingProcess(false), "oauth-blocked", 10);
        });
        assertNull(blocked);
        assertEquals(0, launches.get());
        assertFalse(first.hasConfirmedReap());

        firstProcess.complete(0);
        assertTrue(firstProcess.completed.await(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!first.hasConfirmedReap() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("the late reaper must eventually confirm process exit", first.hasConfirmedReap());

        NativeExecutionHandle next = token.startAttempt(() -> {
            launches.incrementAndGet();
            return NativeExecutionHandle.adopt(new CountingProcess(false), "oauth-after-reap", 10);
        });
        assertNotNull(next);
        assertEquals(1, launches.get());
        assertTrue(token.forceRelease());
    }

    private static final class CountingProcess extends Process {
        private final boolean ignoreDestroy;
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile int exitCode;

        CountingProcess(boolean ignoreDestroy) {
            this.ignoreDestroy = ignoreDestroy;
        }

        void complete(int code) {
            exitCode = code;
            completed.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            completed.await();
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (completed.getCount() != 0) {
                throw new IllegalThreadStateException("process is still running");
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
            destroy();
            return this;
        }
    }
}
