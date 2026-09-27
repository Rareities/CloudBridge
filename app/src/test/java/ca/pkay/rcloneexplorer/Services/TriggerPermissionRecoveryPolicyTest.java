package ca.pkay.rcloneexplorer.Services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TriggerPermissionRecoveryPolicyTest {
    @Test
    public void doesNotRetryWithoutPendingReconciliation() {
        assertFalse(TriggerPermissionRecoveryPolicy.shouldAttempt(false, true));
        assertFalse(TriggerPermissionRecoveryPolicy.shouldAttempt(false, false));
    }

    @Test
    public void waitsWhileExactAlarmPermissionIsDenied() {
        assertFalse(TriggerPermissionRecoveryPolicy.shouldAttempt(true, false));
    }

    @Test
    public void retriesOncePermissionIsRestored() {
        assertTrue(TriggerPermissionRecoveryPolicy.shouldAttempt(true, true));
    }

    @Test
    public void doesNotClearMarkerForIncompleteOrRevokedPass() {
        assertFalse(TriggerPermissionRecoveryPolicy.canClearPending(true, false));
        assertFalse(TriggerPermissionRecoveryPolicy.canClearPending(false, true));
        assertTrue(TriggerPermissionRecoveryPolicy.canClearPending(true, true));
    }
}
