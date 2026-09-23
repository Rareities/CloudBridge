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

    private fun createReadyIdentity(): BisyncPreviewIdentity {
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

        return BisyncPreviewIdentity(
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
    }

    private fun digest(char: Char) = char.toString().repeat(64)
}
