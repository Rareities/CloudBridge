package ca.pkay.rcloneexplorer.Database;

import android.content.Context;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;

import ca.pkay.rcloneexplorer.util.EndpointResource;
import ca.pkay.rcloneexplorer.util.EndpointConflictCoordinator;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.Items.Task;

import org.json.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ResourceClaimRepositoryTest {
    private Context testContext;
    private ResourceClaimRepository repository;

    @Before
    public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        testContext = instrumentation.getContext();
        testContext.deleteDatabase(DatabaseInfo.DATABASE_NAME);
        repository = new ResourceClaimRepository(testContext);
    }

    @After
    public void tearDown() {
        if (testContext != null) {
            testContext.deleteDatabase(DatabaseInfo.DATABASE_NAME);
        }
    }

    @Test
    public void overlappingClaimsAreRejectedAcrossRepositoryInstances() {
        ResourceClaimLease owner = repository.acquire(
                "sync", Collections.singletonList(EndpointResource.remote("drive", "root/team")));
        try {
            ResourceClaimRepository restartedView = new ResourceClaimRepository(testContext);
            try {
                restartedView.acquire("delete", Collections.singletonList(
                        EndpointResource.remote("drive", "root/team/child")));
                fail("overlapping parent/child path must be blocked");
            } catch (ResourceClaimConflictException expected) {
                assertEquals(1, restartedView.activeCount());
            }
        } finally {
            owner.close();
        }
        assertEquals(0, repository.activeCount());
    }

    @Test
    public void unrelatedProviderRootsCanProceedConcurrently() {
        ResourceClaimLease first = repository.acquire(
                "copy", Collections.singletonList(EndpointResource.remote("drive", "root/team")));
        try {
            ResourceClaimLease second = repository.acquire(
                    "copy", Collections.singletonList(EndpointResource.remote("s3", "root/team")));
            second.close();
        } finally {
            first.close();
        }
        assertEquals(0, repository.activeCount());
    }

    @Test
    public void abandonedClaimRemainsAfterRepositoryRecreation() {
        repository.acquire("move", Collections.singletonList(
                EndpointResource.remote("drive", "root/team")));

        ResourceClaimRepository recreated = new ResourceClaimRepository(testContext);
        assertEquals(1, recreated.activeCount());
        try {
            recreated.acquire("copy", Collections.singletonList(
                    EndpointResource.remote("drive", "root/team")));
            fail("abandoned claim must not be auto-expired");
        } catch (ResourceClaimConflictException expected) {
            assertEquals(1, recreated.activeCount());
        }
    }

    @Test
    public void simultaneousOverlappingAcquisitionsHaveExactlyOneOwner() throws Exception {
        int contenders = 8;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(contenders);
        AtomicInteger owners = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("concurrent test start timed out");
                        }
                        ResourceClaimLease lease;
                        try {
                            lease = repository.acquire("copy", Collections.singletonList(
                                    EndpointResource.remote("drive", "root/team")));
                        } catch (ResourceClaimConflictException expected) {
                            attempted.countDown();
                            return;
                        }
                        owners.incrementAndGet();
                        attempted.countDown();
                        if (!attempted.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("contenders did not finish acquiring");
                        }
                        lease.close();
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        attempted.countDown();
                    }
                });
            }
            assertTrue("workers did not become ready", ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue("workers did not finish", attempted.await(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue("workers did not stop", executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (failure.get() != null) throw new AssertionError("concurrent claim test failed", failure.get());
        assertEquals(1, owners.get());
        assertEquals(0, repository.activeCount());
    }

    @Test
    public void commandCoordinatorSerializesOverlappingAndWrappedTargets() throws Exception {
        EndpointConflictCoordinator coordinator = new EndpointConflictCoordinator(testContext);
        JSONObject remotes = new JSONObject().put("drive", new JSONObject().put("type", "drive"));
        ResourceClaimLease parent = coordinator.acquireForCommand(
                new String[]{"/rclone", "sync", "drive:root/team", "drive:root/archive"},
                remotes, "native-sync");
        try {
            try {
                coordinator.acquireForCommand(
                        new String[]{"/rclone", "delete", "drive:root/team/child"},
                        remotes, "native-delete");
                fail("parent/child native paths must share one claim scope");
            } catch (java.io.IOException expected) {
                // Expected conflict from the durable shared coordinator.
            }
        } finally {
            parent.close();
        }

        JSONObject wrapped = new JSONObject().put("alias", new JSONObject()
                .put("type", "alias").put("remote", "drive:root/team"));
        ResourceClaimLease alias = coordinator.acquireForCommand(
                new String[]{"/rclone", "delete", "alias:private"}, wrapped, "native-alias");
        try {
            try {
                coordinator.acquireForCommand(
                        new String[]{"/rclone", "delete", "drive:elsewhere"}, remotes, "native-delete");
                fail("ambiguous wrapper targets must serialize globally");
            } catch (java.io.IOException expected) {
                // Unknown physical wrapper root intentionally blocks unrelated app mutations.
            }
        } finally {
            alias.close();
        }

        ResourceClaimLease literalPath = coordinator.acquireForCommand(
                new String[]{"/rclone", "delete", "--", "--version"}, remotes, "literal-path");
        try {
            try {
                coordinator.acquireForCommand(
                        new String[]{"/rclone", "delete", "drive:elsewhere"}, remotes, "native-delete");
                fail("a literal --version target must not bypass resource ownership");
            } catch (java.io.IOException expected) {
                // The unresolvable relative path is conservatively global.
            }
        } finally {
            literalPath.close();
        }
    }

    @Test
    public void bisyncCoordinatorReservesBothExplicitBackupRoots() throws Exception {
        EndpointConflictCoordinator coordinator = new EndpointConflictCoordinator(testContext);
        JSONObject remotes = new JSONObject().put("drive", new JSONObject().put("type", "drive"));
        String localBackup = new java.io.File(testContext.getFilesDir(), "history/run-1").getAbsolutePath();
        ResourceClaimLease bisync = coordinator.acquireForCommand(
                new String[]{"/rclone", "bisync", "--filter-from", "/data/local/tmp/filters.txt",
                        new java.io.File(testContext.getFilesDir(), "sync").getAbsolutePath(), "drive:sync",
                        "--backup-dir1", localBackup, "--backup-dir2=drive:history/run-1"},
                remotes, "native-bisync");
        try {
            try {
                coordinator.acquireForCommand(new String[]{"/rclone", "delete", localBackup + "/child"},
                        remotes, "native-delete-local-backup");
                fail("a local backup root must remain claimed for the Bisync process lifetime");
            } catch (java.io.IOException expected) {
                // The run's durable claim covers its local backup root.
            }
            try {
                coordinator.acquireForCommand(new String[]{"/rclone", "delete", "drive:history/run-1/child"},
                        remotes, "native-delete-remote-backup");
                fail("a remote backup root must remain claimed for the Bisync process lifetime");
            } catch (java.io.IOException expected) {
                // The run's durable claim covers its remote backup root.
            }
        } finally {
            bisync.close();
        }
        assertEquals(0, repository.activeCount());
    }

    @Test
    public void unknownValueOptionBeforeBisyncEndpointsFallsBackToGlobalClaim() throws Exception {
        EndpointConflictCoordinator coordinator = new EndpointConflictCoordinator(testContext);
        JSONObject remotes = new JSONObject().put("drive", new JSONObject().put("type", "drive"));
        ResourceClaimLease bisync = coordinator.acquireForCommand(
                new String[]{"/rclone", "bisync", "--future-option", "/data/local/tmp/filters.txt",
                        "/data/local/tmp/sync", "drive:sync", "--backup-dir1", "/data/local/tmp/history/run-2"},
                remotes, "ambiguous-bisync");
        try {
            try {
                coordinator.acquireForCommand(new String[]{"/rclone", "delete", "drive:unrelated"},
                        remotes, "native-delete-unrelated");
                fail("ambiguous endpoint parsing must serialize globally");
            } catch (java.io.IOException expected) {
                // Unknown option arity is not guessed; fail closed to a global claim.
            }
        } finally {
            bisync.close();
        }
    }

    @Test
    public void versionTenDatabaseUpgradesWithoutDroppingExistingRows() {
        SQLiteDatabase versionTen = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionTen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        versionTen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_RUNS()
                .replace(DatabaseInfo.RUN_COLUMN_FILTER_SNAPSHOT + " TEXT,", ""));
        versionTen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_ACTIVE_RUN());
        ContentValues profile = new ContentValues();
        profile.put(DatabaseInfo.PROFILE_COLUMN_ID, "migration-profile");
        profile.put(DatabaseInfo.PROFILE_COLUMN_REVISION, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_TITLE, "Migration profile");
        profile.put(DatabaseInfo.PROFILE_COLUMN_MODE, "SYNC_ONE_WAY");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENDPOINT, "endpoint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_SETTINGS, "settings");
        profile.put(DatabaseInfo.PROFILE_COLUMN_FINGERPRINT, "fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENGINE, "engine");
        profile.put(DatabaseInfo.PROFILE_COLUMN_READINESS, "PREFLIGHT_REQUIRED");
        profile.put(DatabaseInfo.PROFILE_COLUMN_CREATED_AT, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, 1);
        versionTen.insertOrThrow(DatabaseInfo.PROFILE_TABLE_NAME, null, profile);
        versionTen.execSQL("CREATE TABLE preserved_test_row (value TEXT NOT NULL)");
        versionTen.execSQL("INSERT INTO preserved_test_row(value) VALUES ('keep')");
        versionTen.setVersion(10);
        versionTen.close();

        ResourceClaimLease lease = repository.acquire("migration-test", Collections.singletonList(
                EndpointResource.remote("drive", "root/team")));
        try {
            SQLiteDatabase upgraded = SQLiteDatabase.openDatabase(
                    testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME).getPath(),
                    null, SQLiteDatabase.OPEN_READONLY);
            try {
                assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
                try (android.database.Cursor cursor = upgraded.rawQuery(
                        "SELECT value FROM preserved_test_row", null)) {
                    assertTrue(cursor.moveToFirst());
                    assertEquals("keep", cursor.getString(0));
                }
                try (android.database.Cursor cursor = upgraded.rawQuery(
                        "SELECT COUNT(*) FROM " + DatabaseInfo.CLAIM_TABLE_NAME, null)) {
                    assertTrue(cursor.moveToFirst());
                    assertEquals(1, cursor.getInt(0));
                }
                try (android.database.Cursor cursor = upgraded.rawQuery(
                        "SELECT " + DatabaseInfo.PROFILE_COLUMN_ID + " FROM " + DatabaseInfo.PROFILE_TABLE_NAME
                                + " WHERE " + DatabaseInfo.PROFILE_COLUMN_ID + " = ?",
                        new String[]{"migration-profile"})) {
                    assertTrue(cursor.moveToFirst());
                }
                try (android.database.Cursor cursor = upgraded.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        new String[]{DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME})) {
                    assertTrue(cursor.moveToFirst());
                }
            } finally {
                upgraded.close();
            }
        } finally {
            lease.close();
        }
    }

    @Test
    public void versionEightUpgradeCreatesNewLedgersAndPreservesLegacyRows() {
        SQLiteDatabase versionEight = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionEight.execSQL("CREATE TABLE preserved_v8_row (value TEXT NOT NULL)");
        versionEight.execSQL("INSERT INTO preserved_v8_row(value) VALUES ('keep-v8')");
        versionEight.setVersion(8);
        versionEight.close();

        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase upgraded = handler.getWritableDatabase();
        try {
            assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT value FROM preserved_v8_row", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("keep-v8", cursor.getString(0));
            }
            for (String table : new String[]{
                    DatabaseInfo.PROFILE_TABLE_NAME,
                    DatabaseInfo.RUN_TABLE_NAME,
                    DatabaseInfo.CLAIM_TABLE_NAME,
                    DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME}) {
                try (android.database.Cursor cursor = upgraded.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        new String[]{table})) {
                    assertTrue("missing migrated table " + table, cursor.moveToFirst());
                }
            }
        } finally {
            upgraded.close();
            handler.close();
        }
    }

    @Test
    public void taskWithoutFilterRemainsNullAfterDatabaseRoundTrip() {
        DatabaseHandler handler = new DatabaseHandler(testContext);
        try {
            Task task = new Task(0);
            task.setTitle("no-filter regression");
            task.setDirection(SyncDirectionObject.SYNC_LOCAL_TO_REMOTE);
            task.setRemoteId("regression-remote");
            task.setRemotePath("root");
            task.setLocalPath("/storage/emulated/0/regression");
            Task stored = handler.createTask(task, false);
            Task loaded = handler.getTask(stored.getId());
            assertTrue(loaded != null);
            assertNull(loaded.getFilterId());
        } finally {
            handler.close();
        }
    }

    @Test
    public void nullEndpointPathsRemainInvalidInsteadOfBecomingProviderRoot() {
        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase database = handler.getWritableDatabase();
        database.execSQL("INSERT INTO task_table(task_title, task_remote_id, task_remote_type, "
                + "task_remote_path, task_local_path, task_direction) VALUES(NULL, NULL, 0, NULL, NULL, 5)");
        long taskId = DatabaseUtils.longForQuery(database, "SELECT last_insert_rowid()", null);
        database.close();

        try {
            Task loaded = handler.getTask(taskId);
            assertTrue(loaded != null);
            assertEquals("", loaded.getRemoteId());
            String invalidPath = String.valueOf((char) 0);
            assertEquals(invalidPath, loaded.getRemotePath());
            assertEquals(invalidPath, loaded.getLocalPath());
        } finally {
            handler.close();
        }
    }

    @Test
    public void bisyncLegacyConfirmationBlockIsDurableAndKeepsNoAcceptedBaseline() {
        DatabaseHandler handler = new DatabaseHandler(testContext);
        Task task = new Task(0);
        task.setTitle("legacy bisync migration guard");
        task.setDirection(SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL);
        task.setRemoteId("regression-remote");
        task.setRemotePath("root");
        task.setLocalPath("/storage/emulated/0/regression");
        Task stored = handler.createTask(task, false);
        handler.close();

        ProfileRecord profile = new ProfileRepository(testContext).getForLegacyTask(stored.getId());
        assertTrue(profile != null);
        BisyncPreflightReason reason = BisyncPreflightReason.LEGACY_MIGRATION_CONFIRMATION_REQUIRED;
        BisyncPreflightInput input = new BisyncPreflightInput(
                profile.getRevision(),
                profile.getFingerprint(),
                "rclone:" + ca.pkay.rcloneexplorer.BuildConfig.RCLONE_ENGINE_VERSION + "@"
                        + ca.pkay.rcloneexplorer.BuildConfig.RCLONE_ENGINE_REF,
                BisyncPreflightPolicy.CURRENT_STATE_VERSION,
                new BisyncEndpointEvidence(null, BisyncEndpointScope.Companion.unknown(), null),
                new BisyncEndpointEvidence(null, BisyncEndpointScope.Companion.unknown(), null),
                new BisyncListingEvidence(false, false, 0, reason),
                new BisyncListingEvidence(false, false, 0, reason),
                null,
                false,
                BisyncComparisonMode.SIZE_AND_MODTIME,
                BisyncNativeState.UNKNOWN,
                null,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT);
        BisyncPreflightResult result = new BisyncPreflightResult(
                ProfileReadiness.BLOCKED, reason, null, null, null);
        new BisyncPreflightRepository(testContext).recordAttempt(
                profile.getProfileId(), profile.getRevision(), profile.getFingerprint(), input, result, 123L);

        BisyncPreflightStatus status = new BisyncPreflightRepository(testContext).get(profile.getProfileId());
        assertTrue(status != null);
        assertEquals(ProfileReadiness.BLOCKED, status.getReadiness());
        assertEquals(reason.getWireValue(), status.getReasonCode());
        assertNull(status.getAcceptedBaseline());
    }

    @Test
    public void versionTwelvePreflightRowsGainConservativeNativeStateColumns() {
        SQLiteDatabase versionTwelve = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionTwelve.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        createPreV17RunsTable(versionTwelve);
        versionTwelve.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_BISYNC_PREFLIGHT());

        ContentValues profile = new ContentValues();
        profile.put(DatabaseInfo.PROFILE_COLUMN_ID, "migration-profile-v12");
        profile.put(DatabaseInfo.PROFILE_COLUMN_REVISION, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_TITLE, "Migration profile");
        profile.put(DatabaseInfo.PROFILE_COLUMN_MODE, "BISYNC");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENDPOINT, "endpoint-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_SETTINGS, "settings-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_FINGERPRINT, "profile-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENGINE, "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        profile.put(DatabaseInfo.PROFILE_COLUMN_READINESS, "BLOCKED");
        profile.put(DatabaseInfo.PROFILE_COLUMN_CREATED_AT, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, 1);
        versionTwelve.insertOrThrow(DatabaseInfo.PROFILE_TABLE_NAME, null, profile);
        versionTwelve.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null,
                createMigrationRun("migration-run-v12", "migration-profile-v12", 124L));

        ContentValues preflight = new ContentValues();
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID, "migration-profile-v12");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION, 1);
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT, "profile-fingerprint");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_ENGINE_REF, "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_STATE_VERSION, 1);
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_READINESS, "BLOCKED");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_REASON, "NATIVE_STATE_UNKNOWN");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT, 123L);
        versionTwelve.insertOrThrow(DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME, null, preflight);
        versionTwelve.setVersion(12);
        versionTwelve.close();

        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase upgraded = handler.getWritableDatabase();
        try {
            assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
            assertRunFilterSnapshotColumnExists(upgraded);
            assertMigrationRunRetained(upgraded, "migration-run-v12", "migration-profile-v12", 124L);
            assertNoMigrationForeignKeyViolations(upgraded);
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE + ", "
                            + DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON + ", "
                            + DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID + " FROM "
                            + DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME + " WHERE "
                            + DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID + " = ?",
                    new String[]{"migration-profile-v12"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("UNKNOWN", cursor.getString(0));
                assertEquals("STATE_NOT_RECORDED", cursor.getString(1));
                assertEquals(0, cursor.getInt(2));
            }
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT + " FROM "
                            + DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME + " WHERE "
                            + DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID + " = ?",
                    new String[]{"migration-profile-v12"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals(123L, cursor.getLong(0));
            }
        } finally {
            upgraded.close();
            handler.close();
        }
    }

    @Test
    public void versionThirteenUpgradeAddsPreviewLedgerWithoutLosingProfiles() {
        SQLiteDatabase versionThirteen = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionThirteen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        createPreV17RunsTable(versionThirteen);
        createPreV16PreflightTable(versionThirteen);

        ContentValues profile = new ContentValues();
        profile.put(DatabaseInfo.PROFILE_COLUMN_ID, "migration-profile-v13");
        profile.put(DatabaseInfo.PROFILE_COLUMN_REVISION, 3);
        profile.put(DatabaseInfo.PROFILE_COLUMN_TITLE, "Existing Bisync profile");
        profile.put(DatabaseInfo.PROFILE_COLUMN_MODE, "BISYNC");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENDPOINT, "endpoint-snapshot");
        profile.put(DatabaseInfo.PROFILE_COLUMN_SETTINGS, "settings-snapshot");
        profile.put(DatabaseInfo.PROFILE_COLUMN_FINGERPRINT, "profile-snapshot");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENGINE, "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        profile.put(DatabaseInfo.PROFILE_COLUMN_READINESS, "PREFLIGHT_REQUIRED");
        profile.put(DatabaseInfo.PROFILE_COLUMN_CREATED_AT, 100L);
        profile.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, 200L);
        versionThirteen.insertOrThrow(DatabaseInfo.PROFILE_TABLE_NAME, null, profile);
        versionThirteen.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null,
                createMigrationRun("migration-run-v13", "migration-profile-v13", 201L,
                        3L, "profile-snapshot"));
        versionThirteen.setVersion(13);
        versionThirteen.close();

        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase upgraded = handler.getWritableDatabase();
        try {
            assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
            assertRunFilterSnapshotColumnExists(upgraded);
            assertMigrationRunRetained(upgraded, "migration-run-v13", "migration-profile-v13", 201L);
            assertNoMigrationForeignKeyViolations(upgraded);
            assertEquals(1L, DatabaseUtils.longForQuery(upgraded,
                    "SELECT COUNT(*) FROM " + DatabaseInfo.PROFILE_TABLE_NAME, null));
            assertEquals(1L, DatabaseUtils.longForQuery(upgraded,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME}));
            assertEquals(1L, DatabaseUtils.longForQuery(upgraded,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='bisync_preview_one_owner'",
                    null));
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.PROFILE_COLUMN_REVISION + ", "
                            + DatabaseInfo.PROFILE_COLUMN_FINGERPRINT + " FROM "
                            + DatabaseInfo.PROFILE_TABLE_NAME + " WHERE "
                            + DatabaseInfo.PROFILE_COLUMN_ID + " = ?",
                    new String[]{"migration-profile-v13"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals(3L, cursor.getLong(0));
                assertEquals("profile-snapshot", cursor.getString(1));
            }
        } finally {
            upgraded.close();
            handler.close();
        }
    }

    @Test
    public void versionFourteenUpgradeDoesNotInventInitializationPreferenceOrReleaseActiveOwner() {
        SQLiteDatabase versionFourteen = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionFourteen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        createPreV17RunsTable(versionFourteen);
        createPreV16PreflightTable(versionFourteen);
        insertMigrationProfile(versionFourteen, "legacy-profile");
        insertMigrationProfile(versionFourteen, "legacy-queued-profile");
        versionFourteen.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null,
                createMigrationRun("migration-run-v14", "legacy-profile", 99L));
        createV14PreviewSchema(versionFourteen);
        versionFourteen.insertOrThrow(DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME, null,
                createV14MigrationPreview("legacy-absent-preview", "legacy-profile",
                        "RUNNING", 100L, 101L, 102L));
        versionFourteen.insertOrThrow(DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME, null,
                createV14MigrationPreview("legacy-absent-queued-preview", "legacy-queued-profile",
                        "QUEUED", 200L, null, 201L));
        versionFourteen.setVersion(14);
        versionFourteen.close();

        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase upgraded = handler.getWritableDatabase();
        try {
            assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
            assertRunFilterSnapshotColumnExists(upgraded);
            assertMigrationRunRetained(upgraded, "migration-run-v14", "legacy-profile", 99L);
            assertNoMigrationForeignKeyViolations(upgraded);
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_ID + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_TOKEN + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT + " FROM "
                            + DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME + " WHERE "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID + " = ?",
                    new String[]{"legacy-absent-preview"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("RECOVERY_REQUIRED", cursor.getString(0));
                assertEquals(2L, cursor.getLong(1));
                assertEquals("INITIALIZATION_POLICY_MISSING", cursor.getString(2));
                assertNull(cursor.getString(3));
                assertTrue(cursor.isNull(4));
                assertEquals("legacy-profile", cursor.getString(5));
                assertEquals("migration-preview-owner-legacy-absent-preview", cursor.getString(6));
                assertEquals(100L, cursor.getLong(7));
            }
            assertEquals(1L, DatabaseUtils.longForQuery(upgraded,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='bisync_preview_one_owner'", null));
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE + ", "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " FROM "
                            + DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME + " WHERE "
                            + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID + " = ?",
                    new String[]{"legacy-absent-queued-preview"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("STALE", cursor.getString(0));
                assertEquals(1L, cursor.getLong(1));
                assertEquals("INITIALIZATION_POLICY_MISSING", cursor.getString(2));
                assertNull(cursor.getString(3));
                assertEquals(201L, cursor.getLong(4));
            }
            assertEquals(1L, DatabaseUtils.longForQuery(upgraded,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='bisync_preview_one_owner'",
                    null));
        } finally {
            upgraded.close();
            handler.close();
        }
    }

    @Test
    public void versionFifteenUpgradeAddsObservationBackupAndRunSnapshotSchema() {
        SQLiteDatabase versionFifteen = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionFifteen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        createPreV17RunsTable(versionFifteen);
        createPreV16PreflightTable(versionFifteen);
        createV15PreviewSchema(versionFifteen);
        versionFifteen.setVersion(15);
        versionFifteen.close();

        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase upgraded = handler.getWritableDatabase();
        try {
            assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
            assertRunFilterSnapshotColumnExists(upgraded);
            assertMigrationColumnExists(upgraded, DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME,
                    DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_OBSERVATION_FINGERPRINT);
            assertMigrationTableExists(upgraded, DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME);
            assertMigrationTableExists(upgraded, DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME);
            assertMigrationIndexExists(upgraded, "bisync_backup_one_unresolved_profile");
            assertMigrationIndexExists(upgraded, "bisync_backup_history");
            assertMigrationIndexDefinition(upgraded, "bisync_backup_one_unresolved_profile",
                    "CREATE UNIQUE INDEX", "ON " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME
                            + "(" + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID + ")",
                    "WHERE " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS
                            + " IN ('PENDING_VALIDATION','BACKUP_VERIFIED','MUTATION_IN_PROGRESS',"
                            + "'RECOVERY_REQUIRED','RESTORE_REQUIRED','RESTORE_IN_PROGRESS','INVALIDATED')");
            assertMigrationIndexDefinition(upgraded, "bisync_backup_history",
                    "CREATE INDEX", "ON " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME + "("
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID + ","
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT + " DESC)");
            assertNoMigrationForeignKeyViolations(upgraded);
        } finally {
            upgraded.close();
            handler.close();
        }
    }

    @Test
    public void versionSixteenUpgradeAddsRunFilterSnapshotWithoutDroppingNewerLedgers() {
        SQLiteDatabase versionSixteen = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionSixteen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        createPreV17RunsTable(versionSixteen);
        createPreV16PreflightTable(versionSixteen);
        versionSixteen.execSQL(
                DatabaseInfo.Companion.getSQL_UPDATE_BISYNC_PREFLIGHT_ADD_OBSERVATION_FINGERPRINT());
        createV15PreviewSchema(versionSixteen);
        createV16BackupTables(versionSixteen);
        insertMigrationProfile(versionSixteen, "migration-profile-v16");
        versionSixteen.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null,
                createMigrationRun("migration-active-run-v16", "migration-profile-v16", 123L));
        versionSixteen.insertOrThrow(DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME, null,
                createV16MigrationPreview("migration-preview-v16", "migration-profile-v16", 123L));
        versionSixteen.insertOrThrow(DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME, null,
                createV16MigrationBackupManifest("migration-backup-v16", "migration-active-run-v16",
                        "migration-profile-v16", "migration-preview-v16", 123L));
        versionSixteen.insertOrThrow(DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME, null,
                createV16MigrationBackupLocation("migration-backup-v16", 123L));
        versionSixteen.setVersion(16);
        versionSixteen.close();

        DatabaseHandler handler = new DatabaseHandler(testContext);
        SQLiteDatabase upgraded = handler.getWritableDatabase();
        try {
            assertEquals(DatabaseInfo.DATABASE_VERSION, upgraded.getVersion());
            assertRunFilterSnapshotColumnExists(upgraded);
            assertMigrationColumnExists(upgraded, DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME,
                    DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_OBSERVATION_FINGERPRINT);
            assertMigrationTableExists(upgraded, DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME);
            assertMigrationTableExists(upgraded, DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME);
            assertMigrationIndexExists(upgraded, "bisync_backup_one_unresolved_profile");
            assertMigrationIndexExists(upgraded, "bisync_backup_history");
            assertMigrationIndexDefinition(upgraded, "bisync_backup_one_unresolved_profile",
                    "CREATE UNIQUE INDEX", "ON " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME
                            + "(" + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID + ")",
                    "WHERE " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS
                            + " IN ('PENDING_VALIDATION','BACKUP_VERIFIED','MUTATION_IN_PROGRESS',"
                            + "'RECOVERY_REQUIRED','RESTORE_REQUIRED','RESTORE_IN_PROGRESS','INVALIDATED')");
            assertMigrationIndexDefinition(upgraded, "bisync_backup_history",
                    "CREATE INDEX", "ON " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME + "("
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID + ","
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT + " DESC)");
            assertNoMigrationForeignKeyViolations(upgraded);
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OPERATION_ID + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_RUN_ID + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_REVISION + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_ENGINE_REF + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATE_VERSION + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OBSERVATION_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_ID + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_CHECKED_AT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_INITIALIZATION_MODE + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_FILTER_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_COMPARISON_MODE + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_PERCENT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_COUNT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_USER_CONFIRMED_AT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_TOKEN + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_GENERATION + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_UPDATED_AT + " FROM "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME + " WHERE "
                            + DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OPERATION_ID + " = ?",
                    new String[]{"migration-backup-v16"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("migration-backup-v16", cursor.getString(0));
                assertEquals("migration-active-run-v16", cursor.getString(1));
                assertEquals("migration-profile-v16", cursor.getString(2));
                assertEquals(1L, cursor.getLong(3));
                assertEquals(repeat('a', 64), cursor.getString(4));
                assertEquals("rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0", cursor.getString(5));
                assertEquals(1, cursor.getInt(6));
                assertEquals(repeat('b', 64), cursor.getString(7));
                assertEquals(repeat('c', 64), cursor.getString(8));
                assertEquals("migration-preview-v16", cursor.getString(9));
                assertEquals(repeat('d', 64), cursor.getString(10));
                assertEquals(123L, cursor.getLong(11));
                assertEquals("path1", cursor.getString(12));
                assertEquals(repeat('e', 64), cursor.getString(13));
                assertEquals("SIZE_AND_MODTIME", cursor.getString(14));
                assertEquals(25, cursor.getInt(15));
                assertEquals(100, cursor.getInt(16));
                assertEquals(123L, cursor.getLong(17));
                assertEquals("migration-backup-owner", cursor.getString(18));
                assertEquals(1L, cursor.getLong(19));
                assertEquals("BACKUP_VERIFIED", cursor.getString(20));
                assertEquals(123L, cursor.getLong(21));
                assertEquals(123L, cursor.getLong(22));
            }
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_OPERATION_ID + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_SIDE + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ACCOUNT_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ENDPOINT_SCOPE_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_BACKUP_SCOPE_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR_FINGERPRINT + ", "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_CREATED_AT + " FROM "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME + " WHERE "
                            + DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_OPERATION_ID + " = ?",
                    new String[]{"migration-backup-v16"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("migration-backup-v16", cursor.getString(0));
                assertEquals("LEFT", cursor.getString(1));
                assertEquals(repeat('f', 64), cursor.getString(2));
                assertEquals(repeat('1', 64), cursor.getString(3));
                assertEquals(repeat('2', 64), cursor.getString(4));
                assertEquals("disposable/v16-backup", cursor.getString(5));
                assertEquals(repeat('3', 64), cursor.getString(6));
                assertEquals(123L, cursor.getLong(7));
            }
            try (android.database.Cursor cursor = upgraded.rawQuery(
                    "SELECT " + DatabaseInfo.RUN_COLUMN_PROFILE_ID + ", "
                            + DatabaseInfo.RUN_COLUMN_STATE + ", "
                            + DatabaseInfo.RUN_COLUMN_OWNER_TOKEN + ", "
                            + DatabaseInfo.RUN_COLUMN_REQUESTED_AT + ", "
                            + DatabaseInfo.RUN_COLUMN_FILTER_SNAPSHOT + " FROM "
                            + DatabaseInfo.RUN_TABLE_NAME + " WHERE "
                            + DatabaseInfo.RUN_COLUMN_ID + " = ?",
                    new String[]{"migration-active-run-v16"})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("migration-profile-v16", cursor.getString(0));
                assertEquals("RUNNING", cursor.getString(1));
                assertEquals("migration-owner-migration-active-run-v16", cursor.getString(2));
                assertEquals(123L, cursor.getLong(3));
                assertTrue(cursor.isNull(4));
            }
            assertEquals(1L, DatabaseUtils.longForQuery(upgraded,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='run_one_active_profile'", null));
            try {
                upgraded.insertOrThrow(DatabaseInfo.RUN_TABLE_NAME, null,
                        createMigrationRun("migration-duplicate-active-run-v16",
                                "migration-profile-v16", 124L));
                fail("migration must preserve the unique active-run owner index");
            } catch (SQLiteConstraintException expected) {
                // The existing RUNNING row still owns the profile after upgrade.
            }
        } finally {
            upgraded.close();
            handler.close();
        }
    }

    @Test
    public void nativeRecoveryEvidenceRoundTripsButCannotAuthorizeOtherStates() {
        DatabaseHandler handler = new DatabaseHandler(testContext);
        Task task = new Task(0);
        task.setTitle("native recovery evidence persistence");
        task.setDirection(SyncDirectionObject.SYNC_BIDIRECTIONAL_INITIAL);
        task.setRemoteId("recovery-evidence-remote");
        task.setRemotePath("root");
        task.setLocalPath("/storage/emulated/0/recovery-evidence");
        Task stored = handler.createTask(task, false);
        handler.close();

        ProfileRecord profile = new ProfileRepository(testContext).getForLegacyTask(stored.getId());
        assertTrue(profile != null);
        String engineRef = "rclone:" + ca.pkay.rcloneexplorer.BuildConfig.RCLONE_ENGINE_VERSION + "@"
                + ca.pkay.rcloneexplorer.BuildConfig.RCLONE_ENGINE_REF;
        BisyncPreflightRepository preflights = new BisyncPreflightRepository(testContext);
        BisyncPreflightInput interrupted = new BisyncPreflightInput(
                profile.getRevision(), profile.getFingerprint(), engineRef,
                BisyncPreflightPolicy.CURRENT_STATE_VERSION,
                new BisyncEndpointEvidence(null, BisyncEndpointScope.Companion.unknown(), null),
                new BisyncEndpointEvidence(null, BisyncEndpointScope.Companion.unknown(), null),
                new BisyncListingEvidence(false, false, 0, null),
                new BisyncListingEvidence(false, false, 0, null),
                null, false, BisyncComparisonMode.SIZE_AND_MODTIME,
                BisyncNativeState.INTERRUPTED, null,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT,
                "CURRENT_LISTINGS_PARTIAL", true);
        BisyncPreflightResult interruptedResult = new BisyncPreflightResult(
                ProfileReadiness.BLOCKED, BisyncPreflightReason.NATIVE_STATE_INTERRUPTED, null, null, null);
        preflights.recordAttempt(profile.getProfileId(), profile.getRevision(), profile.getFingerprint(),
                interrupted, interruptedResult, 124L);

        BisyncPreflightStatus status = preflights.get(profile.getProfileId());
        assertTrue(status != null);
        assertEquals(BisyncNativeState.INTERRUPTED, status.getNativeState());
        assertEquals("CURRENT_LISTINGS_PARTIAL", status.getNativeStateReason());
        assertTrue(status.getRecoveryListingsValid());

        BisyncPreflightInput nonInterrupted = new BisyncPreflightInput(
                profile.getRevision(), profile.getFingerprint(), engineRef,
                BisyncPreflightPolicy.CURRENT_STATE_VERSION,
                new BisyncEndpointEvidence(null, BisyncEndpointScope.Companion.unknown(), null),
                new BisyncEndpointEvidence(null, BisyncEndpointScope.Companion.unknown(), null),
                new BisyncListingEvidence(false, false, 0, null),
                new BisyncListingEvidence(false, false, 0, null),
                null, false, BisyncComparisonMode.SIZE_AND_MODTIME,
                BisyncNativeState.UNKNOWN, null,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT,
                "NATIVE_RUN_ACTIVE", true);
        BisyncPreflightResult unknownResult = new BisyncPreflightResult(
                ProfileReadiness.BLOCKED, BisyncPreflightReason.NATIVE_STATE_UNKNOWN, null, null, null);
        preflights.recordAttempt(profile.getProfileId(), profile.getRevision(), profile.getFingerprint(),
                nonInterrupted, unknownResult, 125L);

        status = preflights.get(profile.getProfileId());
        assertTrue(status != null);
        assertEquals(BisyncNativeState.UNKNOWN, status.getNativeState());
        assertEquals("NATIVE_RUN_ACTIVE", status.getNativeStateReason());
        assertEquals(false, status.getRecoveryListingsValid());
    }

    @Test
    public void confirmedReinitializationRetainsAcceptedBaselineForRollbackReview() {
        DatabaseHandler handler = new DatabaseHandler(testContext);
        Task task = new Task(0);
        task.setTitle("reinitialization preserves old baseline");
        task.setDirection(SyncDirectionObject.SYNC_BIDIRECTIONAL);
        task.setRemoteId("reinit-preservation-remote");
        task.setRemotePath("root");
        task.setLocalPath("/storage/emulated/0/reinit-preservation");
        Task stored = handler.createTask(task, false);
        handler.close();

        ProfileRecord profile = new ProfileRepository(testContext).getForLegacyTask(stored.getId());
        assertTrue(profile != null);
        String leftAccount = repeat('a', 64);
        String rightAccount = repeat('b', 64);
        String engineRef = "rclone:" + ca.pkay.rcloneexplorer.BuildConfig.RCLONE_ENGINE_VERSION + "@"
                + ca.pkay.rcloneexplorer.BuildConfig.RCLONE_ENGINE_REF;
        String filterFingerprint = LegacyProfileMapper.INSTANCE.fingerprintNoFilter();
        BisyncEndpointEvidence left = new BisyncEndpointEvidence(
                leftAccount, BisyncEndpointScope.Companion.from(leftAccount, "left"), true);
        BisyncEndpointEvidence right = new BisyncEndpointEvidence(
                rightAccount, BisyncEndpointScope.Companion.from(rightAccount, "right"), true);
        BisyncListingEvidence completeEmptyListing = new BisyncListingEvidence(true, true, 0, null);

        BisyncPreflightInput absent = new BisyncPreflightInput(
                profile.getRevision(), profile.getFingerprint(), engineRef,
                BisyncPreflightPolicy.CURRENT_STATE_VERSION, left, right,
                completeEmptyListing, completeEmptyListing, filterFingerprint, true,
                BisyncComparisonMode.SIZE_AND_MODTIME, BisyncNativeState.ABSENT, null,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT);
        BisyncPreflightResult initialization = BisyncPreflightPolicy.INSTANCE.evaluate(absent);
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, initialization.getReadiness());
        assertTrue(initialization.getCandidateBaseline() != null);

        BisyncPreflightInput compatible = new BisyncPreflightInput(
                profile.getRevision(), profile.getFingerprint(), engineRef,
                BisyncPreflightPolicy.CURRENT_STATE_VERSION, left, right,
                completeEmptyListing, completeEmptyListing, filterFingerprint, true,
                BisyncComparisonMode.SIZE_AND_MODTIME, BisyncNativeState.COMPATIBLE,
                initialization.getCandidateBaseline(),
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
                BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT);
        BisyncPreflightResult ready = BisyncPreflightPolicy.INSTANCE.evaluate(compatible);
        assertEquals(ProfileReadiness.READY, ready.getReadiness());

        BisyncPreflightRepository repository = new BisyncPreflightRepository(testContext);
        repository.recordAttempt(profile.getProfileId(), profile.getRevision(), profile.getFingerprint(),
                compatible, ready, 126L);
        String acceptedFingerprint = repository.get(profile.getProfileId())
                .getAcceptedBaseline().getPreflightFingerprint();

        try {
            repository.resetForConfirmedReinitialization(
                    profile.getProfileId(), profile.getRevision(), profile.getFingerprint(), false);
            fail("reinitialization must require explicit confirmation");
        } catch (SecurityException expected) {
            // Expected: no state may be changed without explicit confirmation.
        }
        assertEquals(acceptedFingerprint, repository.get(profile.getProfileId())
                .getAcceptedBaseline().getPreflightFingerprint());

        repository.resetForConfirmedReinitialization(
                profile.getProfileId(), profile.getRevision(), profile.getFingerprint(), true);

        BisyncPreflightStatus afterRequest = repository.get(profile.getProfileId());
        assertTrue(afterRequest != null);
        assertEquals(ProfileReadiness.INITIALIZATION_REQUIRED, afterRequest.getReadiness());
        assertEquals("EXPLICIT_REINITIALIZATION_REQUIRES_PRESERVATION", afterRequest.getReasonCode());
        assertTrue(afterRequest.getAcceptedBaseline() != null);
        assertEquals(acceptedFingerprint, afterRequest.getAcceptedBaseline().getPreflightFingerprint());
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    /**
     * The v13-v15 preflight schema is the v12 base table plus the three columns added before
     * version 13. The observation fingerprint is a v16 migration and must stay absent here.
     */
    private static void createPreV17RunsTable(SQLiteDatabase database) {
        String currentSchema = DatabaseInfo.Companion.getSQL_CREATE_TABLE_RUNS();
        String filterSnapshotColumn = DatabaseInfo.RUN_COLUMN_FILTER_SNAPSHOT + " TEXT,";
        if (!currentSchema.contains(filterSnapshotColumn)) {
            throw new AssertionError("current run schema no longer contains the v17 column to omit");
        }
        String createRuns = currentSchema.replace(filterSnapshotColumn, "");
        database.execSQL(createRuns);
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_ACTIVE_RUN());
    }

    private static void insertMigrationProfile(SQLiteDatabase database, String profileId) {
        ContentValues profile = new ContentValues();
        profile.put(DatabaseInfo.PROFILE_COLUMN_ID, profileId);
        profile.put(DatabaseInfo.PROFILE_COLUMN_REVISION, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_TITLE, "Migration profile");
        profile.put(DatabaseInfo.PROFILE_COLUMN_MODE, "BISYNC");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENDPOINT, "endpoint-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_SETTINGS, "settings-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_FINGERPRINT, "profile-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENGINE,
                "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        profile.put(DatabaseInfo.PROFILE_COLUMN_READINESS, "BLOCKED");
        profile.put(DatabaseInfo.PROFILE_COLUMN_CREATED_AT, 1L);
        profile.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, 1L);
        database.insertOrThrow(DatabaseInfo.PROFILE_TABLE_NAME, null, profile);
    }

    private static ContentValues createMigrationRun(String runId, String profileId, long requestedAt) {
        return createMigrationRun(runId, profileId, requestedAt, 1L, "profile-fingerprint");
    }

    private static ContentValues createMigrationRun(
            String runId,
            String profileId,
            long requestedAt,
            long profileRevision,
            String profileFingerprint
    ) {
        ContentValues run = new ContentValues();
        run.put(DatabaseInfo.RUN_COLUMN_ID, runId);
        run.put(DatabaseInfo.RUN_COLUMN_PROFILE_ID, profileId);
        run.put(DatabaseInfo.RUN_COLUMN_PROFILE_REVISION, profileRevision);
        run.put(DatabaseInfo.RUN_COLUMN_PROFILE_FINGERPRINT, profileFingerprint);
        run.put(DatabaseInfo.RUN_COLUMN_REQUESTED_MODE, "BISYNC");
        run.put(DatabaseInfo.RUN_COLUMN_ENDPOINT, "endpoint-fingerprint");
        run.put(DatabaseInfo.RUN_COLUMN_SETTINGS, "settings-fingerprint");
        run.put(DatabaseInfo.RUN_COLUMN_ENGINE,
                "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        run.put(DatabaseInfo.RUN_COLUMN_STATE, "RUNNING");
        run.putNull(DatabaseInfo.RUN_COLUMN_REASON);
        run.put(DatabaseInfo.RUN_COLUMN_REQUESTED_AT, requestedAt);
        run.put(DatabaseInfo.RUN_COLUMN_STARTED_AT, requestedAt);
        run.put(DatabaseInfo.RUN_COLUMN_OWNER_TOKEN, "migration-owner-" + runId);
        run.put(DatabaseInfo.RUN_COLUMN_OWNER_GENERATION, 7L);
        run.put(DatabaseInfo.RUN_COLUMN_CANCEL_REQUESTED, 0);
        run.put(DatabaseInfo.RUN_COLUMN_CREATED_AT, requestedAt);
        run.put(DatabaseInfo.RUN_COLUMN_UPDATED_AT, requestedAt);
        return run;
    }

    private static void assertMigrationRunRetained(
            SQLiteDatabase database,
            String runId,
            String profileId,
            long requestedAt
    ) {
        try (android.database.Cursor cursor = database.rawQuery(
                "SELECT " + DatabaseInfo.RUN_COLUMN_PROFILE_ID + ", "
                        + DatabaseInfo.RUN_COLUMN_STATE + ", "
                        + DatabaseInfo.RUN_COLUMN_OWNER_TOKEN + ", "
                        + DatabaseInfo.RUN_COLUMN_OWNER_GENERATION + ", "
                        + DatabaseInfo.RUN_COLUMN_REQUESTED_AT + ", "
                        + DatabaseInfo.RUN_COLUMN_FILTER_SNAPSHOT + " FROM "
                        + DatabaseInfo.RUN_TABLE_NAME + " WHERE "
                        + DatabaseInfo.RUN_COLUMN_ID + " = ?",
                new String[]{runId})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(profileId, cursor.getString(0));
            assertEquals("RUNNING", cursor.getString(1));
            assertEquals("migration-owner-" + runId, cursor.getString(2));
            assertEquals(7L, cursor.getLong(3));
            assertEquals(requestedAt, cursor.getLong(4));
            assertTrue(cursor.isNull(5));
        }
    }

    private static void assertRunFilterSnapshotColumnExists(SQLiteDatabase database) {
        assertMigrationColumnExists(database, DatabaseInfo.RUN_TABLE_NAME,
                DatabaseInfo.RUN_COLUMN_FILTER_SNAPSHOT);
    }

    private static void assertMigrationColumnExists(
            SQLiteDatabase database,
            String tableName,
            String columnName
    ) {
        boolean foundColumn = false;
        try (android.database.Cursor cursor =
                     database.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            while (cursor.moveToNext()) {
                if (columnName.equals(cursor.getString(1))) {
                    foundColumn = true;
                    break;
                }
            }
        }
        assertTrue(foundColumn);
    }

    private static void assertMigrationTableExists(SQLiteDatabase database, String tableName) {
        assertEquals(1L, DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName}));
    }

    private static void assertMigrationIndexExists(SQLiteDatabase database, String indexName) {
        assertEquals(1L, DatabaseUtils.longForQuery(database,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
                new String[]{indexName}));
    }

    private static void assertMigrationIndexDefinition(
            SQLiteDatabase database,
            String indexName,
            String... expectedFragments
    ) {
        String sql = DatabaseUtils.stringForQuery(database,
                "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
                new String[]{indexName});
        assertTrue(sql != null);
        String normalizedSql = sql.replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        for (String expectedFragment : expectedFragments) {
            String normalizedFragment = expectedFragment.replaceAll("\\s+", " ")
                    .toUpperCase(java.util.Locale.ROOT);
            assertTrue("missing SQL fragment in index " + indexName + ": " + expectedFragment,
                    normalizedSql.contains(normalizedFragment));
        }
    }

    private static void assertNoMigrationForeignKeyViolations(SQLiteDatabase database) {
        try (android.database.Cursor cursor = database.rawQuery("PRAGMA foreign_key_check", null)) {
            assertEquals(0, cursor.getCount());
        }
    }

    private static void createV15PreviewSchema(SQLiteDatabase database) {
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_BISYNC_PREVIEWS());
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW());
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY());
    }

    /** Exact v14 preview DDL, before commit 7daef06 added initialization_mode in schema v15. */
    private static void createV14PreviewSchema(SQLiteDatabase database) {
        String table = DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME;
        String schema = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID + " TEXT PRIMARY KEY NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_ID + " TEXT NOT NULL REFERENCES "
                + DatabaseInfo.PROFILE_TABLE_NAME + "(" + DatabaseInfo.PROFILE_COLUMN_ID + ") ON DELETE CASCADE,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION + " INTEGER NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION + " > 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ENGINE_REF + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATE_VERSION + " INTEGER NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATE_VERSION + " > 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_SCOPE + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_FILTER + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPARISON + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT + " INTEGER NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT + " BETWEEN 1 AND 100),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT + " INTEGER NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT + " > 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE + " TEXT NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE + " IN ('ABSENT','COMPATIBLE')),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE + " TEXT,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + " TEXT NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS
                + " IN ('QUEUED','RUNNING','COMPLETE','INCOMPLETE','UNAVAILABLE','CANCELLED','STALE','INTERRUPTED','RECOVERY_REQUIRED')),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_TOKEN + " TEXT NOT NULL,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION + " INTEGER NOT NULL DEFAULT 0 CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION + " >= 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT + " INTEGER NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT + " > 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STARTED_AT + " INTEGER,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " INTEGER,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE + " TEXT,"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS + " TEXT CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS + " IN ('COMPLETE','INCOMPLETE')),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS + " INTEGER CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS + " >= 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES + " INTEGER CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES + " >= 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES + " INTEGER CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES + " >= 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES + " INTEGER CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES + " >= 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT + " INTEGER CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT + " >= 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN + " INTEGER NOT NULL DEFAULT 0 CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN + " = 0),"
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_UPDATED_AT + " INTEGER NOT NULL CHECK ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_UPDATED_AT + " > 0),"
                + "CHECK ((" + DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE + " = 'ABSENT' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE + " IS NULL) OR ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE + " = 'COMPATIBLE' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE + " IS NOT NULL)),"
                + "CHECK ((" + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + " = 'COMPLETE' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS + " = 'COMPLETE' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT + " = 0 AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " IS NOT NULL) OR ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + " = 'INCOMPLETE' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS + " = 'INCOMPLETE' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " IS NOT NULL) OR ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + " = 'UNAVAILABLE' AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS + " IS NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE + " IS NOT NULL AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " IS NOT NULL) OR ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS + " IN ('STALE','CANCELLED') AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " IS NOT NULL) OR ("
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS
                + " IN ('QUEUED','RUNNING','INTERRUPTED','RECOVERY_REQUIRED') AND "
                + DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT + " IS NULL)))";
        database.execSQL(schema);
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_ACTIVE_BISYNC_PREVIEW());
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_BISYNC_PREVIEW_HISTORY());
    }

    private static ContentValues createV14MigrationPreview(
            String previewId,
            String profileId,
            String status,
            long requestedAt,
            Long startedAt,
            long updatedAt
    ) {
        ContentValues preview = new ContentValues();
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID, previewId);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_ID, profileId);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION, 1L);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT, "profile-fingerprint");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ENGINE_REF,
                "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATE_VERSION, 1);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT, repeat('a', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_SCOPE, repeat('b', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT, repeat('c', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE, repeat('d', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_FILTER, "");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPARISON, "SIZE_AND_MODTIME");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT, 25);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT, 100);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE, "ABSENT");
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT, "legacy-identity");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS, status);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_TOKEN,
                "migration-preview-owner-" + previewId);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, 1L);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT, requestedAt);
        if (startedAt == null) {
            preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STARTED_AT);
        } else {
            preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STARTED_AT, startedAt);
        }
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN, 0);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_UPDATED_AT, updatedAt);
        return preview;
    }

    private static ContentValues createV16MigrationPreview(
            String previewId,
            String profileId,
            long timestamp
    ) {
        ContentValues preview = new ContentValues();
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID, previewId);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_ID, profileId);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION, 1L);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT, repeat('a', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ENGINE_REF,
                "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATE_VERSION, 1);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT, repeat('b', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_SCOPE, repeat('c', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT, repeat('d', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE, repeat('e', 64));
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_FILTER, "");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPARISON, "SIZE_AND_MODTIME");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT, 25);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT, 100);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE, "COMPATIBLE");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE, "accepted-baseline");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT, "legacy-identity");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS, "STALE");
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_TOKEN,
                "migration-preview-owner-" + previewId);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, 2L);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT, timestamp);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_STARTED_AT, timestamp);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT, timestamp);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_FAILURE_CODE, "MIGRATION_FIXTURE");
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES);
        preview.putNull(DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN, 0);
        preview.put(DatabaseInfo.BISYNC_PREVIEW_COLUMN_UPDATED_AT, timestamp);
        return preview;
    }

    private static ContentValues createV16MigrationBackupManifest(
            String operationId,
            String runId,
            String profileId,
            String previewId,
            long timestamp
    ) {
        ContentValues manifest = new ContentValues();
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OPERATION_ID, operationId);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_RUN_ID, runId);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID, profileId);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_REVISION, 1L);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_FINGERPRINT, repeat('a', 64));
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_ENGINE_REF,
                "rclone:1.76.0@fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0");
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATE_VERSION, 1);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_FINGERPRINT, repeat('b', 64));
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OBSERVATION_FINGERPRINT, repeat('c', 64));
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_ID, previewId);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_FINGERPRINT, repeat('d', 64));
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_CHECKED_AT, timestamp);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_INITIALIZATION_MODE, "path1");
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_FILTER_FINGERPRINT, repeat('e', 64));
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_COMPARISON_MODE, "SIZE_AND_MODTIME");
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_PERCENT, 25);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_COUNT, 100);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_USER_CONFIRMED_AT, timestamp);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_TOKEN, "migration-backup-owner");
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_GENERATION, 1L);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS, "BACKUP_VERIFIED");
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT, timestamp);
        manifest.put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_UPDATED_AT, timestamp);
        return manifest;
    }

    private static ContentValues createV16MigrationBackupLocation(String operationId, long timestamp) {
        ContentValues location = new ContentValues();
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_OPERATION_ID, operationId);
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_SIDE, "LEFT");
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ACCOUNT_FINGERPRINT, repeat('f', 64));
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ENDPOINT_SCOPE_FINGERPRINT, repeat('1', 64));
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_BACKUP_SCOPE_FINGERPRINT, repeat('2', 64));
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR, "disposable/v16-backup");
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR_FINGERPRINT, repeat('3', 64));
        location.put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_CREATED_AT, timestamp);
        return location;
    }

    private static void createV16BackupTables(SQLiteDatabase database) {
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_BISYNC_BACKUP_MANIFESTS());
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_BISYNC_BACKUP_LOCATIONS());
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_ACTIVE_BISYNC_BACKUP_PROFILE());
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_INDEX_BISYNC_BACKUP_HISTORY());
    }

    private static void createPreV16PreflightTable(SQLiteDatabase database) {
        database.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_BISYNC_PREFLIGHT());
        database.execSQL(DatabaseInfo.Companion.getSQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE());
        database.execSQL(DatabaseInfo.Companion.getSQL_UPDATE_BISYNC_PREFLIGHT_ADD_NATIVE_STATE_REASON());
        database.execSQL(DatabaseInfo.Companion.getSQL_UPDATE_BISYNC_PREFLIGHT_ADD_RECOVERY_LISTINGS_VALID());
    }
}
