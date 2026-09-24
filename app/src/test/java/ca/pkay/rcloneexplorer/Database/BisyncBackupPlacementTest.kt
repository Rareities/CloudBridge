package ca.pkay.rcloneexplorer.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BisyncBackupPlacementTest {
    @Test
    fun separatedMatchingCandidatesAreOnlyStructurallyValid() {
        val left = endpoint('a', "/notes")
        val right = endpoint('b', "vault")

        val assessment = BisyncBackupPlacementPolicy.assess(
            left,
            right,
            candidate('a', "/history/run-a"),
            candidate('b', "history/run-b")
        )

        assertEquals(
            BisyncBackupPlacementIssue.SHAPE_VALID_REQUIRES_DURABLE_RESERVATION,
            assessment.issue
        )
        assertTrue(assessment.placementShapeValid)
        assertFalse(assessment.mutationPermitted)
    }

    @Test
    fun unknownIdentityAndAccountMismatchFailClosed() {
        val left = endpoint('a', "notes")
        val right = endpoint('b', "vault")

        val unresolved = BisyncBackupPlacementPolicy.assess(
            left,
            right,
            BisyncBackupCandidateEvidence(null, BisyncEndpointScope.unknown()),
            candidate('b', "history/run-b")
        )
        assertEquals(BisyncBackupPlacementIssue.BACKUP_CANDIDATE_IDENTITY_UNRESOLVED, unresolved.issue)
        assertFalse(unresolved.mutationPermitted)

        val mismatched = BisyncBackupPlacementPolicy.assess(
            left,
            right,
            candidate('b', "other-account/history"),
            candidate('b', "history/run-b")
        )
        assertEquals(BisyncBackupPlacementIssue.BACKUP_CANDIDATE_ACCOUNT_MISMATCH, mismatched.issue)
    }

    @Test
    fun candidatesMustBeOutsideBothSyncRootsAndEachOther() {
        val left = endpoint('a', "notes")
        val right = endpoint('b', "vault")
        val rightBackup = candidate('b', "history/run-b")

        val insideSyncRoot = BisyncBackupPlacementPolicy.assess(
            left,
            right,
            candidate('a', "notes/history"),
            rightBackup
        )
        assertEquals(
            BisyncBackupPlacementIssue.BACKUP_CANDIDATE_OVERLAPS_SYNC_ENDPOINT,
            insideSyncRoot.issue
        )

        val crossEndpointOverlap = BisyncBackupPlacementPolicy.assess(
            endpoint('a', "notes"),
            endpoint('a', "vault"),
            candidate('a', "vault/history"),
            candidate('a', "history/run-b")
        )
        assertEquals(
            BisyncBackupPlacementIssue.BACKUP_CANDIDATE_OVERLAPS_SYNC_ENDPOINT,
            crossEndpointOverlap.issue
        )

        val sameAccountLeft = endpoint('a', "notes")
        val sameAccountRight = endpoint('a', "vault")
        val overlappingBackups = BisyncBackupPlacementPolicy.assess(
            sameAccountLeft,
            sameAccountRight,
            candidate('a', "history/run"),
            candidate('a', "history/run/child")
        )
        assertEquals(BisyncBackupPlacementIssue.BACKUP_CANDIDATES_OVERLAP, overlappingBackups.issue)
    }

    @Test
    fun unresolvedOrOverlappingSyncRootsCannotReceiveBackupPlan() {
        val overlapping = BisyncBackupPlacementPolicy.assess(
            endpoint('a', "notes"),
            endpoint('a', "notes/child"),
            candidate('a', "history/left"),
            candidate('a', "history/right")
        )
        assertEquals(BisyncBackupPlacementIssue.SYNC_ENDPOINTS_OVERLAP, overlapping.issue)

        val unresolved = BisyncBackupPlacementPolicy.assess(
            BisyncEndpointEvidence(null, BisyncEndpointScope.unknown(), null),
            endpoint('b', "vault"),
            candidate('a', "history/left"),
            candidate('b', "history/right")
        )
        assertEquals(BisyncBackupPlacementIssue.ENDPOINT_IDENTITY_UNRESOLVED, unresolved.issue)
    }

    private fun endpoint(storage: Char, path: String): BisyncEndpointEvidence {
        val identity = digest(storage)
        return BisyncEndpointEvidence(identity, BisyncEndpointScope.from(identity, path), true)
    }

    private fun candidate(storage: Char, path: String): BisyncBackupCandidateEvidence {
        val identity = digest(storage)
        return BisyncBackupCandidateEvidence(identity, BisyncEndpointScope.from(identity, path))
    }

    private fun digest(value: Char): String = value.toString().repeat(64)
}
