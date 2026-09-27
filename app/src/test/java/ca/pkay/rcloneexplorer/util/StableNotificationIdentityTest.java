package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class StableNotificationIdentityTest {

    @Test
    public void avoidsKnownStringHashCollisionAndIsStableForUnicodeNames() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(StableNotificationIdentity.forRemote("Aa"),
                StableNotificationIdentity.forRemote("BB"));
        assertEquals(StableNotificationIdentity.forRemote("☁️ Proton"),
                StableNotificationIdentity.forRemote("☁️ Proton"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyRemoteName() {
        StableNotificationIdentity.forRemote("");
    }
}
