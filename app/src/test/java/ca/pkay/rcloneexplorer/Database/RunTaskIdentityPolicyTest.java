package ca.pkay.rcloneexplorer.Database;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunTaskIdentityPolicyTest {
    @Test
    public void legacyTaskMustMatchProfileIdAndBothSnapshots() {
        assertTrue(RunTaskIdentityPolicy.matchesLegacyTask(
                17L, 17L, "profile-fingerprint", "profile-fingerprint", "profile-fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesLegacyTask(
                17L, 18L, "profile-fingerprint", "profile-fingerprint", "profile-fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesLegacyTask(
                null, 17L, "profile-fingerprint", "profile-fingerprint", "profile-fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesLegacyTask(
                17L, 17L, "changed-profile", "profile-fingerprint", "profile-fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesLegacyTask(
                17L, 17L, "profile-fingerprint", "profile-fingerprint", "changed-payload"));
    }

    @Test
    public void ephemeralTaskMustMatchStableProfileIdAndSnapshots() {
        assertTrue(RunTaskIdentityPolicy.matchesEphemeralTask(
                null, "profile-id", "profile-id", "fingerprint", "fingerprint",
                "profile-id", "fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesEphemeralTask(
                17L, "profile-id", "profile-id", "fingerprint", "fingerprint",
                "profile-id", "fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesEphemeralTask(
                null, "profile-id", "profile-id", "fingerprint", "fingerprint",
                "other-profile", "fingerprint"));
        assertFalse(RunTaskIdentityPolicy.matchesEphemeralTask(
                null, "profile-id", "profile-id", "fingerprint", "fingerprint",
                "profile-id", "other-fingerprint"));
    }
}
