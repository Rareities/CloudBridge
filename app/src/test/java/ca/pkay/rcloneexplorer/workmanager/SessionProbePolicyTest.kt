package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProbePolicyTest {
    @Test
    fun probesKnownOauthProviderOnlyWithStoredCredential() {
        assertTrue(SessionProbePolicy.shouldProbe("dropbox", true, true, false, false))
        assertTrue(SessionProbePolicy.shouldProbe("dropbox", true, false, true, false))
        assertFalse(SessionProbePolicy.shouldProbe("dropbox", true, false, false, false))
    }

    @Test
    fun tokenFieldAloneDoesNotEnableUnknownProviderProbe() {
        assertFalse(SessionProbePolicy.shouldProbe("custom-backend", false, true, false, false))
    }

    @Test
    fun explicitCustomCapabilitiesPreserveProtonAndInternxtCredentialChecks() {
        assertTrue(SessionProbePolicy.shouldProbe("ProtonDrive", false, true, false, false))
        assertTrue(SessionProbePolicy.shouldProbe("internxt", false, false, false, true))
        assertFalse(SessionProbePolicy.shouldProbe("internxt", false, false, false, false))
    }

    @Test
    fun blankProviderIsNeverProbed() {
        assertFalse(SessionProbePolicy.shouldProbe(" ", true, true, false, false))
    }
}
