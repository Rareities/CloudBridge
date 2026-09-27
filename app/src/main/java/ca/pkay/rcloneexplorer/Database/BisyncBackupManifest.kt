package ca.pkay.rcloneexplorer.Database

enum class BisyncBackupSide(val wireValue: String) {
    LEFT("LEFT"),
    RIGHT("RIGHT")
}

enum class BisyncBackupManifestState(val wireValue: String) {
    PENDING_VALIDATION("PENDING_VALIDATION"),
    BACKUP_VERIFIED("BACKUP_VERIFIED"),
    MUTATION_IN_PROGRESS("MUTATION_IN_PROGRESS"),
    RECOVERY_REQUIRED("RECOVERY_REQUIRED"),
    RESTORE_REQUIRED("RESTORE_REQUIRED"),
    RESTORE_IN_PROGRESS("RESTORE_IN_PROGRESS"),
    RESTORE_VERIFIED("RESTORE_VERIFIED"),
    RETAINED("RETAINED"),
    INVALIDATED("INVALIDATED");

    companion object {
        fun fromWireValue(value: String): BisyncBackupManifestState =
            values().firstOrNull { it.wireValue == value }
                ?: throw BisyncBackupManifestCorruptException("Stored backup-manifest state is unknown")
    }
}

/** A provider locator is persisted for future recovery, but is not evidence the path exists or is fresh. */
data class BisyncBackupLocationRecord(
    val side: BisyncBackupSide,
    val accountFingerprint: String,
    val endpointScopeFingerprint: String,
    val backupScopeFingerprint: String,
    val locator: String,
    val locatorFingerprint: String,
    val createdAt: Long
)

/** Immutable owner snapshot for one pending pair; this record never authorizes mutation. */
data class BisyncBackupManifestRecord(
    val operationId: String,
    val runId: String,
    val profileId: String,
    val profileRevision: Long,
    val profileFingerprint: String,
    val engineRef: String,
    val stateVersion: Int,
    val preflightFingerprint: String,
    val observationFingerprint: String,
    val previewId: String,
    val previewFingerprint: String,
    val preflightCheckedAt: Long,
    val initializationMode: BisyncPreviewResyncMode,
    val filterFingerprint: String,
    val comparisonMode: BisyncComparisonMode,
    val maxDeletePercent: Int,
    val maxDeleteCount: Int,
    val userConfirmedAt: Long,
    val ownerToken: String,
    val ownerGeneration: Long,
    val state: BisyncBackupManifestState,
    val createdAt: Long,
    val updatedAt: Long,
    val left: BisyncBackupLocationRecord,
    val right: BisyncBackupLocationRecord
) {
    /** No state in this persistence-only slice can grant native mutation permission. */
    val mutationPermitted: Boolean
        get() = false
}

data class BisyncBackupManifestRequest(
    val run: RunRecord,
    val preflight: BisyncPreflightInput,
    val previewId: String,
    val leftBackupLocator: String,
    val rightBackupLocator: String,
    /** Caller-supplied timestamp ordering only; not proof of a displayed or explicit user action. */
    val userConfirmedAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)

class BisyncBackupManifestRejectedException(message: String) : IllegalStateException(message)

class BisyncBackupManifestConstraintException : IllegalStateException(
    "A Bisync backup reservation could not satisfy persistence integrity constraints"
)

class BisyncBackupManifestCorruptException(message: String) : IllegalStateException(message)
