package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Stable, persisted mode names. Legacy numeric direction values never cross this boundary. */
enum class ProfileMode(val wireValue: String) {
    SYNC_ONE_WAY("SYNC_ONE_WAY"),
    COPY_ONE_WAY("COPY_ONE_WAY"),
    CLOUD_TO_CLOUD("CLOUD_TO_CLOUD"),
    BISYNC("BISYNC"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromWireValue(value: String?): ProfileMode =
            values().firstOrNull { it.wireValue == value } ?: UNKNOWN

        fun fromLegacyDirection(direction: Int): ProfileMode = when (direction) {
            SyncDirectionObject.SYNC_LOCAL_TO_REMOTE,
            SyncDirectionObject.SYNC_REMOTE_TO_LOCAL -> SYNC_ONE_WAY
            SyncDirectionObject.COPY_LOCAL_TO_REMOTE,
            SyncDirectionObject.COPY_REMOTE_TO_LOCAL -> COPY_ONE_WAY
            SyncDirectionObject.SYNC_REMOTE_TO_REMOTE,
            SyncDirectionObject.COPY_REMOTE_TO_REMOTE -> CLOUD_TO_CLOUD
            SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL,
            SyncDirectionObject.SYNC_BIDIRECTIONAL -> BISYNC
            else -> UNKNOWN
        }
    }
}

/** Readiness is deliberately separate from execution state. */
enum class ProfileReadiness(val wireValue: String) {
    NEW("NEW"),
    PREFLIGHT_REQUIRED("PREFLIGHT_REQUIRED"),
    INITIALIZATION_REQUIRED("INITIALIZATION_REQUIRED"),
    READY("READY"),
    RUNNING("RUNNING"),
    BLOCKED("BLOCKED"),
    RECOVERY_REQUIRED("RECOVERY_REQUIRED");

    companion object {
        fun fromWireValue(value: String?): ProfileReadiness =
            values().firstOrNull { it.wireValue == value } ?: RECOVERY_REQUIRED
    }
}

data class ProfileSpec(
    val title: String,
    val mode: ProfileMode,
    val endpointIdentity: String,
    val settings: String,
    val fingerprint: String,
    val engineRef: String,
    val readiness: ProfileReadiness,
    val reason: String?
)

data class ProfileRecord(
    val profileId: String,
    val legacyTaskId: Long?,
    val revision: Long,
    val title: String,
    val mode: ProfileMode,
    val endpointIdentity: String,
    val settings: String,
    val fingerprint: String,
    val engineRef: String,
    val readiness: ProfileReadiness,
    val reason: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Converts the old Task row into a versioned semantic snapshot. The serialized values contain
 * no credentials and use length-prefixed fields so path separators cannot create collisions.
 */
object LegacyProfileMapper {
    const val PROFILE_SCHEMA_VERSION = 1

    @JvmOverloads
    fun fromTask(
        task: Task,
        engineRef: String,
        selectedFilterRaw: String? = null,
        selectedFilterMissing: Boolean = false
    ): ProfileSpec {
        val mode = ProfileMode.fromLegacyDirection(task.direction)
        val filterFingerprint = when {
            task.filterId == null -> fingerprintNoFilter()
            selectedFilterMissing -> sha256(canonical("filter-schema", PROFILE_SCHEMA_VERSION.toString(), "missing-filter-id", task.filterId.toString()))
            selectedFilterRaw != null -> fingerprintFilter(selectedFilterRaw)
            else -> sha256(canonical("filter-schema", PROFILE_SCHEMA_VERSION.toString(), "unresolved-filter-id", task.filterId.toString()))
        }
        val readiness = when {
            task.filterId != null && (selectedFilterMissing || selectedFilterRaw == null) -> ProfileReadiness.BLOCKED
            mode == ProfileMode.BISYNC -> ProfileReadiness.RECOVERY_REQUIRED
            mode == ProfileMode.UNKNOWN -> ProfileReadiness.RECOVERY_REQUIRED
            else -> ProfileReadiness.PREFLIGHT_REQUIRED
        }
        val reason = when {
            task.filterId != null && (selectedFilterMissing || selectedFilterRaw == null) ->
                "Selected filter is unavailable; review the profile before running"
            mode == ProfileMode.BISYNC -> "Legacy Bisync requires reviewed profile initialization"
            mode == ProfileMode.UNKNOWN -> "Legacy direction is unsupported and requires repair"
            else -> null
        }
        val endpoint = canonical(
            "remote1", task.remoteType.toString(), task.remoteId, task.remotePath,
            "local", task.localPath,
            "remote2", task.remoteType2.toString(), task.remoteId2, task.remotePath2
        )
        val settings = canonical(
            "schema", PROFILE_SCHEMA_VERSION.toString(),
            "direction", task.direction.toString(),
            "md5", task.md5sum.toString(),
            "wifiOnly", task.wifionly.toString(),
            "filterFingerprint", filterFingerprint,
            "deleteExcluded", task.deleteExcluded.toString(),
            "onFail", task.onFailFollowup?.toString() ?: "null",
            "onSuccess", task.onSuccessFollowup?.toString() ?: "null",
            "transfers", task.transfers?.toString() ?: "null"
        )
        val fingerprint = sha256(canonical(
            "mode", mode.wireValue,
            "endpoint", endpoint,
            "settings", settings,
            "engine", engineRef
        ))
        return ProfileSpec(task.title, mode, endpoint, settings, fingerprint, engineRef, readiness, reason)
    }

    /** Hash exact filter bytes so an edit invalidates readiness without persisting filter text. */
    fun fingerprintFilter(raw: String): String = sha256(canonical(
        "filter-schema", PROFILE_SCHEMA_VERSION.toString(), "filter-content", raw
    ))

    fun fingerprintNoFilter(): String = sha256(canonical(
        "filter-schema", PROFILE_SCHEMA_VERSION.toString(), "filter", "none"
    ))

    /** Stable only for the first legacy migration; deletion retires the mapping before reuse. */
    fun stableIdForLegacyTask(taskId: Long): String =
        UUID.nameUUIDFromBytes("cloudbridge.legacy.task.v1:$taskId".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun canonical(vararg values: String): String =
        values.joinToString("|") { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            "${bytes.size}:$value"
        }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
