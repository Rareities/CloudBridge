package io.github.rareities.protonbridge.util;
import io.github.rareities.protonbridge.Rclone;
import org.junit.Test;
import static org.junit.Assert.*;
public class HealthProbeClassificationTest {
    @Test public void onlyPositiveAuthenticationEvidenceRequestsReconnect() {
        for (String error : new String[]{"invalid_grant", "HTTP error 401: Unauthorized", "authentication failed", "session expired"})
            assertTrue(error, new Rclone.DirectoryProbeResult(1, error).isAuthenticationError());
        for (String error : new String[]{"HTTP 403: permission denied", "HTTP 429: quota exhausted", "signature verification failed", "Another native operation is active", "Health probe timed out", "directory not found", "cannot read /unauthorized/photos", "error reading folder unauthorized"})
            assertFalse(error, new Rclone.DirectoryProbeResult(1, error).isAuthenticationError());
        assertFalse(new Rclone.DirectoryProbeResult(0, "old token expired").isAuthenticationError());
        assertFalse(new Rclone.DirectoryProbeResult(-1, "unauthorized").isAuthenticationError());
    }
}
