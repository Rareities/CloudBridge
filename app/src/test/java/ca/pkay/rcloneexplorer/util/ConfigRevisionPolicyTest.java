package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConfigRevisionPolicyTest {
    private static final String BEFORE = "00000000-0000-0000-0000-000000000001";
    private static final String AFTER = "00000000-0000-0000-0000-000000000002";

    @Test
    public void acceptsOnlyCanonicalOpaqueUuidRevisions() {
        assertTrue(ConfigRevisionPolicy.isValidRevision(BEFORE));
        assertFalse(ConfigRevisionPolicy.isValidRevision(null));
        assertFalse(ConfigRevisionPolicy.isValidRevision(""));
        assertFalse(ConfigRevisionPolicy.isValidRevision("provider=secret"));
        assertFalse(ConfigRevisionPolicy.isValidRevision("1-1-1-1-1"));
    }

    @Test
    public void sameNameConfigReplacementInvalidatesPreviouslyCapturedRevision() {
        assertTrue(ConfigRevisionPolicy.matches(BEFORE, BEFORE));
        assertFalse(ConfigRevisionPolicy.matches(BEFORE, AFTER));
        assertFalse(ConfigRevisionPolicy.matches(BEFORE, null));
        assertFalse(ConfigRevisionPolicy.matches(null, BEFORE));
    }
}
