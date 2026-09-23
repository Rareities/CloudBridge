package ca.pkay.rcloneexplorer.Database;

import android.content.Context;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.database.DatabaseUtils;
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
    public void versionTenDatabaseUpgradesWithoutDroppingExistingRows() {
        SQLiteDatabase versionTen = SQLiteDatabase.openOrCreateDatabase(
                testContext.getDatabasePath(DatabaseInfo.DATABASE_NAME), null);
        versionTen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_PROFILES());
        versionTen.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_RUNS());
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
        versionTwelve.execSQL(DatabaseInfo.Companion.getSQL_CREATE_TABLE_BISYNC_PREFLIGHT());

        ContentValues profile = new ContentValues();
        profile.put(DatabaseInfo.PROFILE_COLUMN_ID, "migration-profile-v12");
        profile.put(DatabaseInfo.PROFILE_COLUMN_REVISION, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_TITLE, "Migration profile");
        profile.put(DatabaseInfo.PROFILE_COLUMN_MODE, "BISYNC");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENDPOINT, "endpoint-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_SETTINGS, "settings-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_FINGERPRINT, "profile-fingerprint");
        profile.put(DatabaseInfo.PROFILE_COLUMN_ENGINE, "rclone:1.76.0@d53551e1722305268c6072263f11066f1278a4a0");
        profile.put(DatabaseInfo.PROFILE_COLUMN_READINESS, "BLOCKED");
        profile.put(DatabaseInfo.PROFILE_COLUMN_CREATED_AT, 1);
        profile.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, 1);
        versionTwelve.insertOrThrow(DatabaseInfo.PROFILE_TABLE_NAME, null, profile);

        ContentValues preflight = new ContentValues();
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID, "migration-profile-v12");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION, 1);
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT, "profile-fingerprint");
        preflight.put(DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_ENGINE_REF, "rclone:1.76.0@d53551e1722305268c6072263f11066f1278a4a0");
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
            assertEquals(13, upgraded.getVersion());
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
}
