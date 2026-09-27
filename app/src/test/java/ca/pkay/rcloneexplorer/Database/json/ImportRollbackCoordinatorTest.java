package ca.pkay.rcloneexplorer.Database.json;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ImportRollbackCoordinatorTest {

    @Test
    public void laterStoresAreRestoredAfterAnEarlierRestoreFails() {
        List<String> attempted = new ArrayList<>();
        IOException databaseFailure = new IOException("database restore failed");
        IllegalStateException preferenceFailure =
                new IllegalStateException("preference restore failed");

        Exception result = ImportRollbackCoordinator.restoreAll(
                () -> {
                    attempted.add("database");
                    throw databaseFailure;
                },
                () -> {
                    attempted.add("preferences");
                    throw preferenceFailure;
                },
                () -> attempted.add("config"));

        assertEquals(Arrays.asList("database", "preferences", "config"), attempted);
        assertSame(databaseFailure, result);
        assertEquals(1, result.getSuppressed().length);
        assertSame(preferenceFailure, result.getSuppressed()[0]);
    }

    @Test
    public void successfulRestoresRunInOrderWithoutReturningFailure() {
        List<String> attempted = new ArrayList<>();

        Exception result = ImportRollbackCoordinator.restoreAll(
                () -> attempted.add("database"),
                () -> attempted.add("preferences"),
                () -> attempted.add("config"));

        assertEquals(Arrays.asList("database", "preferences", "config"), attempted);
        assertNull(result);
    }

    @Test
    public void configSnapshotRequiresBothConfigAndSecretRestoreToSucceed() {
        assertEquals(true, ImportRollbackCoordinator.canDiscardConfigSnapshot(true,
                true, false, false, false));
        assertEquals(true, ImportRollbackCoordinator.canDiscardConfigSnapshot(false,
                true, true, true, true));
        assertEquals(true, ImportRollbackCoordinator.canDiscardConfigSnapshot(false,
                false, false, false, false));
        assertEquals(false, ImportRollbackCoordinator.canDiscardConfigSnapshot(false,
                true, true, true, false));
        assertEquals(false, ImportRollbackCoordinator.canDiscardConfigSnapshot(false,
                true, true, false, true));
        assertEquals(false, ImportRollbackCoordinator.canDiscardConfigSnapshot(false,
                true, true, false, false));
        assertEquals(false, ImportRollbackCoordinator.canDiscardConfigSnapshot(false,
                true, false, false, false));
    }
}
