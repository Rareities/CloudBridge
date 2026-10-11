package io.github.rareities.protonbridge;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ProviderLifecycleTest {
    @Test public void unavailableProviderCannotDereferenceAnAbsentDaemon() {
        VirtualContentProvider provider = new VirtualContentProvider();
        assertNull(provider.getFileItem("remote:/uncached-file.txt"));
        assertNull(provider.getFileItem(null));
        assertNull(provider.getFileItem("malformed-id"));
    }
}
