package ca.pkay.rcloneexplorer.util;

import java.io.Closeable;
import org.junit.Test;
import static org.junit.Assert.*;

public class DemandTrackerTest {
    @Test public void openStreamsPreventIdleShutdownBeyondGrace() throws Exception {
        DemandTracker tracker = new DemandTracker();
        Closeable a = tracker.acquire(); Closeable b = tracker.acquire();
        tracker.touch(0);
        assertFalse(tracker.isIdle(100, 10));
        a.close(); a.close(); tracker.touch(0);
        assertTrue(tracker.hasDemand());
        assertFalse(tracker.isIdle(100, 10));
        b.close(); tracker.touch(0);
        assertFalse(tracker.hasDemand());
        assertTrue(tracker.isIdle(100, 10));
    }
    @Test public void newUseRestartsGraceWithoutPolling() {
        DemandTracker tracker = new DemandTracker();
        tracker.touch(10);
        assertFalse(tracker.isIdle(19, 10));
        assertTrue(tracker.isIdle(20, 10));
        tracker.touch(20);
        assertFalse(tracker.isIdle(20, 10));
    }
}
