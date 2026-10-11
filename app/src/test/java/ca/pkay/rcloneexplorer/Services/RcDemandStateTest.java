package ca.pkay.rcloneexplorer.Services;

import org.junit.Test;
import static org.junit.Assert.*;

public class RcDemandStateTest {
    @Test public void concurrentPipeLeasesKeepServiceBusyUntilLastClose() {
        RcDemandState demand = new RcDemandState();
        RcDemandState.Lease read = demand.openStream();
        RcDemandState.Lease write = demand.openStream();
        assertTrue(demand.isBusy(false));
        read.close();
        read.close();
        assertEquals(1, demand.streamCount());
        assertTrue(demand.isBusy(false));
        write.close();
        assertEquals(0, demand.streamCount());
        assertFalse(demand.isBusy(false));
    }

    @Test public void networkRequestOrQueuedRcJobStillPreventsIdle() {
        RcDemandState demand = new RcDemandState();
        assertTrue(demand.isBusy(true));
        assertFalse(demand.isBusy(false));
    }
}
