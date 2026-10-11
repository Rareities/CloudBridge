package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Pipes intentionally have small buffers to reproduce blocked native writers. */
public class NativePipeTransferTest {
    @Test(timeout = 10000) public void downloadPreservesBytesAndReportsNativeFailureWithRedactedStderr() throws Exception {
        FakeProcess nativeChild = new FakeProcess(true);
        NativePipeTransfer transfer = new NativePipeTransfer(nativeChild, false);
        byte[] payload = {0, 1, 2, (byte) 255, 0, 10};
        nativeChild.stdoutWriter.write(payload);
        nativeChild.stderrWriter.write("api_key=sensitive-value\n".getBytes());
        nativeChild.finish(7);
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[4];
        int count;
        while ((count = transfer.input().read(buffer)) != -1) collected.write(buffer, 0, count);
        assertArrayEquals(payload, collected.toByteArray());
        transfer.input().close();
        try {
            transfer.awaitSuccessfulExit();
            fail("Nonzero exit must be failure even when all bytes were delivered");
        } catch (IOException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains("code 7"));
            assertFalse(failure.getMessage().contains("sensitive-value"));
            assertTrue(failure.getMessage(), failure.getMessage().contains("[redacted]"));
        }
    }

    @Test(timeout = 10000) public void uploadDrainsSaturatedStdoutAndStderrWithoutDroppingExitStatus() throws Exception {
        FakeProcess nativeChild = new FakeProcess(true);
        NativePipeTransfer transfer = new NativePipeTransfer(nativeChild, true);
        byte[] payload = new byte[4096];
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 512; i++) {
                    nativeChild.stdoutWriter.write(payload);
                    nativeChild.stderrWriter.write(payload);
                }
                nativeChild.finish(0);
            } catch (IOException error) { nativeChild.finish(9); }
        });
        producer.start();
        transfer.output().write(new byte[] {1, 2, 3});
        transfer.output().close();
        transfer.awaitSuccessfulExit();
        producer.join(2000);
        assertFalse("Native producer stalled in a full pipe", producer.isAlive());
    }

    @Test(timeout = 10000) public void downloadDrainsConcurrentStderrAndPreservesLargeBinaryStream() throws Exception {
        FakeProcess nativeChild = new FakeProcess(true);
        NativePipeTransfer transfer = new NativePipeTransfer(nativeChild, false);
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 512; i++) {
                    nativeChild.stdoutWriter.write(payload);
                    nativeChild.stderrWriter.write(payload);
                }
                nativeChild.finish(0);
            } catch (IOException failure) { nativeChild.finish(7); }
        });
        producer.start();
        byte[] buffer = new byte[8192];
        int length;
        long total = 0;
        while ((length = transfer.input().read(buffer)) != -1) {
            for (int i = 0; i < length; i++) assertEquals(payload[(int) ((total + i) % payload.length)], buffer[i]);
            total += length;
        }
        transfer.input().close();
        transfer.awaitSuccessfulExit();
        producer.join(2000);
        assertEquals(512L * payload.length, total);
        assertFalse("Native producer stalled on stderr", producer.isAlive());
    }

    @Test(timeout = 10000) public void exitCallbackRunsOnceOnlyAfterConfirmedExitIncludingLateRegistration() throws Exception {
        FakeProcess nativeChild = new FakeProcess(false);
        NativePipeTransfer transfer = new NativePipeTransfer(nativeChild, true);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callback = new CountDownLatch(1);
        transfer.onExit(() -> { callbacks.incrementAndGet(); callback.countDown(); });
        transfer.cancel();
        assertFalse("Destroy request is not proof of process exit", callback.await(200, TimeUnit.MILLISECONDS));
        assertEquals(0, callbacks.get());
        nativeChild.finish(143);
        assertTrue(callback.await(2000, TimeUnit.MILLISECONDS));
        assertEquals(1, callbacks.get());
        transfer.onExit(callbacks::incrementAndGet);
        assertEquals(2, callbacks.get());
        try {
            transfer.awaitSuccessfulExit();
            fail("Cancellation must never be success");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cancelled"));
        }
    }

    @Test(timeout = 10000) public void uploadWriteFailureCannotBeOverriddenByZeroExit() throws Exception {
        FakeProcess nativeChild = new FakeProcess(true);
        NativePipeTransfer transfer = new NativePipeTransfer(nativeChild, true);
        nativeChild.stdinReader.close();
        try {
            transfer.output().write(new byte[] {1, 2, 3});
            fail("Writing to a closed native stdin must fail");
        } catch (IOException expected) { }
        nativeChild.finish(0);
        try {
            transfer.awaitSuccessfulExit();
            fail("Stream failure must override a zero exit code");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("stream failed"));
        }
    }

    @Test(timeout = 10000) public void earlyDownloadCloseIsFailureEvenAfterCleanProcessExit() throws Exception {
        FakeProcess nativeChild = new FakeProcess(true);
        NativePipeTransfer transfer = new NativePipeTransfer(nativeChild, false);
        nativeChild.stdoutWriter.write(new byte[] {10, 11, 12, 13});
        nativeChild.finish(0);
        assertEquals(10, transfer.input().read());
        transfer.input().close();
        try {
            transfer.awaitSuccessfulExit();
            fail("Abandoning buffered native stdout before EOF must not report success");
        } catch (IOException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains("stream failed"));
        }
    }

    /** Controllable Process with genuine bounded stdin, stdout and stderr pipes. */
    private static final class FakeProcess extends Process {
        final PipedInputStream stdoutReader;
        final PipedOutputStream stdoutWriter;
        final PipedInputStream stderrReader;
        final PipedOutputStream stderrWriter;
        final PipedInputStream stdinReader;
        final PipedOutputStream stdinWriter;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final boolean exitsOnDestroy;
        private volatile int code;

        FakeProcess(boolean exitsOnDestroy) throws IOException {
            this.exitsOnDestroy = exitsOnDestroy;
            stdoutReader = new PipedInputStream(1024);
            stdoutWriter = new PipedOutputStream(stdoutReader);
            stderrReader = new PipedInputStream(1024);
            stderrWriter = new PipedOutputStream(stderrReader);
            stdinReader = new PipedInputStream(1024);
            stdinWriter = new PipedOutputStream(stdinReader);
        }

        synchronized void finish(int exitCode) {
            if (exited.getCount() == 0) return;
            code = exitCode;
            try { stdoutWriter.close(); } catch (IOException ignored) { }
            try { stderrWriter.close(); } catch (IOException ignored) { }
            try { stdinReader.close(); } catch (IOException ignored) { }
            exited.countDown();
        }

        @Override public OutputStream getOutputStream() { return stdinWriter; }
        @Override public InputStream getInputStream() { return stdoutReader; }
        @Override public InputStream getErrorStream() { return stderrReader; }
        @Override public int waitFor() throws InterruptedException { exited.await(); return code; }
        @Override public int exitValue() {
            if (exited.getCount() != 0) throw new IllegalThreadStateException("Still running");
            return code;
        }
        @Override public void destroy() { if (exitsOnDestroy) finish(143); }
    }
}
