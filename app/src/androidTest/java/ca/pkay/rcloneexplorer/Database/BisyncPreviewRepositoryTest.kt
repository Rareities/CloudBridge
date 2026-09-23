package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class BisyncPreviewRepositoryTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseInfo.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DatabaseInfo.DATABASE_NAME)
    }

    @Test
    fun durableOwnerAllowsOnlyOneClaimAndStoresConfirmedPathFreeResult() {
        val identity = createReadyIdentity()
        val repository = BisyncPreviewRepository(context)
        val queued = repository.queue(identity, 10_000L)
        assertEquals(BisyncPreviewOperationState.QUEUED, queued.state)
        assertThrows(BisyncPreviewRejectedException::class.java) {
            repository.queue(identity, 10_001L)
        }

        val claimed = repository.claim(queued.previewId, queued.ownerToken, identity, 10_100L)
        assertEquals(BisyncPreviewOperationState.RUNNING, claimed.state)
        assertEquals(1L, claimed.ownerGeneration)
        val result = BisyncPreviewParseResult.Available(
            BisyncPreviewSummary(BisyncPreviewStatus.COMPLETE, 2L, 128L, 0L, 0L, 0L, false)
        )

        assertFalse(repository.finish(
            claimed.previewId, claimed.ownerToken, claimed.ownerGeneration - 1L,
            identity, result, true, 10_200L
        ))
        assertTrue(repository.finish(
            claimed.previewId, claimed.ownerToken, claimed.ownerGeneration,
            identity, result, true, 10_200L
        ))

        val stored = repository.get(claimed.previewId)
        assertNotNull(stored)
        assertEquals(BisyncPreviewOperationState.COMPLETE, stored!!.state)
        assertEquals(10_200L, stored.updatedAt)
        assertEquals(2L, stored.summary!!.plannedTransfers)
        assertEquals(BisyncPreviewFreshness.FRESH_FOR_DISPLAY,
            BisyncPreviewFreshnessPolicy.evaluate(stored, identity, 10_201L))
    }

    @Test
    fun activeAndHistoryQueriesRetainTerminalRowsAndKeepInterruptedOwnersVisible() {
        val identity = createReadyIdentity()
        val repository = BisyncPreviewRepository(context)
        val queued = repository.queue(identity, 15_000L)

        assertEquals(queued.previewId, repository.active(identity.profileId)!!.previewId)
        assertEquals(listOf(queued.previewId), repository.history(identity.profileId).map { it.previewId })
        assertTrue(repository.finishQueued(
            queued.previewId,
            queued.ownerToken,
            BisyncPreviewOperationState.CANCELLED,
            BisyncPreviewFailureCode.CANCELLED_BEFORE_START,
            15_100L
        ))
        assertEquals(null, repository.active(identity.profileId))
        assertEquals(BisyncPreviewOperationState.CANCELLED,
            repository.history(identity.profileId).single().state)
        assertThrows(IllegalArgumentException::class.java) { repository.history(identity.profileId, 0) }
    }

    @Test
    fun changedProfileMakesQueuedPreviewStaleAndNeverLaunchable() {
        val identity = createReadyIdentity()
        val repository = BisyncPreviewRepository(context)
        val queued = repository.queue(identity, 20_000L)
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            db.update(
                DatabaseInfo.PROFILE_TABLE_NAME,
                ContentValues().apply { put(DatabaseInfo.PROFILE_COLUMN_REVISION, identity.profileRevision + 1L) },
                "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
                arrayOf(identity.profileId)
            )
        } finally {
            db.close()
            handler.close()
        }

        assertThrows(BisyncPreviewRejectedException::class.java) {
            repository.claim(queued.previewId, queued.ownerToken, identity, 20_100L)
        }
        val stored = repository.get(queued.previewId)
        assertNotNull(stored)
        assertEquals(BisyncPreviewOperationState.STALE, stored!!.state)
        assertEquals(BisyncPreviewFailureCode.IDENTITY_CHANGED, stored.failureCode)
    }

    @Test
    fun interruptedOrUnconfirmedOwnerRemainsBlockingUntilExplicitReconciliation() {
        val identity = createReadyIdentity()
        val repository = BisyncPreviewRepository(context)
        val queued = repository.queue(identity, 30_000L)
        val claimed = repository.claim(queued.previewId, queued.ownerToken, identity, 30_100L)

        assertEquals(1, repository.reconcileInterruptedRuns(30_200L))
        val interrupted = repository.get(queued.previewId)!!
        assertEquals(BisyncPreviewOperationState.INTERRUPTED, interrupted.state)
        assertEquals(2L, interrupted.ownerGeneration)
        assertEquals(30_200L, interrupted.updatedAt)
        assertThrows(BisyncPreviewRejectedException::class.java) {
            repository.queue(identity, 30_300L)
        }

        // Even a late callback from the former owner cannot clear the conservative hold.
        assertFalse(repository.finish(
            claimed.previewId,
            claimed.ownerToken,
            claimed.ownerGeneration,
            identity,
            BisyncPreviewParseResult.Unavailable(BisyncPreviewUnavailableReason.PROCESS_FAILED),
            true,
            30_400L
        ))
    }

    @Test
    fun queuedDispatchFailureIsTerminalAndReleasesOnlyItsExactOwner() {
        val identity = createReadyIdentity()
        val repository = BisyncPreviewRepository(context)
        val queued = repository.queue(identity)

        assertFalse(repository.finishQueued(
            queued.previewId,
            "not-the-owner",
            BisyncPreviewOperationState.UNAVAILABLE,
            BisyncPreviewFailureCode.PROCESS_FAILED
        ))
        assertTrue(repository.finishQueued(
            queued.previewId,
            queued.ownerToken,
            BisyncPreviewOperationState.UNAVAILABLE,
            BisyncPreviewFailureCode.PROCESS_FAILED
        ))

        val failed = repository.get(queued.previewId)!!
        assertEquals(BisyncPreviewOperationState.UNAVAILABLE, failed.state)
        assertEquals(BisyncPreviewFailureCode.PROCESS_FAILED, failed.failureCode)
        assertNotNull(failed.completedAt)
        assertEquals(null, failed.summary)
        assertEquals(BisyncPreviewOperationState.QUEUED, repository.queue(identity).state)
    }

    @Test
    fun queuedCancellationCanCloseOnlyAQueuedOwner() {
        val identity = createReadyIdentity()
        val repository = BisyncPreviewRepository(context)
        val queued = repository.queue(identity)
        val claimed = repository.claim(queued.previewId, queued.ownerToken, identity)

        assertFalse(repository.finishQueued(
            queued.previewId,
            queued.ownerToken,
            BisyncPreviewOperationState.CANCELLED,
            BisyncPreviewFailureCode.CANCELLED_BEFORE_START
        ))
        assertEquals(BisyncPreviewOperationState.RUNNING, repository.get(claimed.previewId)!!.state)
    }

    @Test
    fun queueRequiresPersistedSuccessfulPreflightEvidence() {
        val identity = createReadyIdentity(seedPreflight = false)

        assertThrows(BisyncPreviewRejectedException::class.java) {
            BisyncPreviewRepository(context).queue(identity)
        }
    }

    @Test
    fun queueRejectsBlockedAndExpiredPreflightEvidence() {
        val blockedIdentity = createReadyIdentity()
        updatePreflight(blockedIdentity.profileId, ContentValues().apply {
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_READINESS, ProfileReadiness.BLOCKED.wireValue)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_REASON, "NATIVE_STATE_UNKNOWN")
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE, BisyncNativeState.UNKNOWN.name)
        })
        assertThrows(BisyncPreviewRejectedException::class.java) {
            BisyncPreviewRepository(context).queue(blockedIdentity)
        }

        val staleIdentity = createReadyIdentity()
        updatePreflight(staleIdentity.profileId, ContentValues().apply {
            put(
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT,
                System.currentTimeMillis() - BisyncPreviewFreshnessPolicy.MAX_AGE_MILLIS - 1L
            )
        })
        assertThrows(BisyncPreviewRejectedException::class.java) {
            BisyncPreviewRepository(context).queue(staleIdentity)
        }
    }

    @Test
    fun queueRequiresAbsentStateToBeFreshAndExplicitlyBoundToInitializationPolicy() {
        val compatible = createReadyIdentity()
        val absent = compatible.copy(
            nativeState = BisyncNativeState.ABSENT,
            acceptedBaselineFingerprint = null,
            initializationMode = BisyncPreviewResyncMode.PATH2
        )
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            assertEquals(1, db.update(
                DatabaseInfo.PROFILE_TABLE_NAME,
                ContentValues().apply {
                    put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.INITIALIZATION_REQUIRED.wireValue)
                    putNull(DatabaseInfo.PROFILE_COLUMN_REASON)
                },
                "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
                arrayOf(absent.profileId)
            ))
            assertEquals(1, db.update(
                DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME,
                ContentValues().apply {
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_READINESS, ProfileReadiness.INITIALIZATION_REQUIRED.wireValue)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_REASON)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE, BisyncNativeState.ABSENT.name)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_FILTER)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_COMPARISON)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_FINGERPRINT)
                },
                "${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID} = ?",
                arrayOf(absent.profileId)
            ))
        } finally {
            db.close()
            handler.close()
        }

        assertEquals(BisyncPreviewOperationState.QUEUED, BisyncPreviewRepository(context).queue(absent).state)
        assertThrows(IllegalArgumentException::class.java) {
            BisyncPreviewRepository(context).queue(absent.copy(initializationMode = null))
        }
    }

    private fun createReadyIdentity(seedPreflight: Boolean = true): BisyncPreviewIdentity {
        val handler = DatabaseHandler(context)
        val task = Task(0).apply {
            title = "Bisync preview repository fixture"
            direction = SyncDirectionObject.SYNC_BIDIRECTIONAL
            remoteId = "fixture-remote"
            remotePath = "disposable/root"
            localPath = "/storage/emulated/0/fixture-bisync"
        }
        val stored = handler.createTask(task, false)
        handler.close()

        val profile = ProfileRepository(context).getForLegacyTask(stored.id)
            ?: throw AssertionError("Expected persisted test profile")
        val updateHandler = DatabaseHandler(context)
        val db = updateHandler.writableDatabase
        try {
            val values = ContentValues().apply {
                put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.READY.wireValue)
                putNull(DatabaseInfo.PROFILE_COLUMN_REASON)
            }
            assertEquals(1, db.update(
                DatabaseInfo.PROFILE_TABLE_NAME,
                values,
                "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
                arrayOf(profile.profileId)
            ))
        } finally {
            db.close()
            updateHandler.close()
        }

        val identity = BisyncPreviewIdentity(
            profileId = profile.profileId,
            profileRevision = profile.revision,
            profileFingerprint = profile.fingerprint,
            engineRef = profile.engineRef,
            stateVersion = BisyncPreflightPolicy.CURRENT_STATE_VERSION,
            leftAccountFingerprint = digest('a'),
            leftScopeFingerprint = digest('b'),
            rightAccountFingerprint = digest('c'),
            rightScopeFingerprint = digest('d'),
            filterFingerprint = digest('e'),
            comparisonMode = BisyncComparisonMode.SIZE_AND_MODTIME,
            maxDeletePercent = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
            maxDeleteCount = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT,
            nativeState = BisyncNativeState.COMPATIBLE,
            acceptedBaselineFingerprint = digest('f')
        )
        if (seedPreflight) {
            val preflightHandler = DatabaseHandler(context)
            val preflightDb = preflightHandler.writableDatabase
            try {
                val evidence = ContentValues().apply {
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID, identity.profileId)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION, identity.profileRevision)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT, identity.profileFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_ENGINE_REF, identity.engineRef)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_STATE_VERSION, identity.stateVersion)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT, identity.leftAccountFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE, identity.leftScopeFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT, identity.rightAccountFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE, identity.rightScopeFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_FILTER, identity.filterFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_COMPARISON, identity.comparisonMode.wireValue)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_FINGERPRINT, identity.acceptedBaselineFingerprint)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_READINESS, ProfileReadiness.READY.wireValue)
                    putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_REASON)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT, System.currentTimeMillis())
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE, BisyncNativeState.COMPATIBLE.name)
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON, "FIXTURE")
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID, 0)
                }
                assertTrue(preflightDb.insertOrThrow(
                    DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME, null, evidence
                ) > 0L)
            } finally {
                preflightDb.close()
                preflightHandler.close()
            }
        }
        return identity
    }

    private fun updatePreflight(profileId: String, values: ContentValues) {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            assertEquals(1, db.update(
                DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME,
                values,
                "${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID} = ?",
                arrayOf(profileId)
            ))
        } finally {
            db.close()
            handler.close()
        }
    }

    private fun digest(char: Char) = char.toString().repeat(64)
}
