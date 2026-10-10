package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowupPolicyTest {
    @Test fun selfAndIndirectCyclesAreRejected() {
        assertFalse(FollowupPolicy.canQueue(listOf(1), 1))
        assertFalse(FollowupPolicy.canQueue(listOf(1, 2, 3), 1))
        assertTrue(FollowupPolicy.canQueue(listOf(1, 2), 3))
    }
    @Test fun longChainsCannotCreateUnboundedWork() {
        assertFalse(FollowupPolicy.canQueue((1L..32L).toList(), 33))
        assertFalse(FollowupPolicy.canQueue(emptyList(), -1))
    }
}
