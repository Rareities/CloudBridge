package ca.pkay.rcloneexplorer.Database

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BisyncPreviewFreshnessTest {
    @Test
    fun identityFingerprintIsStableAndIncludesEverySemanticInput() {
        val identity = identity()

        assertEquals(identity.fingerprint, identity.copy().fingerprint)
        assertNotEquals(identity.fingerprint, identity.copy(profileRevision = 2).fingerprint)
        assertNotEquals(identity.fingerprint, identity.copy(rightScopeFingerprint = digest('f')).fingerprint)
        assertNotEquals(identity.fingerprint, identity.copy(engineRef = "rclone:1.76.0@${"e".repeat(40)}").fingerprint)
        assertNotEquals(identity.fingerprint, identity.copy(maxDeleteCount = 26).fingerprint)
        assertNotEquals(identity.fingerprint, identity.copy(nativeState = BisyncNativeState.ABSENT,
            acceptedBaselineFingerprint = null).fingerprint)
    }

    @Test
    fun identityRejectsAmbiguousOrUnsafeSnapshots() {
        assertThrows(IllegalArgumentException::class.java) { identity(profileId = "not-a-uuid") }
        assertThrows(IllegalArgumentException::class.java) { identity(engineRef = "rclone:master") }
        assertThrows(IllegalArgumentException::class.java) { identity(leftScopeFingerprint = "local:/private/path") }
        assertThrows(IllegalArgumentException::class.java) { identity(maxDeletePercent = 0) }
        assertThrows(IllegalArgumentException::class.java) { identity(maxDeleteCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { identity(nativeState = BisyncNativeState.UNKNOWN) }
        assertThrows(IllegalArgumentException::class.java) {
            identity(nativeState = BisyncNativeState.ABSENT, acceptedBaselineFingerprint = digest('e'))
        }
    }

    @Test
    fun identityFactoryRequiresSuccessfulPreflightAndSeparatesCandidateFromAcceptedBaseline() {
        val engineRef = "rclone:1.76.0@${"a".repeat(40)}"
        val profile = ProfileRecord(
            profileId = UUID.randomUUID().toString(),
            legacyTaskId = 1L,
            revision = 1L,
            title = "fixture",
            mode = ProfileMode.BISYNC,
            endpointIdentity = "endpoint-hash",
            settings = "settings-hash",
            fingerprint = digest('1'),
            engineRef = engineRef,
            readiness = ProfileReadiness.INITIALIZATION_REQUIRED,
            reason = null,
            createdAt = 1L,
            updatedAt = 1L
        )
        val leftAccount = digest('2')
        val rightAccount = digest('3')
        val input = BisyncPreflightInput(
            profileRevision = profile.revision,
            profileFingerprint = profile.fingerprint,
            engineRef = engineRef,
            stateVersion = BisyncPreflightPolicy.CURRENT_STATE_VERSION,
            left = BisyncEndpointEvidence(leftAccount, BisyncEndpointScope.from(leftAccount, "left"), true),
            right = BisyncEndpointEvidence(rightAccount, BisyncEndpointScope.from(rightAccount, "right"), true),
            leftListing = BisyncListingEvidence(true, true, 0),
            rightListing = BisyncListingEvidence(true, true, 0),
            filterFingerprint = digest('4'),
            filterResolved = true,
            comparisonMode = BisyncComparisonMode.SIZE_ONLY,
            nativeState = BisyncNativeState.ABSENT
        )
        val initialization = BisyncPreflightPolicy.evaluate(input)
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, initialization.readiness)
        val absentIdentity = BisyncPreviewIdentity.fromPreflight(profile, input, initialization)
        assertEquals(BisyncNativeState.ABSENT, absentIdentity.nativeState)
        assertEquals(null, absentIdentity.acceptedBaselineFingerprint)

        val compatibleInput = input.copy(
            nativeState = BisyncNativeState.COMPATIBLE,
            previous = initialization.candidateBaseline
        )
        val readyResult = BisyncPreflightPolicy.evaluate(compatibleInput)
        val readyProfile = profile.copy(readiness = ProfileReadiness.READY)
        val compatibleIdentity = BisyncPreviewIdentity.fromPreflight(readyProfile, compatibleInput, readyResult)
        assertEquals(ProfileReadiness.READY, readyResult.readiness)
        assertEquals(readyResult.candidateBaseline!!.preflightFingerprint,
            compatibleIdentity.acceptedBaselineFingerprint)
    }

    @Test
    fun completeResultIsFreshForDisplayOnlyInsideTheStrictFifteenMinuteWindow() {
        val id = identity()
        val completedAt = 1_800_000_000_000L
        val operation = operation(id, BisyncPreviewOperationState.COMPLETE, completedAt,
            BisyncPreviewSummary(BisyncPreviewStatus.COMPLETE, 2, 26, 0, 0, 0))

        assertEquals(BisyncPreviewFreshness.FRESH_FOR_DISPLAY,
            BisyncPreviewFreshnessPolicy.evaluate(operation, id, completedAt))
        assertEquals(BisyncPreviewFreshness.FRESH_FOR_DISPLAY,
            BisyncPreviewFreshnessPolicy.evaluate(operation, id,
                completedAt + BisyncPreviewFreshnessPolicy.MAX_AGE_MILLIS - 1))
        assertEquals(BisyncPreviewFreshness.EXPIRED,
            BisyncPreviewFreshnessPolicy.evaluate(operation, id,
                completedAt + BisyncPreviewFreshnessPolicy.MAX_AGE_MILLIS))
        assertTrue("A fresh preview is review data, not a write authorization",
            BisyncPreviewFreshnessPolicy.MAX_AGE_MILLIS == 15L * 60L * 1000L)
    }

    @Test
    fun identityChangeAndClockRollbackInvalidateThePreview() {
        val id = identity()
        val completedAt = 1_800_000_000_000L
        val operation = operation(id, BisyncPreviewOperationState.COMPLETE, completedAt,
            BisyncPreviewSummary(BisyncPreviewStatus.COMPLETE, 0, 0, 0, 0, 0))

        assertEquals(BisyncPreviewFreshness.IDENTITY_CHANGED,
            BisyncPreviewFreshnessPolicy.evaluate(operation, id.copy(profileRevision = 2), completedAt + 1))
        assertEquals(BisyncPreviewFreshness.CLOCK_INVALID,
            BisyncPreviewFreshnessPolicy.evaluate(operation, id, completedAt - 1))
    }

    @Test
    fun incompleteUnconfirmedAndInterruptedResultsNeverBecomeFresh() {
        val id = identity()
        val time = 1_800_000_000_000L
        val incomplete = operation(id, BisyncPreviewOperationState.INCOMPLETE, time,
            BisyncPreviewSummary(BisyncPreviewStatus.INCOMPLETE, 0, 0, 0, 0, 1))
        val failed = operation(id, BisyncPreviewOperationState.UNAVAILABLE, time, null)
        val interrupted = operation(id, BisyncPreviewOperationState.INTERRUPTED, null, null)

        assertEquals(BisyncPreviewFreshness.INCOMPLETE,
            BisyncPreviewFreshnessPolicy.evaluate(incomplete, id, time + 1))
        assertEquals(BisyncPreviewFreshness.RESULT_UNAVAILABLE,
            BisyncPreviewFreshnessPolicy.evaluate(failed, id, time + 1))
        assertEquals(BisyncPreviewFreshness.NOT_COMPLETE,
            BisyncPreviewFreshnessPolicy.evaluate(interrupted, id, time + 1))
    }

    private fun identity(
        profileId: String = UUID.randomUUID().toString(),
        engineRef: String = "rclone:1.76.0@${"a".repeat(40)}",
        leftScopeFingerprint: String = digest('b'),
        rightScopeFingerprint: String = digest('c'),
        maxDeletePercent: Int = 10,
        maxDeleteCount: Int = 25,
        nativeState: BisyncNativeState = BisyncNativeState.COMPATIBLE,
        acceptedBaselineFingerprint: String? = digest('d'),
        profileRevision: Long = 1
    ) = BisyncPreviewIdentity(
        profileId = profileId,
        profileRevision = profileRevision,
        profileFingerprint = digest('1'),
        engineRef = engineRef,
        stateVersion = 1,
        leftAccountFingerprint = digest('2'),
        leftScopeFingerprint = leftScopeFingerprint,
        rightAccountFingerprint = digest('3'),
        rightScopeFingerprint = rightScopeFingerprint,
        filterFingerprint = digest('4'),
        comparisonMode = BisyncComparisonMode.SIZE_AND_MODTIME,
        maxDeletePercent = maxDeletePercent,
        maxDeleteCount = maxDeleteCount,
        nativeState = nativeState,
        acceptedBaselineFingerprint = acceptedBaselineFingerprint
    )

    private fun operation(
        identity: BisyncPreviewIdentity,
        state: BisyncPreviewOperationState,
        completedAt: Long?,
        summary: BisyncPreviewSummary?
    ) = BisyncPreviewOperation(
        previewId = UUID.randomUUID().toString(),
        identity = identity,
        state = state,
        ownerToken = UUID.randomUUID().toString(),
        ownerGeneration = 1,
        requestedAt = 1_800_000_000_000L,
        startedAt = completedAt,
        completedAt = completedAt,
        updatedAt = completedAt ?: 1_800_000_000_000L,
        failureCode = null,
        summary = summary
    )

    private fun digest(char: Char) = char.toString().repeat(64)
}
