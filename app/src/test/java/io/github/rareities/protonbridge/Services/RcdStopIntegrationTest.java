package io.github.rareities.protonbridge.Services;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Stop notification cancellation path with a real Android service and a controlled document lease. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RcdStopIntegrationTest {
    private static int liveStreams(RcdService service) throws Exception {
        Field field = RcdService.class.getDeclaredField("demand");
        field.setAccessible(true);
        return ((RcDemandState) field.get(service)).streamCount();
    }

    @Test public void stopAllRequestsCancellationButExistingDocumentLeaseOwnsItsExit() throws Exception {
        ServiceController<RcdService> controller = Robolectric.buildService(RcdService.class).create();
        try {
            RcdService service = controller.get();
            CountDownLatch cancelled = new CountDownLatch(1);
            RcdService.DocumentLease lease = service.acquireDocumentLease();
            lease.setCancellation(cancelled::countDown);
            assertEquals(1, liveStreams(service));
            Intent stop = new Intent(service, RcdService.class).setAction(RcdService.ACTION_STOP_FOREGROUND);
            service.onStartCommand(stop, 0, 1);
            assertTrue("Stop should signal document cancellation", cancelled.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, service::acquireDocumentLease);
            // The client still owns the lease until its native exit callback closes it.
            assertEquals(1, liveStreams(service));
            lease.close();
            lease.close();
            assertEquals(0, liveStreams(service));
        } finally { controller.destroy(); }
    }

    @Test public void timeoutCancelsBothDocumentsAndKeepsTheirLeasesUntilEachExit() throws Exception {
        ServiceController<RcdService> controller = Robolectric.buildService(RcdService.class).create();
        try {
            RcdService service = controller.get();
            RcdService.DocumentLease first = service.acquireDocumentLease();
            RcdService.DocumentLease second = service.acquireDocumentLease();
            CountDownLatch cancellations = new CountDownLatch(2);
            first.setCancellation(cancellations::countDown);
            second.setCancellation(cancellations::countDown);
            assertEquals(2, liveStreams(service));
            service.onTimeout(2, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            assertTrue(service.isShutdown());
            assertTrue("Timeout must signal both running streams", cancellations.await(2, TimeUnit.SECONDS));
            assertEquals("A stop request cannot release still-live process leases", 2, liveStreams(service));
            assertThrows(IllegalStateException.class, service::acquireDocumentLease);
            first.close();
            assertEquals(1, liveStreams(service));
            second.close();
            assertEquals(0, liveStreams(service));
        } finally { controller.destroy(); }
    }

    @Test public void registeringCancellationAfterStopStillSignalsStreamAndClosedLeaseIsIdempotent() throws Exception {
        ServiceController<RcdService> controller = Robolectric.buildService(RcdService.class).create();
        try {
            RcdService service = controller.get();
            RcdService.DocumentLease lease = service.acquireDocumentLease();
            service.onTimeout(3, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            CountDownLatch cancellation = new CountDownLatch(1);
            lease.setCancellation(cancellation::countDown);
            assertTrue("Late callback registration must observe prior stop", cancellation.await(2, TimeUnit.SECONDS));
            assertEquals(1, liveStreams(service));
            lease.close();
            lease.close();
            assertEquals(0, liveStreams(service));
        } finally { controller.destroy(); }
    }
}
