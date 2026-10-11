package io.github.rareities.protonbridge.util;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class NativeSessionGateTest {
    @Test public void rcAndCliCannotShareMutableSessionsAndOldDaemonsAreInvalidated() throws Exception {
        NativeSessionGate gate = new NativeSessionGate();
        long daemon = gate.generation();
        NativeSessionGate.Lease first = gate.acquireRc(daemon);
        NativeSessionGate.Lease second = gate.acquireRc(daemon);
        assertThrows(IOException.class, gate::acquireCommand);
        first.close(); first.close();
        assertThrows(IOException.class, gate::acquireCommand);
        second.close();
        NativeSessionGate.Lease command = gate.acquireCommand();
        assertThrows(IOException.class, () -> gate.acquireRc(daemon));
        assertThrows(IOException.class, gate::acquireCommand);
        command.close();
        assertThrows(IOException.class, () -> gate.acquireRc(daemon));
        gate.acquireRc(gate.generation()).close();
    }

    @Test(timeout=5000) public void cancellationDoesNotReleaseOwnershipBeforeActualExit() throws Exception {
        NativeSessionGate gate = new NativeSessionGate();
        DelayedProcess child = new DelayedProcess();
        NativeSessionGate.Lease owner = gate.acquireCommand();
        owner.closeAfterExit(child);
        child.destroy();
        assertThrows(IOException.class, gate::acquireCommand);
        child.exit.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try { gate.acquireCommand().close(); return; }
            catch (IOException busy) { Thread.sleep(5); }
        }
        fail("Confirmed child exit did not release native admission");
    }

    @Test public void metadataDetectionDoesNotTreatAnEndpointAsACommand() {
        assertFalse(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "cat", "version"}));
        assertFalse(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "copy", "a", "listremotes"}));
        assertFalse(NativeSessionGate.isDaemon(new String[]{"rclone", "--rc-addr", "127.0.0.1:5572", "copy", "source", "rcd"}));
        assertTrue(NativeSessionGate.isDaemon(new String[]{"rclone", "--config", "fixture", "--rc-addr", "127.0.0.1:5572", "--rc-serve", "rcd"}));
        assertTrue(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "--config", "fixture", "config", "dump"}));
        assertTrue(NativeSessionGate.isLocalMetadata(new String[]{"rclone", "--config", "fixture", "bisync", "a", "b", "--help"}));
    }

    private static class DelayedProcess extends Process {
        final CountDownLatch exit = new CountDownLatch(1);
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { exit.await(); return 143; }
        @Override public int exitValue() { if (exit.getCount() != 0) throw new IllegalThreadStateException(); return 143; }
        @Override public void destroy() { }
    }
}
