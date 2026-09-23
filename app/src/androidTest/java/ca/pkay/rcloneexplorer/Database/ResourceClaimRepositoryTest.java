package ca.pkay.rcloneexplorer.Database;

import android.content.Context;
import android.app.Instrumentation;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;

import ca.pkay.rcloneexplorer.util.EndpointResource;
import ca.pkay.rcloneexplorer.util.EndpointConflictCoordinator;

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
                assertEquals(11, upgraded.getVersion());
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
            } finally {
                upgraded.close();
            }
        } finally {
            lease.close();
        }
    }
}
