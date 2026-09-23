package ca.pkay.rcloneexplorer.Database

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Immutable, path-free snapshot of the settings under which one native Bisync preview ran.
 * This is evidence for review, not an execution token or a snapshot of either endpoint.
 */
data class BisyncPreviewIdentity(
    val profileId: String,
    val profileRevision: Long,
    val profileFingerprint: String,
    val engineRef: String,
    val stateVersion: Int,
    val leftAccountFingerprint: String,
    val leftScopeFingerprint: String,
    val rightAccountFingerprint: String,
    val rightScopeFingerprint: String,
    val filterFingerprint: String,
    val comparisonMode: BisyncComparisonMode,
    /** Null for a compatible accepted baseline; required before queueing an absent-state preview. */
    val initializationMode: BisyncPreviewResyncMode? = null,
    val maxDeletePercent: Int,
    val maxDeleteCount: Int,
    val nativeState: BisyncNativeState,
    val acceptedBaselineFingerprint: String?
) {
    init {
        require(isCanonicalUuid(profileId)) { "Preview profile ID must be a canonical UUID" }
        require(profileRevision > 0) { "Preview profile revision must be positive" }
        require(isDigest(profileFingerprint)) { "Invalid profile fingerprint" }
        require(Regex("^rclone:[A-Za-z0-9._+-]+@[0-9a-f]{40}$").matches(engineRef)) {
            "Preview engine must be an immutable Rareities/rclone pin"
        }
        require(stateVersion > 0) { "Preview state version must be positive" }
        require(isDigest(leftAccountFingerprint) && isDigest(leftScopeFingerprint)) {
            "Invalid left endpoint fingerprint"
        }
        require(isDigest(rightAccountFingerprint) && isDigest(rightScopeFingerprint)) {
            "Invalid right endpoint fingerprint"
        }
        require(isDigest(filterFingerprint)) { "Invalid filter fingerprint" }
        require(initializationMode == null || nativeState == BisyncNativeState.ABSENT) {
            "An initialization preference applies only to absent native state"
        }
        require(maxDeletePercent in 1..100) { "Delete percentage must be between 1 and 100" }
        require(maxDeleteCount > 0) { "Absolute delete limit must be positive" }
        require(nativeState == BisyncNativeState.ABSENT || nativeState == BisyncNativeState.COMPATIBLE) {
            "Unresolved native state cannot be previewed as a normal Bisync run"
        }
        if (nativeState == BisyncNativeState.COMPATIBLE) {
            require(isDigest(acceptedBaselineFingerprint)) { "Compatible state requires its accepted baseline identity" }
        } else {
            require(acceptedBaselineFingerprint == null) { "Absent native state cannot claim an accepted baseline" }
        }
    }

    /** Stable digest over semantic settings only; no raw paths, remote names, or credentials. */
    val fingerprint: String
        get() = sha256(canonical(listOf(
            if (initializationMode == null) "bisync-preview-identity-v1" else "bisync-preview-identity-v2",
            profileId,
            profileRevision.toString(),
            profileFingerprint,
            engineRef,
            stateVersion.toString(),
            leftAccountFingerprint,
            leftScopeFingerprint,
            rightAccountFingerprint,
            rightScopeFingerprint,
            filterFingerprint,
            comparisonMode.wireValue,
            maxDeletePercent.toString(),
            maxDeleteCount.toString(),
            nativeState.name,
            acceptedBaselineFingerprint ?: "none"
        ) + if (initializationMode == null) emptyList() else listOf(
            "initialization-mode",
            initializationMode.wireValue
        )))

    companion object {
        private val digestPattern = Regex("^[0-9a-f]{64}$")

        internal fun isDigest(value: String?): Boolean = value != null && digestPattern.matches(value)

        @JvmStatic
        fun fromPreflight(
            profile: ProfileRecord,
            input: BisyncPreflightInput,
            result: BisyncPreflightResult,
            initializationMode: BisyncPreviewResyncMode? = null
        ): BisyncPreviewIdentity {
            require(profile.mode == ProfileMode.BISYNC) { "Preview requires a Bisync profile" }
            require(input.profileRevision == profile.revision && input.profileFingerprint == profile.fingerprint) {
                "Preflight snapshot does not match the current profile"
            }
            require(input.engineRef == profile.engineRef) { "Preflight engine does not match the profile pin" }
            require(BisyncPreflightPolicy.evaluate(input) == result && result.reason == null) {
                "Preview identity requires the successful result of the current preflight policy"
            }
            val candidate = result.candidateBaseline
                ?: throw IllegalArgumentException("Successful preview preflight must carry its candidate identity")
            require(candidate.profileRevision == profile.revision &&
                candidate.profileFingerprint == profile.fingerprint &&
                candidate.engineRef == input.engineRef &&
                candidate.stateVersion == input.stateVersion &&
                candidate.leftAccountFingerprint == input.left.accountFingerprint &&
                candidate.leftScopeFingerprint == input.left.scope.fingerprint() &&
                candidate.rightAccountFingerprint == input.right.accountFingerprint &&
                candidate.rightScopeFingerprint == input.right.scope.fingerprint() &&
                candidate.filterFingerprint == input.filterFingerprint &&
                candidate.comparisonMode == input.comparisonMode &&
                candidate.preflightFingerprint == result.identityFingerprint) {
                "Candidate preflight identity does not match its endpoint and settings evidence"
            }
            val acceptedBaseline = when (input.nativeState) {
                BisyncNativeState.ABSENT -> {
                    require(result.readiness == ProfileReadiness.INITIALIZATION_REQUIRED && input.previous == null) {
                        "Absent native state is only previewable as an explicit initialization candidate"
                    }
                    require(profile.readiness == ProfileReadiness.INITIALIZATION_REQUIRED) {
                        "Profile readiness does not match absent native state"
                    }
                    require(initializationMode != null) {
                        "An explicit initialization preference is required for an absent-state preview"
                    }
                    null
                }
                BisyncNativeState.COMPATIBLE -> {
                    require(initializationMode == null) {
                        "An initialization preference cannot be applied to a compatible baseline"
                    }
                    require(result.readiness == ProfileReadiness.READY && profile.readiness == ProfileReadiness.READY) {
                        "Compatible native state requires a ready profile and baseline"
                    }
                    require(input.previous?.preflightFingerprint == candidate.preflightFingerprint) {
                        "Compatible state is not backed by the accepted preflight identity"
                    }
                    candidate.preflightFingerprint
                }
                else -> throw IllegalArgumentException("Unresolved native state cannot produce a preview identity")
            }
            return BisyncPreviewIdentity(
                profileId = profile.profileId,
                profileRevision = profile.revision,
                profileFingerprint = profile.fingerprint,
                engineRef = input.engineRef,
                stateVersion = input.stateVersion,
                leftAccountFingerprint = requireNotNull(input.left.accountFingerprint),
                leftScopeFingerprint = requireNotNull(input.left.scope.fingerprint()),
                rightAccountFingerprint = requireNotNull(input.right.accountFingerprint),
                rightScopeFingerprint = requireNotNull(input.right.scope.fingerprint()),
                filterFingerprint = requireNotNull(input.filterFingerprint),
                comparisonMode = input.comparisonMode,
                initializationMode = initializationMode,
                maxDeletePercent = input.maxDeletePercent,
                maxDeleteCount = input.maxDeleteCount,
                nativeState = input.nativeState,
                acceptedBaselineFingerprint = acceptedBaseline
            )
        }

        private fun isCanonicalUuid(value: String): Boolean = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun canonical(values: List<String>): String = values.joinToString("") { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            "${bytes.size}:$value|"
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** Persisted preview ownership is separate from normal copy/sync runs. */
enum class BisyncPreviewOperationState(val wireValue: String, val terminal: Boolean) {
    QUEUED("QUEUED", false),
    RUNNING("RUNNING", false),
    COMPLETE("COMPLETE", true),
    INCOMPLETE("INCOMPLETE", true),
    UNAVAILABLE("UNAVAILABLE", true),
    CANCELLED("CANCELLED", true),
    STALE("STALE", true),
    INTERRUPTED("INTERRUPTED", false),
    RECOVERY_REQUIRED("RECOVERY_REQUIRED", false);

    companion object {
        fun fromWireValue(value: String?): BisyncPreviewOperationState =
            values().firstOrNull { it.wireValue == value } ?: RECOVERY_REQUIRED
    }
}

/** Explicit conflict preference used only for a first/reinitialization preview candidate. */
enum class BisyncPreviewResyncMode(val wireValue: String) {
    PATH1("path1"),
    PATH2("path2"),
    NEWER("newer"),
    OLDER("older"),
    LARGER("larger"),
    SMALLER("smaller");

    companion object {
        fun fromWireValue(value: String?): BisyncPreviewResyncMode? =
            values().firstOrNull { it.wireValue == value }
    }
}

/** Allowlisted, path-free terminal reason codes. Raw native output is never persisted here. */
enum class BisyncPreviewFailureCode(val wireValue: String) {
    PROCESS_FAILED("PROCESS_FAILED"),
    OUTPUT_TRUNCATED("OUTPUT_TRUNCATED"),
    OUTPUT_EMPTY("OUTPUT_EMPTY"),
    OUTPUT_TOO_LARGE("OUTPUT_TOO_LARGE"),
    INVALID_JSON("INVALID_JSON"),
    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION"),
    INVALID_SCHEMA("INVALID_SCHEMA"),
    PROFILE_CHANGED("PROFILE_CHANGED"),
    IDENTITY_CHANGED("IDENTITY_CHANGED"),
    CANCELLED_UNCONFIRMED("CANCELLED_UNCONFIRMED"),
    OWNER_LOST("OWNER_LOST"),
    NATIVE_STATE_UNRESOLVED("NATIVE_STATE_UNRESOLVED"),
    INITIALIZATION_POLICY_MISSING("INITIALIZATION_POLICY_MISSING"),
    ENGINE_UNSUPPORTED("ENGINE_UNSUPPORTED"),
    REQUEST_REJECTED("REQUEST_REJECTED"),
    SCRATCH_CLEANUP_FAILED("SCRATCH_CLEANUP_FAILED"),
    CANCELLED_BEFORE_START("CANCELLED_BEFORE_START");

    companion object {
        fun fromWireValue(value: String?): BisyncPreviewFailureCode? =
            values().firstOrNull { it.wireValue == value }
    }
}

data class BisyncPreviewOperation(
    val previewId: String,
    val identity: BisyncPreviewIdentity,
    val state: BisyncPreviewOperationState,
    val ownerToken: String,
    val ownerGeneration: Long,
    val requestedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val updatedAt: Long,
    val failureCode: BisyncPreviewFailureCode?,
    val summary: BisyncPreviewSummary?
) {
    /** Owner tokens are capabilities and must not leak through accidental data-class logging. */
    override fun toString(): String =
        "BisyncPreviewOperation(previewId=$previewId, profileId=${identity.profileId}, " +
            "state=${state.wireValue}, ownerGeneration=$ownerGeneration, requestedAt=$requestedAt, " +
            "completedAt=$completedAt, failureCode=${failureCode?.wireValue})"
}

enum class BisyncPreviewFreshness {
    FRESH_FOR_DISPLAY,
    NOT_COMPLETE,
    INCOMPLETE,
    RESULT_UNAVAILABLE,
    IDENTITY_CHANGED,
    CLOCK_INVALID,
    EXPIRED
}

/**
 * Freshness only controls whether a result may be shown as recent. It never authorizes a write,
 * deletion, initialization, recovery, or execution; those actions must probe and decide afresh.
 */
object BisyncPreviewFreshnessPolicy {
    const val MAX_AGE_MILLIS: Long = 15L * 60L * 1000L

    @JvmStatic
    fun evaluate(
        operation: BisyncPreviewOperation,
        currentIdentity: BisyncPreviewIdentity,
        now: Long
    ): BisyncPreviewFreshness {
        if (!operation.state.terminal) return BisyncPreviewFreshness.NOT_COMPLETE
        if (operation.state == BisyncPreviewOperationState.INCOMPLETE ||
            operation.summary?.status == BisyncPreviewStatus.INCOMPLETE) {
            return BisyncPreviewFreshness.INCOMPLETE
        }
        if (operation.state != BisyncPreviewOperationState.COMPLETE || operation.summary == null) {
            return BisyncPreviewFreshness.RESULT_UNAVAILABLE
        }
        if (operation.identity != currentIdentity ||
            operation.identity.fingerprint != currentIdentity.fingerprint) {
            return BisyncPreviewFreshness.IDENTITY_CHANGED
        }
        val completedAt = operation.completedAt ?: return BisyncPreviewFreshness.RESULT_UNAVAILABLE
        if (completedAt <= 0L || now < completedAt) return BisyncPreviewFreshness.CLOCK_INVALID
        if (now - completedAt >= MAX_AGE_MILLIS) return BisyncPreviewFreshness.EXPIRED
        return BisyncPreviewFreshness.FRESH_FOR_DISPLAY
    }
}
