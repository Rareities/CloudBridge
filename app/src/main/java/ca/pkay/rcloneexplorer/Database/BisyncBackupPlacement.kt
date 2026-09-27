package ca.pkay.rcloneexplorer.Database

/** Hash-only evidence for one proposed native --backup-dir target. */
data class BisyncBackupCandidateEvidence(
    val accountFingerprint: String?,
    val scope: BisyncEndpointScope
)

enum class BisyncBackupPlacementIssue {
    ENDPOINT_IDENTITY_UNRESOLVED,
    SYNC_ENDPOINTS_OVERLAP,
    BACKUP_CANDIDATE_IDENTITY_UNRESOLVED,
    BACKUP_CANDIDATE_ACCOUNT_MISMATCH,
    BACKUP_CANDIDATE_OVERLAPS_SYNC_ENDPOINT,
    BACKUP_CANDIDATES_OVERLAP,
    SHAPE_VALID_REQUIRES_DURABLE_RESERVATION
}

/**
 * A pure structural assessment only. Even a valid shape is not a reservation or permission to
 * mutate: callers still need a fresh run-owned location, durable manifest, restart reconciliation,
 * provider write/read proof, and mutation-boundary identity revalidation.
 */
data class BisyncBackupPlacementAssessment(val issue: BisyncBackupPlacementIssue) {
    val placementShapeValid: Boolean
        get() = issue == BisyncBackupPlacementIssue.SHAPE_VALID_REQUIRES_DURABLE_RESERVATION

    /** This model intentionally cannot authorize native mutation. */
    val mutationPermitted: Boolean
        get() = false
}

/**
 * Checks only identity matching and path-scope separation for two proposed backup roots. It has
 * no filesystem/provider/database side effects and deliberately returns no usable path.
 */
object BisyncBackupPlacementPolicy {
    private val digestPattern = Regex("^[0-9a-f]{64}$")

    fun assess(
        left: BisyncEndpointEvidence,
        right: BisyncEndpointEvidence,
        backupForLeft: BisyncBackupCandidateEvidence,
        backupForRight: BisyncBackupCandidateEvidence
    ): BisyncBackupPlacementAssessment {
        if (!validIdentity(left.accountFingerprint, left.scope) ||
            !validIdentity(right.accountFingerprint, right.scope)) {
            return result(BisyncBackupPlacementIssue.ENDPOINT_IDENTITY_UNRESOLVED)
        }
        if (left.scope.overlaps(right.scope)) {
            return result(BisyncBackupPlacementIssue.SYNC_ENDPOINTS_OVERLAP)
        }

        if (!validIdentity(backupForLeft.accountFingerprint, backupForLeft.scope) ||
            !validIdentity(backupForRight.accountFingerprint, backupForRight.scope)) {
            return result(BisyncBackupPlacementIssue.BACKUP_CANDIDATE_IDENTITY_UNRESOLVED)
        }
        if (backupForLeft.accountFingerprint != left.accountFingerprint ||
            backupForRight.accountFingerprint != right.accountFingerprint) {
            return result(BisyncBackupPlacementIssue.BACKUP_CANDIDATE_ACCOUNT_MISMATCH)
        }
        if (backupForLeft.scope.overlaps(left.scope) || backupForLeft.scope.overlaps(right.scope) ||
            backupForRight.scope.overlaps(left.scope) || backupForRight.scope.overlaps(right.scope)) {
            return result(BisyncBackupPlacementIssue.BACKUP_CANDIDATE_OVERLAPS_SYNC_ENDPOINT)
        }
        if (backupForLeft.scope.overlaps(backupForRight.scope)) {
            return result(BisyncBackupPlacementIssue.BACKUP_CANDIDATES_OVERLAP)
        }
        return result(BisyncBackupPlacementIssue.SHAPE_VALID_REQUIRES_DURABLE_RESERVATION)
    }

    private fun validIdentity(accountFingerprint: String?, scope: BisyncEndpointScope): Boolean =
        accountFingerprint != null && digestPattern.matches(accountFingerprint) &&
            scope.isResolved && scope.storageFingerprint == accountFingerprint

    private fun result(issue: BisyncBackupPlacementIssue) = BisyncBackupPlacementAssessment(issue)
}
