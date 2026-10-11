package ca.pkay.rcloneexplorer;

import ca.pkay.rcloneexplorer.util.NativeSessionGate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real admission gate against controlled native process completion. */
public class RcSessionIntegrationTest {
    private static final class ControllableProcess extends Process {
        private final CountDownLatch exit = new CountDownLatch(1);
        private volatile int status;
        private volatile boolean destroyCalled;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { exit.await(); return status; }
        @Override public int exitValue() {
            if (exit.getCount() != 0) throw new IllegalThreadStateException("Native process still exists");
            return status;
        }
        @Override public void destroy() { destroyCalled = true; }
        void finish(int result) { status = result; exit.countDown(); }
    }

    @Test public void stopRequestDoesNotReleaseCommandAdmissionUntilActualNativeExit() throws Exception {
        NativeSessionGate gate = new NativeSessionGate();
        ControllableProcess child = new ControllableProcess();
        NativeSessionGate.Lease lease = gate.acquireCommand();
        try {
            lease.closeAfterExit(child);
            child.destroy();
            assertTrue(child.destroyCalled);
            assertThrows(IOException.class, gate::acquireCommand);
            assertThrows(IOException.class, () -> gate.acquireRc(gate.generation()));
            child.finish(9);
            // Wait for the actual completion observer, not a fixed sleep.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            NativeSessionGate.Lease next = null;
            while (next == null && System.nanoTime() < deadline) {
                try { next = gate.acquireCommand(); }
                catch (IOException busy) { Thread.yield(); }
            }
            assertNotNull("Native exit must eventually release the lease", next);
            next.close();
        } finally { child.finish(9); lease.close(); }
    }

    @Test public void multipleRcUsersMustAllExitBeforeCommandAdmission() throws Exception {
        NativeSessionGate gate = new NativeSessionGate();
        long generation = gate.generation();
        NativeSessionGate.Lease first = gate.acquireRc(generation);
        NativeSessionGate.Lease second = gate.acquireRc(generation);
        try {
            assertThrows(IOException.class, gate::acquireCommand);
            first.close();
            assertThrows(IOException.class, gate::acquireCommand);
            second.close();
            NativeSessionGate.Lease command = gate.acquireCommand();
            command.close();
            assertThrows(IOException.class, () -> gate.acquireRc(generation));
            NativeSessionGate.Lease fresh = gate.acquireRc(gate.generation());
            fresh.close();
        } finally { first.close(); second.close(); }
    }

    @Test public void localMetadataClassificationDoesNotSkipRealTransferSessions() {
        assertTrue(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "--config", "conf", "config", "dump"}));
        assertTrue(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "bisync", "--help"}));
        assertFalse(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "bisync", "left", "right"}));
        assertFalse(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "copy", "left", "right"}));
    }
}
