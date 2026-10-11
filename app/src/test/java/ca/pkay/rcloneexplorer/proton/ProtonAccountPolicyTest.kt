package ca.pkay.rcloneexplorer.proton

import org.junit.Assert.*
import org.junit.Test

class ProtonAccountPolicyTest {
    @Test fun authenticAccountErrorsRequireDistinctUserActions() {
        assertEquals(ProtonAccountPolicy.Action.RECONNECT, ProtonAccountPolicy.classify("Proton authentication failed: invalid credentials"))
        assertEquals(ProtonAccountPolicy.Action.TWO_FACTOR, ProtonAccountPolicy.classify("Invalid verification code"))
        assertEquals(ProtonAccountPolicy.Action.MAILBOX_PASSWORD, ProtonAccountPolicy.classify("Incorrect mailbox password"))
        assertEquals(ProtonAccountPolicy.Action.CHALLENGE, ProtonAccountPolicy.classify("Human verification required"))
    }
    @Test fun unrelatedFailuresDoNotEraseOrPauseCredentials() {
        for (message in listOf("network timeout", "directory not found: authentication failed.txt",
                "permission denied: invalid credentials.txt", "signature verification failed", "quota exceeded")) {
            assertNull(message, ProtonAccountPolicy.classify(message))
        }
    }
    @Test fun declaredUsernameAliasesShareOnlyTheirOwnIdentity() {
        assertEquals(ProtonAccountPolicy.identity(" Person@Example.com "), ProtonAccountPolicy.identity("person@example.com"))
        assertNotEquals(ProtonAccountPolicy.identity("one@example.com"), ProtonAccountPolicy.identity("two@example.com"))
        assertFalse(ProtonAccountPolicy.identity("one@example.com").contains("example"))
    }
}
