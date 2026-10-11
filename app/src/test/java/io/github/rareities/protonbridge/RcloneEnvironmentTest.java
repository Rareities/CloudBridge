package io.github.rareities.protonbridge;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RcloneEnvironmentTest {
    @Test public void nativeDnsUsesAndroidPolicyAndDoesNotSupplyRawResolverAddresses() {
        String[] env = new Rclone(ApplicationProvider.getApplicationContext()).getRcloneEnv();
        assertTrue(Arrays.asList(env).contains("GODEBUG=netdns=cgo"));
        for(String value : env) assertFalse(value.startsWith("RCLONE_DNS_SERVERS="));
    }
}
