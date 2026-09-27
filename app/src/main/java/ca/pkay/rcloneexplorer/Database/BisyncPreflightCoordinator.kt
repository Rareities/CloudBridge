package ca.pkay.rcloneexplorer.Database

import android.content.Context
import android.os.CancellationSignal
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.FilterEntry
import ca.pkay.rcloneexplorer.Rclone

fun interface BisyncNativeStateProbe {
    fun inspect(
        profileId: String,
        profileFingerprint: String,
        localPath: String?,
        remote: RemoteItem,
        remotePath: String?,
        comparisonMode: BisyncComparisonMode,
        cancellationSignal: CancellationSignal?
    ): BisyncNativeStateEvidence
}

data class BisyncPreflightRunResult(
    val policyResult: BisyncPreflightResult,
    val checkedAt: Long,
    /** Transient revalidation evidence used by a worker; it is never persisted. */
    val input: BisyncPreflightInput? = null,
    /** Raw endpoints stay in process memory and are rebuilt from the same Task that was probed. */
    val executionSnapshot: BisyncPreflightExecutionSnapshot? = null
)

data class BisyncPreflightExecutionSnapshot(
    val localPath: String,
    val remoteId: String,
    val remoteType: Int,
    val remotePath: String,
    val filters: List<FilterEntry>,
    val deleteExcluded: Boolean,
    val checksumRequested: Boolean
)

/** Coordinates only read-only probes and persists their sanitized outcome against a profile revision. */
class BisyncPreflightCoordinator(
    context: Context,
    private val rclone: Rclone,
    private val stateProbe: BisyncNativeStateProbe = BisyncNativeStateProbe {
            profileId, fingerprint, localPath, remote, remotePath, comparisonMode, cancellationSignal ->
        rclone.inspectBisyncNativeState(
            profileId, fingerprint, localPath, remote, remotePath,
            comparisonMode.nativeCompareOptions, cancellationSignal
        )
    }
) {
    private val appContext = context.applicationContext
    private val profiles = ProfileRepository(appContext)
    private val preflights = BisyncPreflightRepository(appContext)
    private val identities = BisyncIdentityResolver(appContext, rclone)

    /** Must run under a persisted background owner; it never initializes or changes either root. */
    fun run(
        profileId: String,
        expectedRevision: Long,
        expectedProfileFingerprint: String,
        comparisonMode: BisyncComparisonMode,
        maxDeletePercent: Int = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
        maxDeleteCount: Int = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT,
        legacyMigrationConfirmed: Boolean = false,
        cancellationSignal: CancellationSignal? = null
    ): BisyncPreflightRunResult {
        val selected = profiles.get(profileId)
            ?: throw StaleBisyncPreflightException("Profile no longer exists")
        val taskId = selected.legacyTaskId
            ?: throw IllegalStateException("This profile has no validated endpoint adapter")
        val handler = DatabaseHandler(appContext)
        val loaded = try {
            val task = handler.getTask(taskId)
                ?: throw StaleBisyncPreflightException("Profile source no longer exists")
            val current = profiles.ensureLegacyTask(task)
            if (current.mode != ProfileMode.BISYNC ||
                current.revision != expectedRevision || current.fingerprint != expectedProfileFingerprint) {
                throw StaleBisyncPreflightException("Profile changed before Bisync preflight")
            }
            if (task.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL &&
                task.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL) {
                throw StaleBisyncPreflightException("Legacy Bisync direction is not supported")
            }

            if (!legacyMigrationConfirmed) {
                val now = System.currentTimeMillis()
                val blockedInput = BisyncPreflightInput(
                    profileRevision = current.revision,
                    profileFingerprint = current.fingerprint,
                    engineRef = EngineIdentity.current,
                    stateVersion = BisyncPreflightPolicy.CURRENT_STATE_VERSION,
                    left = BisyncEndpointEvidence(null, BisyncEndpointScope.unknown(), null),
                    right = BisyncEndpointEvidence(null, BisyncEndpointScope.unknown(), null),
                    leftListing = BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LEGACY_MIGRATION_CONFIRMATION_REQUIRED),
                    rightListing = BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LEGACY_MIGRATION_CONFIRMATION_REQUIRED),
                    filterFingerprint = null,
                    filterResolved = false,
                    comparisonMode = comparisonMode,
                    nativeState = BisyncNativeState.UNKNOWN,
                    maxDeletePercent = maxDeletePercent,
                    maxDeleteCount = maxDeleteCount
                )
                val blocked = BisyncPreflightResult(
                    ProfileReadiness.BLOCKED,
                    BisyncPreflightReason.LEGACY_MIGRATION_CONFIRMATION_REQUIRED,
                    null,
                    null,
                    null
                )
                preflights.recordAttempt(
                    profileId, current.revision, current.fingerprint, blockedInput, blocked, now
                )
                return BisyncPreflightRunResult(blocked, now, blockedInput, null)
            }

            Triple(task, current, task.filterId?.let(handler::getFilter))
        } finally {
            handler.close()
        }
        val task = loaded.first
        val current = loaded.second
        val selectedFilter = loaded.third
        val selectedFilterMissing = task.filterId != null && selectedFilter == null
        val filterRaw = selectedFilter?.getFiltersRaw()
        val parsedFilters = BisyncFilterParser.parse(filterRaw)
        val filterFingerprint = when {
            task.filterId == null -> LegacyProfileMapper.fingerprintNoFilter()
            filterRaw != null && parsedFilters.valid -> LegacyProfileMapper.fingerprintFilter(filterRaw)
            else -> null
        }
        val filterResolved = !selectedFilterMissing && parsedFilters.valid && filterFingerprint != null
        val previous = preflights.get(profileId)?.acceptedBaseline
        val configBefore = rclone.getBisyncConfigSnapshotFingerprint()
        val localPath: String? = task.localPath
        val remotePath: String? = task.remotePath
        val remote = RemoteItem(task.remoteId, task.remoteType, "")
        val stateEvidence = try {
            stateProbe.inspect(
                profileId, current.fingerprint, localPath, remote, remotePath,
                comparisonMode, cancellationSignal
            )
        } catch (_: Exception) {
            // Probe failures are unknown native state, never evidence of an empty baseline.
            BisyncNativeStateEvidence(BisyncNativeState.UNKNOWN, "PROBE_FAILED", false)
        }
        val state = stateEvidence.state
        val leftIdentity = identities.local(localPath)
        var rightIdentity = identities.remote(remote, remotePath, cancellationSignal, inspectCapabilities = false)
        val stateFailure = when {
            state == BisyncNativeState.UNKNOWN -> BisyncPreflightReason.NATIVE_STATE_UNKNOWN
            state == BisyncNativeState.INTERRUPTED -> BisyncPreflightReason.NATIVE_STATE_INTERRUPTED
            state == BisyncNativeState.INCOMPATIBLE -> BisyncPreflightReason.NATIVE_STATE_INCOMPATIBLE
            state == BisyncNativeState.COMPATIBLE && previous == null -> BisyncPreflightReason.NATIVE_STATE_UNVERIFIED
            state == BisyncNativeState.ABSENT && previous != null -> BisyncPreflightReason.NATIVE_STATE_UNKNOWN
            else -> null
        }
        val initialFailure = when {
            cancellationSignal?.isCanceled == true -> BisyncPreflightReason.PROBE_CANCELLED
            configBefore == null -> BisyncPreflightReason.CONFIG_SNAPSHOT_UNAVAILABLE
            stateFailure != null -> stateFailure
            !filterResolved -> BisyncPreflightReason.FILTER_MISSING
            leftIdentity.accountFingerprint == null || rightIdentity.accountFingerprint == null ||
                !leftIdentity.scope.isResolved || !rightIdentity.scope.isResolved ->
                BisyncPreflightReason.ACCOUNT_IDENTITY_UNKNOWN
            leftIdentity.scope.overlaps(rightIdentity.scope) -> BisyncPreflightReason.ROOT_OVERLAP
            maxDeletePercent !in 1..100 || maxDeleteCount <= 0 -> BisyncPreflightReason.DELETE_LIMIT_INVALID
            else -> BisyncPreflightReason.LISTING_INCOMPLETE
        }
        var leftListing = BisyncListingEvidence(false, false, 0, initialFailure)
        var rightListing = leftListing
        val structuralBlock = !filterResolved ||
            leftIdentity.accountFingerprint == null || rightIdentity.accountFingerprint == null ||
            !leftIdentity.scope.isResolved || !rightIdentity.scope.isResolved ||
            leftIdentity.scope.overlaps(rightIdentity.scope) ||
            maxDeletePercent !in 1..100 || maxDeleteCount <= 0 ||
            cancellationSignal?.isCanceled == true || configBefore == null || stateFailure != null

        if (!structuralBlock) {
            if (comparisonMode == BisyncComparisonMode.SIZE_AND_MODTIME) {
                rightIdentity = rightIdentity.copy(
                    supportsModTimeComparison = identities.remoteSupportsModTime(remote, remotePath, cancellationSignal)
                )
            }
            if (cancellationSignal?.isCanceled == true) {
                leftListing = BisyncListingEvidence(false, false, 0, BisyncPreflightReason.PROBE_CANCELLED)
                rightListing = leftListing
            } else if (comparisonMode == BisyncComparisonMode.SIZE_AND_MODTIME &&
                rightIdentity.supportsModTimeComparison != true) {
                leftListing = BisyncListingEvidence(false, false, 0, BisyncPreflightReason.COMPARISON_UNSUPPORTED)
                rightListing = leftListing
            } else {
                val rules = parsedFilters.entries.orEmpty()
                leftListing = rclone.scanBisyncPath(requireNotNull(localPath), rules, cancellationSignal).evidence
                if (leftListing.complete && cancellationSignal?.isCanceled != true) {
                    rightListing = rclone.scanBisyncRoot(remote, requireNotNull(remotePath), rules, cancellationSignal).evidence
                } else if (cancellationSignal?.isCanceled == true) {
                    rightListing = BisyncListingEvidence(
                        false, false, 0, BisyncPreflightReason.PROBE_CANCELLED
                    )
                }
            }
        }

        val configAfter = rclone.getBisyncConfigSnapshotFingerprint()
        val localAfter = identities.local(localPath)
        if (configBefore != null && configBefore != configAfter) {
            leftListing = BisyncListingEvidence(
                false, false, 0, BisyncPreflightReason.CONFIG_CHANGED_DURING_PREFLIGHT
            )
            rightListing = leftListing
        } else if (leftIdentity.scope.fingerprint() != localAfter.scope.fingerprint()) {
            leftListing = BisyncListingEvidence(
                false, false, 0, BisyncPreflightReason.ENDPOINT_IDENTITY_CHANGED
            )
            rightListing = leftListing
        }

        val input = BisyncPreflightInput(
            profileRevision = current.revision,
            profileFingerprint = current.fingerprint,
            engineRef = EngineIdentity.current,
            stateVersion = BisyncPreflightPolicy.CURRENT_STATE_VERSION,
            left = leftIdentity,
            right = rightIdentity,
            leftListing = leftListing,
            rightListing = rightListing,
            filterFingerprint = filterFingerprint,
            filterResolved = filterResolved,
            comparisonMode = comparisonMode,
            nativeState = state,
            nativeStateReason = stateEvidence.reason,
            nativeRecoveryListingsValid = stateEvidence.recoveryListingsValid,
            previous = previous,
            maxDeletePercent = maxDeletePercent,
            maxDeleteCount = maxDeleteCount
        )
        val result = BisyncPreflightPolicy.evaluate(input)
        val checkedAt = System.currentTimeMillis()
        preflights.recordAttempt(
            profileId,
            current.revision,
            current.fingerprint,
            input,
            result,
            checkedAt
        )
        val executionSnapshot = BisyncPreflightExecutionSnapshot(
            localPath = task.localPath,
            remoteId = task.remoteId,
            remoteType = task.remoteType,
            remotePath = task.remotePath,
            filters = parsedFilters.entries.orEmpty().map { FilterEntry(it.filterType, it.filter) },
            deleteExcluded = task.deleteExcluded,
            checksumRequested = task.md5sum
        )
        return BisyncPreflightRunResult(result, checkedAt, input, executionSnapshot)
    }
}
