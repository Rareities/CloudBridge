package ca.pkay.rcloneexplorer;

import android.app.Application;
import android.os.Looper;
import ca.pkay.rcloneexplorer.util.NativeSessionGate;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import androidx.test.core.app.ApplicationProvider;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RcdTerminalRaceTest {
    private static final class PendingProcess extends Process {
        private final CountDownLatch exited = new CountDownLatch(1);
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { exited.await(); return 0; }
        @Override public int exitValue() {
            if (exited.getCount() != 0) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { /* Process exit is controlled independently. */ }
        void finish() { exited.countDown(); }
    }

    private static RcloneRcd daemon() {
        return new RcloneRcd(ApplicationProvider.getApplicationContext(), null);
    }

    private static void exit(RcloneRcd daemon) throws Exception {
        Method method = RcloneRcd.class.getDeclaredMethod("onNativeExit");
        method.setAccessible(true);
        method.invoke(daemon);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static RcloneRcd.JobStatusResponse finished(int id, boolean success) {
        RcloneRcd.JobStatusResponse response = new RcloneRcd.JobStatusResponse();
        response.id = id;
        response.finished = true;
        response.success = success;
        return response;
    }

    @Test public void uncheckedFailureBeforeSpawnReleasesStartupPermit() throws Exception {
        assertFalse("No previous session may remain active", NativeSessionGate.GLOBAL.isBusy());
        RcloneRcd daemon = daemon();
        try {
            assertThrows(IllegalStateException.class, () -> daemon.startRcdWithStartup(() -> {
                throw new IllegalStateException("synthetic pre-spawn socket failure");
            }));
            assertFalse("Pre-spawn failure stranded native admission", NativeSessionGate.GLOBAL.isBusy());
            NativeSessionGate.Lease command = NativeSessionGate.GLOBAL.acquireCommand();
            command.close();
        } finally { daemon.stopRcd(); }
    }

    @Test public void uncheckedFailureAfterSpawnRetainsStartupLeaseUntilActualExit() throws Exception {
        assertFalse(NativeSessionGate.GLOBAL.isBusy());
        RcloneRcd daemon = daemon();
        PendingProcess child = new PendingProcess();
        Field process = RcloneRcd.class.getDeclaredField("rcd");
        process.setAccessible(true);
        try {
            assertThrows(IllegalStateException.class, () -> daemon.startRcdWithStartup(() -> {
                try { process.set(daemon, child); }
                catch (IllegalAccessException inaccessible) { throw new IllegalStateException(inaccessible); }
                throw new IllegalStateException("synthetic failure during native pipe setup");
            }));
            assertTrue("Live child still owns startup admission", NativeSessionGate.GLOBAL.isBusy());
            assertThrows(IOException.class, NativeSessionGate.GLOBAL::acquireCommand);
        } finally { child.finish(); }
        assertTrue("Actual child exit must release the startup admission", daemon.awaitNativeExit(2000));
        assertFalse(NativeSessionGate.GLOBAL.isBusy());
    }

    @Test public void confirmedSuccessIsClaimedBeforeProcessExitAndNeverReplayed() throws Exception {
        RcloneRcd daemon = daemon();
        List<RcloneRcd.JobStatusResponse> received = new ArrayList<>();
        daemon.jobsHandlers.put(19, received::add);
        RcloneRcd.JobStatusResponse success = finished(19, true);
        RcloneRcd.JobStatusHandler claim = daemon.claimJobStatus(19, success);
        assertNotNull(claim);
        exit(daemon);
        claim.handleJobStatus(success);
        assertEquals(1, received.size());
        assertSame(success, received.get(0));
        assertNull(daemon.claimJobStatus(19, success));
    }

    @Test public void exitFanoutUsesAlreadyConfirmedTerminalStatusExactlyOnce() throws Exception {
        RcloneRcd daemon = daemon();
        List<RcloneRcd.JobStatusResponse> received = new ArrayList<>();
        daemon.jobsHandlers.put(23, received::add);
        RcloneRcd.JobStatusResponse success = finished(23, true);
        daemon.lastStatus.put(23, success);
        exit(daemon);
        assertEquals(1, received.size());
        assertSame("Confirmed successful result must survive exit", success, received.get(0));
        assertNull(daemon.claimJobStatus(23, success));
    }

    @Test public void exitBeforeConfirmationReportsSingleFailureAndRejectsLateStatus() throws Exception {
        RcloneRcd daemon = daemon();
        List<RcloneRcd.JobStatusResponse> received = new ArrayList<>();
        daemon.jobsHandlers.put(29, received::add);
        exit(daemon);
        assertNull(daemon.claimJobStatus(29, finished(29, true)));
        assertEquals(1, received.size());
        assertFalse(received.get(0).success);
        assertTrue(received.get(0).finished);
    }
}
