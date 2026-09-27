package ca.pkay.rcloneexplorer.Database

enum class RunState(val wireValue: String, val terminal: Boolean = false) {
    QUEUED("QUEUED"),
    PREFLIGHT("PREFLIGHT"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS", true),
    FAILED("FAILED", true),
    CANCELLED("CANCELLED", true),
    INTERRUPTED("INTERRUPTED"),
    DEFERRED("DEFERRED", true),
    AUTH_REQUIRED("AUTH_REQUIRED", true),
    RATE_LIMITED("RATE_LIMITED", true),
    BLOCKED("BLOCKED", true),
    RECOVERY_REQUIRED("RECOVERY_REQUIRED");

    companion object {
        fun fromWireValue(value: String?): RunState =
            values().firstOrNull { it.wireValue == value } ?: RECOVERY_REQUIRED

        val activeValues = listOf(QUEUED, PREFLIGHT, RUNNING)
    }
}

data class RunRecord(
    val runId: String,
    val profileId: String,
    val profileRevision: Long,
    val profileFingerprint: String,
    val requestedMode: ProfileMode,
    val endpointIdentity: String,
    val settings: String,
    val engineRef: String,
    val state: RunState,
    val reason: String?,
    val requestedAt: Long,
    val dueAt: Long?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val ownerToken: String,
    val ownerGeneration: Long,
    val cancellationRequested: Boolean,
    val successfulItems: Long?,
    val failedItems: Long?,
    val conflictItems: Long?,
    val unknownItems: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Exact filter text captured at queue time; cleared when the run leaves active execution. */
    val filterSnapshot: String? = null
)

class RunRejectedException(message: String) : IllegalStateException(message)
