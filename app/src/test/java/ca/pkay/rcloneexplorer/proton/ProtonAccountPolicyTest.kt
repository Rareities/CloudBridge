package ca.pkay.rcloneexplorer.proton

import org.junit.Assert.*
import org.junit.Test

class ProtonAccountPolicyTest {
    @Test fun unrelatedErrorsDoNotRequireReconnect() {
        for (error in listOf("quota exceeded", "path not found", "TLS signature verification failed", "network timeout", "HTTP 503", "HTTP 401 unauthorized file", "Failed to copy /storage/captcha.txt: permission denied", "Failed to copy /storage/human verification folder: no such file")) {
            assertNull(error, ProtonAccountPolicy.classify(error))
        }
    }
    @Test fun recognizesUserActionsWithoutRetainingSecrets() {
        assertEquals(ProtonAccountPolicy.Action.CHALLENGE, ProtonAccountPolicy.classify("Human verification required"))
        assertEquals(ProtonAccountPolicy.Action.TWO_FACTOR, ProtonAccountPolicy.classify("Invalid two-factor code"))
        assertEquals(ProtonAccountPolicy.Action.MAILBOX_PASSWORD, ProtonAccountPolicy.classify("Incorrect mailbox password"))
        assertEquals(ProtonAccountPolicy.Action.RECONNECT, ProtonAccountPolicy.classify("Refresh token was revoked"))
    }
    @Test fun sameDeclaredAccountSharesIdentityWithoutAliasGuessing() {
        assertEquals(ProtonAccountPolicy.identity(" USER@Proton.me "), ProtonAccountPolicy.identity("user@proton.me"))
        assertNotEquals(ProtonAccountPolicy.identity("user@proton.me"), ProtonAccountPolicy.identity("alias@proton.me"))
        assertEquals(64, ProtonAccountPolicy.identity("user@proton.me").length)
    }
}
