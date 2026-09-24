package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BisyncBackupManifestRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() {
        context.deleteDatabase(DatabaseInfo.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DatabaseInfo.DATABASE_NAME)
    }

    @Test
    fun versionFifteenUpgradeCreatesManifestSchemaAndPreservesExistingRows() {
        val databaseFile = context.getDatabasePath(DatabaseInfo.DATABASE_NAME)
        val old = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLES_TASKS)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_TRIGGER)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_FILTERS)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_MD5)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_WIFI)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TRIGGER_ADD_TYPE)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_FILTER_ID)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_DELETE_EXCLUDED)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_FOLLOWUPS_FAIL)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_FOLLOWUPS_SUCCESS)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_TRANSFERS)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_REMOTE_ID2)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_REMOTE_TYPE2)
        old.execSQL(DatabaseInfo.SQL_UPDATE_TASK_ADD_REMOTE_PATH2)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_PROFILES)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_RUNS)
        old.execSQL(DatabaseInfo.SQL_CREATE_INDEX_ACTIVE_RUN)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_RESOURCE_CLAIMS)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_BISYNC_PREFLIGHT)
        old.execSQL(DatabaseInfo.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE)
        old.execSQL(DatabaseInfo.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE_REASON)
        old.execSQL(DatabaseInfo.SQL_UPDATE_BISYNC_PREFLIGHT_ADD_RECOVERY_LISTINGS_VALID)
        old.execSQL(DatabaseInfo.SQL_CREATE_TABLE_BISYNC_PREVIEWS)
        old.execSQL(DatabaseInfo.SQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW)
        old.execSQL(DatabaseInfo.SQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY)
        val profileId = "00000000-0000-4000-8000-000000000015"
        val runId = "00000000-0000-4000-8000-000000000115"
        val previewId = "00000000-0000-4000-8000-000000000215"
        val ownerToken = "00000000-0000-4000-8000-000000000315"
        val profileFingerprint = "c".repeat(64)
        val engineRef = "rclone:rareities-rclone@${"a".repeat(40)}"
        val now = System.currentTimeMillis()
        old.insertOrThrow(Task.TABLE_NAME, null, ContentValues().apply {
            put(Task.COLUMN_NAME_ID, 1500L)
            put(Task.COLUMN_NAME_TITLE, "v15 migration task")
            put(Task.COLUMN_NAME_REMOTE_ID, "remote-left")
            put(Task.COLUMN_NAME_REMOTE_TYPE, 1)
            put(Task.COLUMN_NAME_REMOTE_PATH, "notes")
            put(Task.COLUMN_NAME_LOCAL_PATH, "/disposable/v15")
            put(Task.COLUMN_NAME_SYNC_DIRECTION, SyncDirectionObject.SYNC_BIDIRECTIONAL)
        })
        old.insertOrThrow(DatabaseInfo.PROFILE_TABLE_NAME, null, ContentValues().apply {
            put(DatabaseInfo.PROFILE_COLUMN_ID, profileId)
            put(DatabaseInfo.PROFILE_COLUMN_LEGACY_TASK_ID, 1500L)
            put(DatabaseInfo.PROFILE_COLUMN_REVISION, 1L)
            put(DatabaseInfo.PROFILE_COLUMN_TITLE, "v15 migration profile")
            put(DatabaseInfo.PROFILE_COLUMN_MODE, ProfileMode.BISYNC.wireValue)
            put(DatabaseInfo.PROFILE_COLUMN_ENDPOINT, "v15-endpoint-identity")
            put(DatabaseInfo.PROFILE_COLUMN_SETTINGS, "v15-settings")
            put(DatabaseInfo.PROFILE_COLUMN_FINGERPRINT, profileFingerprint)
            put(DatabaseInfo.PROFILE_COLUMN_ENGINE, engineRef)
            put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.INITIALIZATION_REQUIRED.wireValue)
            putNull(DatabaseInfo.PROFILE_COLUMN_REASON)
            put(DatabaseInfo.PROFILE_COLUMN_CREATED_AT, now)
            put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, now)
        })
        old.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null, ContentValues().apply {
            put(DatabaseInfo.RUN_COLUMN_ID, runId)
            put(DatabaseInfo.RUN_COLUMN_PROFILE_ID, profileId)
            put(DatabaseInfo.RUN_COLUMN_PROFILE_REVISION, 1L)
            put(DatabaseInfo.RUN_COLUMN_PROFILE_FINGERPRINT, profileFingerprint)
            put(DatabaseInfo.RUN_COLUMN_REQUESTED_MODE, ProfileMode.BISYNC.wireValue)
            put(DatabaseInfo.RUN_COLUMN_ENDPOINT, "v15-endpoint-identity")
            put(DatabaseInfo.RUN_COLUMN_SETTINGS, "v15-settings")
            put(DatabaseInfo.RUN_COLUMN_ENGINE, engineRef)
            put(DatabaseInfo.RUN_COLUMN_STATE, RunState.SUCCESS.wireValue)
            putNull(DatabaseInfo.RUN_COLUMN_REASON)
            put(DatabaseInfo.RUN_COLUMN_REQUESTED_AT, now)
            put(DatabaseInfo.RUN_COLUMN_STARTED_AT, now)
            put(DatabaseInfo.RUN_COLUMN_FINISHED_AT, now)
            put(DatabaseInfo.RUN_COLUMN_OWNER_TOKEN, ownerToken)
            put(DatabaseInfo.RUN_COLUMN_OWNER_GENERATION, 1L)
            put(DatabaseInfo.RUN_COLUMN_CANCEL_REQUESTED, 0)
            put(DatabaseInfo.RUN_COLUMN_CREATED_AT, now)
            put(DatabaseInfo.RUN_COLUMN_UPDATED_AT, now)
        })
        old.insertOrThrow(DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME, null, ContentValues().apply {
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID, profileId)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION, 1L)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT, profileFingerprint)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_ENGINE_REF, engineRef)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_STATE_VERSION, 1)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_READINESS, ProfileReadiness.INITIALIZATION_REQUIRED.wireValue)
            putNull(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_REASON)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT, now)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE, BisyncNativeState.ABSENT.name)
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON, "STATE_VERIFIED")
            put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID, 0)
        })
        old.insertOrThrow(DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME, null, ContentValues().apply {
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID, previewId)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_ID, profileId)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION, 1L)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT, profileFingerprint)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ENGINE_REF, engineRef)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATE_VERSION, 1)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT, "1".repeat(64))
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_SCOPE, "2".repeat(64))
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT, "3".repeat(64))
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE, "4".repeat(64))
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_FILTER, "5".repeat(64))
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPARISON, BisyncComparisonMode.SIZE_AND_MODTIME.wireValue)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT, 10)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT, 25)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE, BisyncNativeState.ABSENT.name)
            putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT, "6".repeat(64))
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS, BisyncPreviewOperationState.STALE.wireValue)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_TOKEN, ownerToken)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, 1L)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT, now)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT, now)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN, 0)
            put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_UPDATED_AT, now)
            putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE)
        })
        old.execSQL("CREATE TABLE preserved_v15_row (value TEXT NOT NULL)")
        old.execSQL("INSERT INTO preserved_v15_row(value) VALUES ('keep-v15')")
        old.version = 15
        old.close()

        val handler = DatabaseHandler(context)
        val upgraded = handler.writableDatabase
        try {
            assertEquals(16, upgraded.version)
            assertEquals(2L, DatabaseUtils.longForQuery(
                upgraded,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN (?, ?)",
                arrayOf(
                    DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME,
                    DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME
                )
            ))
            upgraded.rawQuery("SELECT value FROM preserved_v15_row", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep-v15", cursor.getString(0))
            }
            assertEquals(0L, DatabaseUtils.longForQuery(
                upgraded,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME}",
                null
            ))
            assertEquals(1L, DatabaseUtils.longForQuery(
                upgraded, "SELECT COUNT(*) FROM ${Task.TABLE_NAME} WHERE ${Task.COLUMN_NAME_ID} = 1500", null
            ))
            assertEquals(1L, DatabaseUtils.longForQuery(
                upgraded, "SELECT COUNT(*) FROM ${DatabaseInfo.PROFILE_TABLE_NAME} WHERE ${DatabaseInfo.PROFILE_COLUMN_ID} = ? AND ${DatabaseInfo.PROFILE_COLUMN_SETTINGS} = ?",
                arrayOf(profileId, "v15-settings")
            ))
            assertEquals(1L, DatabaseUtils.longForQuery(
                upgraded, "SELECT COUNT(*) FROM ${DatabaseInfo.RUN_TABLE_NAME} WHERE ${DatabaseInfo.RUN_COLUMN_ID} = ? AND ${DatabaseInfo.RUN_COLUMN_STATE} = ?",
                arrayOf(runId, RunState.SUCCESS.wireValue)
            ))
            assertEquals(1L, DatabaseUtils.longForQuery(
                upgraded, "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME} WHERE ${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID} = ? AND ${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE} = ?",
                arrayOf(profileId, BisyncNativeState.ABSENT.name)
            ))
            assertEquals(1L, DatabaseUtils.longForQuery(
                upgraded, "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME} WHERE ${DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID} = ? AND ${DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS} = ?",
                arrayOf(previewId, BisyncPreviewOperationState.STALE.wireValue)
            ))
            upgraded.rawQuery("SELECT * FROM ${DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME} LIMIT 0", null).use { cursor ->
                assertTrue(cursor.getColumnIndex(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_OBSERVATION_FINGERPRINT) >= 0)
            }
            assertEquals(0L, DatabaseUtils.longForQuery(
                upgraded,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME}",
                null
            ))
        } finally {
            upgraded.close()
            handler.close()
        }
    }

    @Test
    fun pairIsAtomicRunBoundPendingAndNeverAuthorizesMutation() {
        val owner = createOwner("atomic-pair")
        val repository = BisyncBackupManifestRepository(context)
        val manifest = repository.createPending(request(owner))

        assertEquals(owner.run.runId, manifest.runId)
        assertEquals(owner.profile.profileId, manifest.profileId)
        assertEquals(BisyncBackupManifestState.PENDING_VALIDATION, manifest.state)
        assertEquals("history/${owner.profile.profileId}/left", manifest.left.locator)
        assertEquals("history/${owner.profile.profileId}/right", manifest.right.locator)
        assertFalse(manifest.mutationPermitted)
        assertEquals(owner.preview.previewId, manifest.previewId)
        assertEquals(BisyncPreviewResyncMode.PATH1, manifest.initializationMode)
        assertEquals(owner.preview.identity.fingerprint, manifest.previewFingerprint)
        assertEquals(
            BisyncPreflightPolicy.observationFingerprint(owner.preflight, manifest.preflightFingerprint),
            manifest.observationFingerprint
        )
        assertTrue(manifest.userConfirmedAt >= owner.preview.completedAt!!)
        assertEquals(manifest, BisyncBackupManifestRepository(context).getForRun(owner.run.runId))
        assertEquals(listOf(manifest), BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId))

        val dbHandler = DatabaseHandler(context)
        val db = dbHandler.writableDatabase
        try {
            assertEquals(1L, DatabaseUtils.longForQuery(db, "PRAGMA foreign_keys", null))
            assertEquals(1L, DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME} WHERE " +
                    "${DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_RUN_ID} = ?",
                arrayOf(owner.run.runId)
            ))
            assertEquals(2L, DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME} WHERE " +
                    "${DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_OPERATION_ID} = ?",
                arrayOf(manifest.operationId)
            ))
            assertThrows(SQLiteConstraintException::class.java) {
                db.delete(DatabaseInfo.RUN_TABLE_NAME,
                    "${DatabaseInfo.RUN_COLUMN_ID} = ?", arrayOf(owner.run.runId))
            }
        } finally {
            db.close()
            dbHandler.close()
        }
    }

    @Test
    fun staleRunOwnerAndWrongProfileSnapshotAreRejectedWithoutRows() {
        val owner = createOwner("stale-owner")
        val staleRun = owner.run.copy(ownerToken = UUID.randomUUID().toString())
        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(request(owner, run = staleRun))
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())

        val wrongPreflight = owner.preflight.copy(profileFingerprint = "f".repeat(64))
        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(
                request(owner, preflight = wrongPreflight)
            )
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    @Test
    fun cancellationRequestedRunCannotReserveBackupLocations() {
        val owner = createOwner("cancelled-owner")
        assertTrue(RunRepository(context).requestCancellation(owner.run.runId, owner.run.ownerToken))

        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(request(owner))
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    @Test
    fun runningOwnerCannotCreateBackupManifestAfterNativeWorkMayHaveStarted() {
        val owner = createOwner("running-owner")
        val runningRun = owner.run.copy(state = RunState.RUNNING)

        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(request(owner, run = runningRun))
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    @Test
    fun changedOrExpiredPersistedPreflightCannotCreateManifest() {
        val owner = createOwner("stale-preflight")
        val differentEndpointEvidence = owner.preflight.copy(
            left = owner.preflight.left.copy(
                accountFingerprint = digest('z'),
                scope = BisyncEndpointScope.from(digest('z'), "notes")
            )
        )
        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(
                request(owner, preflight = differentEndpointEvidence)
            )
        }

        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            db.update(
                DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME,
                ContentValues().apply {
                    put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT,
                        System.currentTimeMillis() - BisyncPreflightPolicy.MAX_EVIDENCE_AGE_MILLIS)
                },
                "${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID} = ?",
                arrayOf(owner.profile.profileId)
            )
        } finally {
            db.close()
            handler.close()
        }
        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(request(owner))
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    @Test
    fun changedListingObservationCannotReusePersistedPreflightIdentity() {
        val owner = createOwner("changed-observation")
        val changedObservation = owner.preflight.copy(
            leftListing = owner.preflight.leftListing.copy(itemCount = owner.preflight.leftListing.itemCount + 1)
        )
        assertEquals(
            BisyncPreflightPolicy.evaluate(owner.preflight).identityFingerprint,
            BisyncPreflightPolicy.evaluate(changedObservation).identityFingerprint
        )

        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(
                request(owner, preflight = changedObservation)
            )
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    @Test
    fun previewRequestedBeforeCurrentPreflightCannotCreateManifest() {
        val owner = createOwner("stale-preview-order")
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            val checkedAt = DatabaseUtils.longForQuery(
                db,
                "SELECT ${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT} FROM ${DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME} WHERE ${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID} = ?",
                arrayOf(owner.profile.profileId)
            )
            assertTrue(checkedAt > 1L)
            db.update(
                DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME,
                ContentValues().apply {
                    put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT, checkedAt - 1L)
                },
                "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID} = ?",
                arrayOf(owner.preview.previewId)
            )
        } finally {
            db.close()
            handler.close()
        }

        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(request(owner))
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    @Test
    fun duplicateRunAndReusedBackupScopeCannotLeaveHalfPair() {
        val firstOwner = createOwner("first-scope")
        val secondOwner = createOwner("second-scope")
        val repository = BisyncBackupManifestRepository(context)
        val first = repository.createPending(request(
            firstOwner,
            leftLocator = "history/shared-left"
        ))

        assertThrows(BisyncBackupManifestConstraintException::class.java) {
            repository.createPending(request(
                firstOwner,
                leftLocator = "history/another-left"
            ))
        }
        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            repository.createPending(request(
                secondOwner,
                leftLocator = "history/shared-left"
            ))
        }

        assertNotNull(repository.getForRun(firstOwner.run.runId))
        assertNull(repository.getForRun(secondOwner.run.runId))
        assertEquals(1, repository.unresolvedForProfile(firstOwner.profile.profileId).size)
        assertTrue(repository.unresolvedForProfile(secondOwner.profile.profileId).isEmpty())
        val db = DatabaseHandler(context).readableDatabase
        try {
            assertEquals(1L, DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME}",
                null
            ))
            assertEquals(2L, DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME}",
                null
            ))
        } finally {
            db.close()
        }
    }

    @Test
    fun nestedBackupScopesAcrossManifestsAreRejected() {
        val firstOwner = createOwner("nested-scope-first")
        val secondOwner = createOwner("nested-scope-second")
        val repository = BisyncBackupManifestRepository(context)
        val first = repository.createPending(request(
            firstOwner,
            leftLocator = "history/shared"
        ))

        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            repository.createPending(request(
                secondOwner,
                leftLocator = "history/shared/profile-b"
            ))
        }

        assertNotNull(repository.getForRun(firstOwner.run.runId))
        assertNull(repository.getForRun(secondOwner.run.runId))
        val db = DatabaseHandler(context).readableDatabase
        try {
            assertEquals(1L, DatabaseUtils.longForQuery(
                db, "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME}", null
            ))
            assertEquals(2L, DatabaseUtils.longForQuery(
                db, "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME}", null
            ))
        } finally {
            db.close()
        }
    }

    @Test
    fun existingBackupReservationCannotBecomeAnotherProfilesSyncRoot() {
        val firstOwner = createOwner("sync-overlap-first")
        val secondOwner = createOwner(
            "sync-overlap-second",
            leftPath = "history/shared/nested",
            leftRemoteId = "remote-left-sync-overlap-first"
        )
        val repository = BisyncBackupManifestRepository(context)
        val first = repository.createPending(request(firstOwner, leftLocator = "history/shared"))

        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            repository.createPending(request(secondOwner))
        }

        assertNotNull(repository.getForRun(firstOwner.run.runId))
        assertNull(repository.getForRun(secondOwner.run.runId))
        assertEquals(1, repository.unresolvedForProfile(firstOwner.profile.profileId).size)
        assertTrue(repository.unresolvedForProfile(secondOwner.profile.profileId).isEmpty())
        assertNotNull(first)
    }

    @Test
    fun failureOnSecondLocationInsertRollsBackManifestAndFirstLocation() {
        val owner = createOwner("right-insert-rollback")
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            db.execSQL(
                "CREATE TRIGGER test_fail_bisync_backup_right " +
                    "BEFORE INSERT ON ${DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME} " +
                    "WHEN NEW.${DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_SIDE} = 'RIGHT' " +
                    "BEGIN SELECT RAISE(ABORT, 'injected right-side failure'); END"
            )
        } finally {
            db.close()
            handler.close()
        }

        try {
            assertThrows(BisyncBackupManifestConstraintException::class.java) {
                BisyncBackupManifestRepository(context).createPending(request(owner))
            }
        } finally {
            val cleanup = DatabaseHandler(context)
            val cleanupDb = cleanup.writableDatabase
            try {
                cleanupDb.execSQL("DROP TRIGGER IF EXISTS test_fail_bisync_backup_right")
            } finally {
                cleanupDb.close()
                cleanup.close()
            }
        }

        assertNull(BisyncBackupManifestRepository(context).getForRun(owner.run.runId))
        val verifyHandler = DatabaseHandler(context)
        val verifyDb = verifyHandler.readableDatabase
        try {
            assertEquals(0L, DatabaseUtils.longForQuery(
                verifyDb,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME}",
                null
            ))
            assertEquals(0L, DatabaseUtils.longForQuery(
                verifyDb,
                "SELECT COUNT(*) FROM ${DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME}",
                null
            ))
        } finally {
            verifyDb.close()
            verifyHandler.close()
        }
    }

    @Test
    fun concurrentDuplicateReservationsCommitExactlyOneCompletePair() {
        val owner = createOwner("concurrent-pair")
        val request = request(owner)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val conflicts = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        repeat(2) {
            executor.execute {
                ready.countDown()
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw AssertionError("reservation start timed out")
                    BisyncBackupManifestRepository(context).createPending(request)
                    successes.incrementAndGet()
                } catch (_: BisyncBackupManifestConstraintException) {
                    conflicts.incrementAndGet()
                } catch (_: BisyncBackupManifestRejectedException) {
                    conflicts.incrementAndGet()
                } catch (unexpected: Throwable) {
                    failure.compareAndSet(null, unexpected)
                }
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Unexpected concurrent reservation failure", it) }
        assertEquals(1, successes.get())
        assertEquals(1, conflicts.get())
        val manifest = BisyncBackupManifestRepository(context)
            .getForRun(owner.run.runId)
        assertNotNull(manifest)
        assertEquals(2, listOf(manifest!!.left, manifest.right).size)
        assertEquals(1, BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).size)
    }

    @Test
    fun backupInsideEitherSyncRootIsRejectedBeforePersistence() {
        val owner = createOwner("overlap")
        assertThrows(BisyncBackupManifestRejectedException::class.java) {
            BisyncBackupManifestRepository(context).createPending(
                request(owner, leftLocator = "notes/history/left")
            )
        }
        assertTrue(BisyncBackupManifestRepository(context)
            .unresolvedForProfile(owner.profile.profileId).isEmpty())
    }

    private fun createOwner(
        label: String,
        leftPath: String = "notes",
        rightPath: String = "vault",
        leftRemoteId: String = "remote-left-$label",
        rightRemoteId: String = "remote-right-$label"
    ): TestOwner {
        val handler = DatabaseHandler(context)
        val task = Task(0L).apply {
            title = label
            remoteId = leftRemoteId
            remotePath = leftPath
            remotePath2 = rightPath
            remoteId2 = rightRemoteId
            localPath = "/disposable/$label"
            direction = SyncDirectionObject.SYNC_BIDIRECTIONAL
        }
        val stored = try {
            handler.createTask(task)
        } finally {
            handler.close()
        }
        val profile = requireNotNull(ProfileRepository(context).getForLegacyTask(stored.id))
        val now = System.currentTimeMillis()
        val input = preflight(profile)
        val result = BisyncPreflightPolicy.evaluate(input)
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, result.readiness)
        BisyncPreflightRepository(context).recordAttempt(
            profile.profileId, profile.revision, profile.fingerprint, input, result, now - 1L
        )

        val runId = UUID.randomUUID().toString()
        val ownerToken = UUID.randomUUID().toString()
        val run = RunRecord(
            runId = runId,
            profileId = profile.profileId,
            profileRevision = profile.revision,
            profileFingerprint = profile.fingerprint,
            requestedMode = ProfileMode.BISYNC,
            endpointIdentity = profile.endpointIdentity,
            settings = profile.settings,
            engineRef = profile.engineRef,
            state = RunState.PREFLIGHT,
            reason = null,
            requestedAt = now,
            dueAt = null,
            startedAt = null,
            finishedAt = null,
            ownerToken = ownerToken,
            ownerGeneration = 1L,
            cancellationRequested = false,
            successfulItems = null,
            failedItems = null,
            conflictItems = null,
            unknownItems = null,
            createdAt = now,
            updatedAt = now
        )
        val dbHandler = DatabaseHandler(context)
        val db = dbHandler.writableDatabase
        try {
            val values = ContentValues().apply {
                put(DatabaseInfo.RUN_COLUMN_ID, run.runId)
                put(DatabaseInfo.RUN_COLUMN_PROFILE_ID, run.profileId)
                put(DatabaseInfo.RUN_COLUMN_PROFILE_REVISION, run.profileRevision)
                put(DatabaseInfo.RUN_COLUMN_PROFILE_FINGERPRINT, run.profileFingerprint)
                put(DatabaseInfo.RUN_COLUMN_REQUESTED_MODE, run.requestedMode.wireValue)
                put(DatabaseInfo.RUN_COLUMN_ENDPOINT, run.endpointIdentity)
                put(DatabaseInfo.RUN_COLUMN_SETTINGS, run.settings)
                put(DatabaseInfo.RUN_COLUMN_ENGINE, run.engineRef)
                put(DatabaseInfo.RUN_COLUMN_STATE, run.state.wireValue)
                putNull(DatabaseInfo.RUN_COLUMN_REASON)
                put(DatabaseInfo.RUN_COLUMN_REQUESTED_AT, run.requestedAt)
                putNull(DatabaseInfo.RUN_COLUMN_DUE_AT)
                putNull(DatabaseInfo.RUN_COLUMN_STARTED_AT)
                putNull(DatabaseInfo.RUN_COLUMN_FINISHED_AT)
                put(DatabaseInfo.RUN_COLUMN_OWNER_TOKEN, run.ownerToken)
                put(DatabaseInfo.RUN_COLUMN_OWNER_GENERATION, run.ownerGeneration)
                put(DatabaseInfo.RUN_COLUMN_CANCEL_REQUESTED, 0)
                putNull(DatabaseInfo.RUN_COLUMN_SUCCESSFUL_ITEMS)
                putNull(DatabaseInfo.RUN_COLUMN_FAILED_ITEMS)
                putNull(DatabaseInfo.RUN_COLUMN_CONFLICT_ITEMS)
                putNull(DatabaseInfo.RUN_COLUMN_UNKNOWN_ITEMS)
                put(DatabaseInfo.RUN_COLUMN_CREATED_AT, run.createdAt)
                put(DatabaseInfo.RUN_COLUMN_UPDATED_AT, run.updatedAt)
            }
            db.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null, values)
        } finally {
            db.close()
            dbHandler.close()
        }
        val currentProfile = requireNotNull(ProfileRepository(context).get(profile.profileId))
        val previewIdentity = BisyncPreviewIdentity.fromPreflight(
            currentProfile, input, result, BisyncPreviewResyncMode.PATH1
        )
        val previewRepository = BisyncPreviewRepository(context)
        val queuedPreview = previewRepository.queue(previewIdentity)
        val claimedPreview = previewRepository.claim(
            queuedPreview.previewId, queuedPreview.ownerToken, previewIdentity
        )
        val completedAt = System.currentTimeMillis()
        assertTrue(previewRepository.finish(
            claimedPreview.previewId,
            claimedPreview.ownerToken,
            claimedPreview.ownerGeneration,
            previewIdentity,
            BisyncPreviewParseResult.Available(
                BisyncPreviewSummary(BisyncPreviewStatus.COMPLETE, 0, 0, 0, 0, 0, false)
            ),
            true,
            completedAt
        ))
        val completedPreview = requireNotNull(previewRepository.get(queuedPreview.previewId))
        return TestOwner(currentProfile, run, input, completedPreview)
    }

    private fun preflight(profile: ProfileRecord): BisyncPreflightInput {
        val leftAccount = digest('a')
        val rightAccount = digest('b')
        return BisyncPreflightInput(
            profileRevision = profile.revision,
            profileFingerprint = profile.fingerprint,
            engineRef = profile.engineRef,
            stateVersion = BisyncPreflightPolicy.CURRENT_STATE_VERSION,
            left = BisyncEndpointEvidence(
                leftAccount, BisyncEndpointScope.from(leftAccount, "notes"), true
            ),
            right = BisyncEndpointEvidence(
                rightAccount, BisyncEndpointScope.from(rightAccount, "vault"), true
            ),
            leftListing = BisyncListingEvidence(true, true, 0),
            rightListing = BisyncListingEvidence(true, true, 0),
            filterFingerprint = LegacyProfileMapper.fingerprintNoFilter(),
            filterResolved = true,
            comparisonMode = BisyncComparisonMode.SIZE_AND_MODTIME,
            nativeState = BisyncNativeState.ABSENT
        )
    }

    private fun request(
        owner: TestOwner,
        run: RunRecord = owner.run,
        preflight: BisyncPreflightInput = owner.preflight,
        leftLocator: String = "history/${owner.profile.profileId}/left",
        rightLocator: String = "history/${owner.profile.profileId}/right"
    ): BisyncBackupManifestRequest {
        val createdAt = System.currentTimeMillis()
        return BisyncBackupManifestRequest(
            run = run,
            preflight = preflight,
            previewId = owner.preview.previewId,
            leftBackupLocator = leftLocator,
            rightBackupLocator = rightLocator,
            userConfirmedAt = createdAt,
            createdAt = createdAt
        )
    }

    private fun digest(value: Char): String = value.toString().repeat(64)

    private data class TestOwner(
        val profile: ProfileRecord,
        val run: RunRecord,
        val preflight: BisyncPreflightInput,
        val preview: BisyncPreviewOperation
    )
}
