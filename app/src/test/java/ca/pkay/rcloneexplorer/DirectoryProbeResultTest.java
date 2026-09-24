package ca.pkay.rcloneexplorer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectoryProbeResultTest {

    @Test
    public void networkCategoryRequiresFailureExit() {
        assertTrue(new Rclone.DirectoryProbeResult(-1, "network is unreachable").isNetworkError());
        assertFalse(new Rclone.DirectoryProbeResult(0, "network is unreachable").isNetworkError());
        assertFalse(new Rclone.DirectoryProbeResult(7, "rclone probe failed").isNetworkError());
    }

    @Test
    public void onlySanitizedAuthenticationCategoryRequestsReauthentication() {
        assertTrue(new Rclone.DirectoryProbeResult(1, "authentication required").isAuthenticationError());
        assertFalse(new Rclone.DirectoryProbeResult(1, "rclone probe failed").isAuthenticationError());
        assertFalse(new Rclone.DirectoryProbeResult(0, "authentication required").isAuthenticationError());
    }

    @Test
    public void failureLineClassificationIsTypedAndDoesNotRetainProviderText() {
        assertEquals(Rclone.DirectoryProbeResult.FailureCategory.AUTHENTICATION,
                Rclone.DirectoryProbeResult.classifyFailureLine("oauth: invalid_grant"));
        assertEquals(Rclone.DirectoryProbeResult.FailureCategory.RATE_LIMITED,
                Rclone.DirectoryProbeResult.classifyFailureLine("HTTP 429: too many requests"));
        assertEquals(Rclone.DirectoryProbeResult.FailureCategory.INTEGRITY,
                Rclone.DirectoryProbeResult.classifyFailureLine("checksum mismatch"));
        assertEquals(Rclone.DirectoryProbeResult.FailureCategory.OTHER,
                Rclone.DirectoryProbeResult.classifyFailureLine("permission denied"));

        Rclone.DirectoryProbeResult unknown = new Rclone.DirectoryProbeResult(1, "secret-bearing provider text");
        assertEquals("rclone probe failed", unknown.getStderr());
    }

    @Test
    public void knownRateAndIntegrityCategoriesDoNotLookLikeAuthentication() {
        assertTrue(new Rclone.DirectoryProbeResult(1, "provider rate limited").isRateLimited());
        assertTrue(new Rclone.DirectoryProbeResult(1, "integrity check failed").isIntegrityError());
        assertFalse(new Rclone.DirectoryProbeResult(1, "provider rate limited").isAuthenticationError());
        assertFalse(new Rclone.DirectoryProbeResult(1, "integrity check failed").isAuthenticationError());
    }
}
