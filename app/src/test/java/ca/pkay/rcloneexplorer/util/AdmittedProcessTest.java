package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AdmittedProcessTest {
    private static final class Child extends Process {
        final CountDownLatch exited = new CountDownLatch(1);
        boolean destroyRequested;
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() throws InterruptedException { exited.await(); return 7; }
        public int exitValue() {
            if (exited.getCount() != 0) throw new IllegalThreadStateException();
            return 7;
        }
        public void destroy() { destroyRequested = true; }
    }
    @Test public void cancellationRetainsAdmissionUntilConfirmedExit() throws Exception {
        Child child = new Child();
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch released = new CountDownLatch(1);
        Process process = new AdmittedProcess(child, () -> { closed.incrementAndGet(); released.countDown(); });
        process.destroy();
        assertTrue(child.destroyRequested);
        assertEquals(0, closed.get());
        try { process.exitValue(); fail("Child is still running"); }
        catch (IllegalThreadStateException expected) { }
        child.exited.countDown();
        assertTrue(released.await(5, TimeUnit.SECONDS));
        assertEquals(7, process.waitFor());
        assertEquals(7, process.exitValue());
        assertEquals(1, closed.get());
    }
    @Test public void startupFailureReleasesAdmission() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        try {
            AdmittedProcess.start(new String[]{"/nonexistent-cloudbridge-native-child"}, new String[0], closed::incrementAndGet);
            fail("Expected startup failure");
        } catch (IOException expected) { assertEquals(1, closed.get()); }
    }
}
