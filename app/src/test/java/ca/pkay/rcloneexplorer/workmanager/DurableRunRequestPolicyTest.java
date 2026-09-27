package ca.pkay.rcloneexplorer.workmanager;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DurableRunRequestPolicyTest {
    @Test
    public void identityRequiresBothRunAndOwnerTokens() {
        assertFalse(DurableRunRequestPolicy.hasCompleteIdentity(null, null));
        assertFalse(DurableRunRequestPolicy.hasCompleteIdentity("run-id", null));
        assertFalse(DurableRunRequestPolicy.hasCompleteIdentity(null, "owner"));
        assertFalse(DurableRunRequestPolicy.hasCompleteIdentity(" ", "owner"));
        assertFalse(DurableRunRequestPolicy.hasCompleteIdentity("run-id", "\t"));
        assertTrue(DurableRunRequestPolicy.hasCompleteIdentity("run-id", "owner"));
    }

    @Test
    public void requestRequiresExactlyOneTaskPayloadAndCompleteIdentity() {
        assertTrue(DurableRunRequestPolicy.isValidRequest(true, false, "run-id", "owner"));
        assertTrue(DurableRunRequestPolicy.isValidRequest(false, true, "run-id", "owner"));
        assertFalse(DurableRunRequestPolicy.isValidRequest(false, false, "run-id", "owner"));
        assertFalse(DurableRunRequestPolicy.isValidRequest(true, true, "run-id", "owner"));
        assertFalse(DurableRunRequestPolicy.isValidRequest(true, false, "run-id", null));
    }
}
