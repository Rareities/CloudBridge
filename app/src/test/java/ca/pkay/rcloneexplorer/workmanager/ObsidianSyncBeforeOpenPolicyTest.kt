package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsidianSyncBeforeOpenPolicyTest {
    private val profileId = "profile-obsidian"
    private val runId = "run-42"

    @Test
    fun successfulRunForRequestedProfileOffersPackageScopedUserAction() {
        val completed = completedRun(ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS)
        val decision = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            completed,
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_000L
        )

        assertEquals(ObsidianSyncBeforeOpenPolicy.State.USER_ACTION_AVAILABLE, decision.state)
        assertEquals(runId, decision.qualifyingRunId)
        assertEquals(
            ObsidianSyncBeforeOpenPolicy.OBSIDIAN_PACKAGE,
            decision.launchIntentSpec?.packageName
        )
        assertEquals(
            ObsidianSyncBeforeOpenPolicy.ACTION_MAIN,
            decision.launchIntentSpec?.action
        )
        assertEquals(
            ObsidianSyncBeforeOpenPolicy.CATEGORY_LAUNCHER,
            decision.launchIntentSpec?.category
        )
    }

    @Test
    fun failedCancelledAndUnknownRunsNeverOfferLaunchAction() {
        listOf(
            ObsidianSyncBeforeOpenPolicy.SyncOutcome.FAILURE to ObsidianSyncBeforeOpenPolicy.State.SYNC_FAILED,
            ObsidianSyncBeforeOpenPolicy.SyncOutcome.CANCELLED to ObsidianSyncBeforeOpenPolicy.State.SYNC_CANCELLED,
            ObsidianSyncBeforeOpenPolicy.SyncOutcome.UNKNOWN to ObsidianSyncBeforeOpenPolicy.State.SYNC_UNKNOWN
        ).forEach { (outcome, expectedState) ->
            val decision = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
                completedRun(outcome),
                ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
                ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
                nowElapsedMillis = 5_000L
            )

            assertEquals(expectedState, decision.state)
            assertNull(decision.launchIntentSpec)
        }
    }

    @Test
    fun successFromAnotherProfileIsNotQualifying() {
        val request = ObsidianSyncBeforeOpenPolicy.begin(profileId)
        val decision = ObsidianSyncBeforeOpenPolicy.onSyncCompleted(
            request,
            runProfileId = "different-profile",
            runId = runId,
            outcome = ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS
        )

        assertEquals(ObsidianSyncBeforeOpenPolicy.State.PROFILE_MISMATCH, decision.state)
        assertNull(decision.qualifyingRunId)
        assertNull(decision.launchIntentSpec)
    }

    @Test
    fun unknownForegroundIsDeferredAndCanBeRecheckedExplicitly() {
        val deferred = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            completedRun(ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS),
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.UNKNOWN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_000L
        )
        assertEquals(ObsidianSyncBeforeOpenPolicy.State.DEFERRED, deferred.state)
        assertNull(deferred.launchIntentSpec)

        val rechecked = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            deferred,
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_500L
        )
        assertEquals(ObsidianSyncBeforeOpenPolicy.State.USER_ACTION_AVAILABLE, rechecked.state)
        assertNotNull(rechecked.launchIntentSpec)
    }

    @Test
    fun alreadyForegroundObsidianIsNotReopened() {
        val decision = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            completedRun(ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS),
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_000L
        )

        assertEquals(ObsidianSyncBeforeOpenPolicy.State.ALREADY_OPEN, decision.state)
        assertNull(decision.launchIntentSpec)
    }

    @Test
    fun repeatedOpenRequestIsDebouncedUntilIntervalExpires() {
        val firstReady = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            completedRun(ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS),
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_000L
        )
        assertEquals(ObsidianSyncBeforeOpenPolicy.State.USER_ACTION_AVAILABLE, firstReady.state)

        val secondRequest = ObsidianSyncBeforeOpenPolicy.begin(
            profileId,
            lastOpenActionOfferedAtElapsedMillis = firstReady.lastOpenActionOfferedAtElapsedMillis
        )
        val successfulRun = ObsidianSyncBeforeOpenPolicy.onSyncCompleted(
            secondRequest,
            runProfileId = profileId,
            runId = "run-43",
            outcome = ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS
        )

        val debounced = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            successfulRun,
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_500L
        )
        assertEquals(ObsidianSyncBeforeOpenPolicy.State.REOPEN_DEBOUNCED, debounced.state)
        assertNull(debounced.launchIntentSpec)

        val afterDebounce = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            debounced,
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 7_000L
        )
        assertEquals(ObsidianSyncBeforeOpenPolicy.State.USER_ACTION_AVAILABLE, afterDebounce.state)
        assertEquals(7_000L, afterDebounce.lastOpenActionOfferedAtElapsedMillis)
        assertNotNull(afterDebounce.launchIntentSpec)
    }

    @Test
    fun absentObsidianRequiresActionableManualFallback() {
        val decision = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            completedRun(ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS),
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.ABSENT,
            nowElapsedMillis = 5_000L
        )

        assertEquals(ObsidianSyncBeforeOpenPolicy.State.MANUAL_ACTION_REQUIRED, decision.state)
        assertNull(decision.launchIntentSpec)
        assertTrue(decision.manualAction.orEmpty().contains("Install Obsidian"))
    }

    @Test
    fun launchIntentUnavailableFallsBackToManualState() {
        val ready = ObsidianSyncBeforeOpenPolicy.onForegroundObserved(
            completedRun(ObsidianSyncBeforeOpenPolicy.SyncOutcome.SUCCESS),
            ObsidianSyncBeforeOpenPolicy.ForegroundObservation.NOT_OBSIDIAN,
            ObsidianSyncBeforeOpenPolicy.PackageAvailability.INSTALLED,
            nowElapsedMillis = 5_000L
        )

        val fallback = ObsidianSyncBeforeOpenPolicy.onLaunchIntentUnavailable(ready)
        assertEquals(ObsidianSyncBeforeOpenPolicy.State.MANUAL_ACTION_REQUIRED, fallback.state)
        assertNull(fallback.launchIntentSpec)
        assertTrue(fallback.manualAction.orEmpty().contains("Open Obsidian manually"))
    }

    private fun completedRun(outcome: ObsidianSyncBeforeOpenPolicy.SyncOutcome) =
        ObsidianSyncBeforeOpenPolicy.onSyncCompleted(
            ObsidianSyncBeforeOpenPolicy.begin(profileId),
            runProfileId = profileId,
            runId = runId,
            outcome = outcome
        )
}
