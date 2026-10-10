package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHealthPolicyTest {
    @Test fun quotaPathAndCryptoErrorsAreNotExpiry() {
        for (error in listOf("quota exceeded", "403 permission denied", "404 path not found", "signature verification failed", "connection timeout")) {
            assertFalse(SessionHealthPolicy.requiresReconnect(error))
        }
    }
    @Test fun explicitAuthFailuresRequireReconnect() {
        assertTrue(SessionHealthPolicy.requiresReconnect("refresh token revoked"))
        assertTrue(SessionHealthPolicy.requiresReconnect("HTTP 401 unauthorized"))
        assertTrue(SessionHealthPolicy.requiresReconnect("invalid_grant"))
    }
}
