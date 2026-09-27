package ca.pkay.rcloneexplorer.Services;

import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;

public class TriggerAlarmIdentityPolicyTest {
    @Test
    public void idsThatCollideWhenNarrowedToRequestCodeRemainDistinct() {
        long firstId = 7L;
        long secondId = firstId + (1L << 32);

        assertEquals((int) firstId, (int) secondId);
        assertNotEquals(TriggerAlarmIdentityPolicy.dataUri(firstId),
                TriggerAlarmIdentityPolicy.dataUri(secondId));
    }

    @Test
    public void identityPreservesFullLongValue() {
        assertEquals("cloudbridge://trigger/9223372036854775807",
                TriggerAlarmIdentityPolicy.dataUri(Long.MAX_VALUE));
    }
}
