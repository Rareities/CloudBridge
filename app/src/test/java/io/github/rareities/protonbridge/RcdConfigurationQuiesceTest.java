package io.github.rareities.protonbridge;

import android.app.Application;
import io.github.rareities.protonbridge.util.NativeSessionGate;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RcdConfigurationQuiesceTest {
    @Test public void reconnectCannotCancelAnotherNativeSession() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NativeSessionGate.Lease lease = NativeSessionGate.GLOBAL.acquireCommand();
        try {
            assertTrue(executor.submit(() -> {
                try { RcloneRcd.quiesceForConfigurationChange(); return false; }
                catch (IOException expected) { return true; }
            }).get(3, TimeUnit.SECONDS));
            assertTrue(NativeSessionGate.GLOBAL.isBusy());
        } finally { lease.close(); executor.shutdownNow(); }
    }
}
