package ca.pkay.rcloneexplorer.workmanager

/**
 * Bounded policy for the optional sync-before-open handoff to Obsidian.
 *
 * A caller reports SUCCESS only after the requested profile's native run has a confirmed,
 * successful terminal result. This policy creates a user-action-ready intent description; it
 * never starts an activity. Callers should retain [Decision.lastOpenActionOfferedAtElapsedMillis] when
 * beginning later requests so the reopen debounce survives worker/process boundaries.
 */
internal object ObsidianSyncBeforeOpenPolicy {
    const val OBSIDIAN_PACKAGE = "md.obsidian"
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val REOPEN_DEBOUNCE_MILLIS = 2_000L

    enum class State {
        INVALID_REQUEST,
        WAITING_FOR_SYNC,
        SYNC_SUCCEEDED,
        SYNC_FAILED,
        SYNC_CANCELLED,
        SYNC_UNKNOWN,
        PROFILE_MISMATCH,
        DEFERRED,
        ALREADY_OPEN,
        REOPEN_DEBOUNCED,
        USER_ACTION_AVAILABLE,
        MANUAL_ACTION_REQUIRED
    }

    enum class SyncOutcome { SUCCESS, FAILURE, CANCELLED, UNKNOWN }

    enum class ForegroundObservation { NOT_OBSIDIAN, OBSIDIAN, UNKNOWN }

    enum class PackageAvailability { INSTALLED, ABSENT, UNKNOWN }

    data class LaunchIntentSpec(
        val packageName: String = OBSIDIAN_PACKAGE,
        val action: String = ACTION_MAIN,
        val category: String = CATEGORY_LAUNCHER
    )

    data class Decision(
        val state: State,
        val requestedProfileId: String? = null,
        val qualifyingRunId: String? = null,
        val launchIntentSpec: LaunchIntentSpec? = null,
        val manualAction: String? = null,
        val lastOpenActionOfferedAtElapsedMillis: Long? = null
    )

    /** Starts one request for an exact, nonblank profile ID. */
    @JvmStatic
    fun begin(
        requestedProfileId: String?,
        lastOpenActionOfferedAtElapsedMillis: Long? = null
    ): Decision {
        val profileId = requestedProfileId?.trim().orEmpty()
        if (profileId.isEmpty() ||
            (lastOpenActionOfferedAtElapsedMillis != null && lastOpenActionOfferedAtElapsedMillis < 0L)) {
            return Decision(State.INVALID_REQUEST)
        }
        return Decision(
            state = State.WAITING_FOR_SYNC,
            requestedProfileId = profileId,
            lastOpenActionOfferedAtElapsedMillis = lastOpenActionOfferedAtElapsedMillis
        )
    }

    /** Accepts only the first terminal outcome and only a successful run for the requested profile. */
    @JvmStatic
    fun onSyncCompleted(
        decision: Decision,
        runProfileId: String?,
        runId: String?,
        outcome: SyncOutcome
    ): Decision {
        if (decision.state != State.WAITING_FOR_SYNC) return decision

        return when (outcome) {
            SyncOutcome.FAILURE -> decision.copy(state = State.SYNC_FAILED)
            SyncOutcome.CANCELLED -> decision.copy(state = State.SYNC_CANCELLED)
            SyncOutcome.UNKNOWN -> decision.copy(state = State.SYNC_UNKNOWN)
            SyncOutcome.SUCCESS -> {
                val completedProfileId = runProfileId?.trim().orEmpty()
                val completedRunId = runId?.trim().orEmpty()
                if (completedProfileId.isEmpty() || completedRunId.isEmpty() ||
                    completedProfileId != decision.requestedProfileId) {
                    decision.copy(state = State.PROFILE_MISMATCH)
                } else {
                    decision.copy(
                        state = State.SYNC_SUCCEEDED,
                        qualifyingRunId = completedRunId
                    )
                }
            }
        }
    }

    /**
     * Evaluates one explicit foreground observation. UNKNOWN is deferred and may be evaluated
     * again after a later user-driven observation; it is never interpreted as NOT_OBSIDIAN.
     */
    @JvmStatic
    fun onForegroundObserved(
        decision: Decision,
        foreground: ForegroundObservation,
        packageAvailability: PackageAvailability,
        nowElapsedMillis: Long
    ): Decision {
        if (decision.state !in setOf(State.SYNC_SUCCEEDED, State.DEFERRED, State.REOPEN_DEBOUNCED)) {
            return decision
        }
        if (decision.qualifyingRunId.isNullOrBlank()) {
            return decision.copy(state = State.SYNC_UNKNOWN, launchIntentSpec = null)
        }
        if (nowElapsedMillis < 0L || foreground == ForegroundObservation.UNKNOWN) {
            return decision.copy(state = State.DEFERRED, launchIntentSpec = null)
        }
        if (foreground == ForegroundObservation.OBSIDIAN) {
            return decision.copy(state = State.ALREADY_OPEN, launchIntentSpec = null)
        }
        if (packageAvailability != PackageAvailability.INSTALLED) {
            val instruction = if (packageAvailability == PackageAvailability.ABSENT) {
                "Install Obsidian, then open it manually after the sync completes."
            } else {
                "Open Obsidian manually after the sync completes; CloudBridge could not confirm its launcher."
            }
            return decision.copy(
                state = State.MANUAL_ACTION_REQUIRED,
                launchIntentSpec = null,
                manualAction = instruction
            )
        }

        val previousActionAt = decision.lastOpenActionOfferedAtElapsedMillis
        if (previousActionAt != null &&
            (nowElapsedMillis < previousActionAt ||
                nowElapsedMillis - previousActionAt < REOPEN_DEBOUNCE_MILLIS)) {
            return decision.copy(state = State.REOPEN_DEBOUNCED, launchIntentSpec = null)
        }

        return decision.copy(
            state = State.USER_ACTION_AVAILABLE,
            launchIntentSpec = LaunchIntentSpec(),
            manualAction = null,
            lastOpenActionOfferedAtElapsedMillis = nowElapsedMillis
        )
    }

    /** Converts a policy-approved but unresolvable package into the manual fallback state. */
    @JvmStatic
    fun onLaunchIntentUnavailable(decision: Decision): Decision {
        if (decision.state != State.USER_ACTION_AVAILABLE) return decision
        return decision.copy(
            state = State.MANUAL_ACTION_REQUIRED,
            launchIntentSpec = null,
            manualAction = "Open Obsidian manually after the sync completes; its launcher is unavailable."
        )
    }
}
