package ca.pkay.rcloneexplorer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class ConfigRestoreJournalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void completedCleanupRemovesJournal() throws IOException {
        File directory = temporary.newFolder("journal");
        assertTrue(new File(directory, "prepared").createNewFile());
        assertTrue(new File(directory, "committed").createNewFile());
        assertTrue(new File(directory, "config").createNewFile());
        ConfigRestoreJournal.clear(directory, ignored -> {});
        assertFalse(directory.exists());
    }

    @Test public void directorySyncFailurePreservesSnapshotsAndCommitDecision() throws IOException {
        File directory = temporary.newFolder("journal");
        File prepared = new File(directory, "prepared");
        File committed = new File(directory, "committed");
        File snapshot = new File(directory, "config");
        assertTrue(prepared.createNewFile());
        assertTrue(committed.createNewFile());
        assertTrue(snapshot.createNewFile());
        try {
            ConfigRestoreJournal.clear(directory, ignored -> { throw new IOException("Injected fsync failure"); });
            fail("Expected durability failure");
        } catch (IOException expected) {
            assertFalse(prepared.exists());
            assertTrue("No snapshot deletion before durable retirement", snapshot.exists());
            assertTrue(committed.exists());
        }
    }

    @Test public void partialCleanupRetiresRollbackBeforeDeletingSnapshots() throws IOException {
        File directory = temporary.newFolder("journal");
        File prepared = new File(directory, "prepared");
        File committed = new File(directory, "committed");
        assertTrue(prepared.createNewFile());
        assertTrue(committed.createNewFile());
        // A nonempty unexpected entry makes snapshot cleanup fail deterministically.
        File snapshot = new File(directory, "config");
        assertTrue(snapshot.mkdir());
        assertTrue(new File(snapshot, "keep").createNewFile());
        try {
            ConfigRestoreJournal.clear(directory, ignored -> {});
            fail("Expected snapshot cleanup failure");
        } catch (IOException expected) {
            assertFalse("Partial snapshots must never remain eligible for rollback", prepared.exists());
            assertTrue("Commit decision remains until every snapshot is removed", committed.exists());
        }
    }
}
