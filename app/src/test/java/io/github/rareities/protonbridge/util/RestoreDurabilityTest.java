package io.github.rareities.protonbridge.util;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import io.github.rareities.protonbridge.Database.json.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class RestoreDurabilityTest {
    private DurableFiles.DirectorySync original;
    @Before public void setup() { original = DurableFiles.directorySync; }
    @After public void restore() { DurableFiles.directorySync = original; }
    private File journal(Application context) throws Exception {
        File journal = new File(context.getFilesDir(), "restore-journal"); journal.mkdirs();
        JSONObject snapshot = new JSONObject().put("version",1).put("hadConfig",false).put("hadPassword",false)
                .put("database",new JSONObject(Exporter.create(context)))
                .put("preferences",new JSONObject(SharedPreferencesBackup.export(context)));
        Files.write(new File(journal,"previous.json").toPath(),snapshot.toString().getBytes("UTF-8"));
        Files.write(new File(journal,"pending").toPath(),new byte[]{1});
        return journal;
    }
    @Test public void failedInstallBarrierRetainsPendingAndRollbackSnapshot() throws Exception {
        Application context = ApplicationProvider.getApplicationContext(); File journal=journal(context);
        DurableFiles.directorySync = directory -> { throw new IOException("power-loss barrier"); };
        assertThrows(IOException.class, () -> BackupRestore.recoverIfNeeded(context));
        assertTrue(new File(journal,"pending").exists());
        assertTrue(new File(journal,"previous.json").exists());
        DurableFiles.directorySync = directory -> {};
        BackupRestore.recoverIfNeeded(context);
        assertFalse(new File(journal,"pending").exists());
        assertFalse(new File(journal,"previous.json").exists());
    }
    @Test public void failedMarkerRetirementNeverRemovesRollbackSnapshots() throws Exception {
        Application context = ApplicationProvider.getApplicationContext(); File journal=journal(context);
        DurableFiles.directorySync = directory -> {
            if (directory.equals(journal) && !new File(journal,"pending").exists())
                throw new IOException("pending unlink not durable");
        };
        assertThrows(IOException.class, () -> BackupRestore.recoverIfNeeded(context));
        assertTrue(new File(journal,"previous.json").exists());
        assertTrue("uncertain retirement must block native admission", new File(journal,"pending").exists());
        // Model the marker reappearing after power loss; rollback still has its snapshot.
        Files.write(new File(journal,"pending").toPath(),new byte[]{1});
        DurableFiles.directorySync = directory -> {};
        BackupRestore.recoverIfNeeded(context);
        assertFalse(new File(journal,"pending").exists());
    }
    @Test public void committedRollbackCleanupFailureDoesNotReportFailure() throws Exception {
        Application context = ApplicationProvider.getApplicationContext(); File journal=journal(context);
        DurableFiles.directorySync = directory -> {
            if (directory.equals(journal) && !new File(journal,"pending").exists() &&
                    !new File(journal,"previous.json").exists())
                throw new IOException("obsolete journal cleanup barrier failed");
        };
        BackupRestore.recoverIfNeeded(context);
        assertFalse(new File(journal,"pending").exists());
    }
    @Test public void doubleMarkerFailureBlocksNativePreparationUntilForcedRecovery() throws Exception {
        Application context=ApplicationProvider.getApplicationContext(); File journal=journal(context);
        DurableFiles.directorySync=directory -> {
            if (directory.equals(journal)) {
                new File(journal,"pending").delete();
                throw new IOException("journal remains unavailable");
            }
        };
        assertThrows(IOException.class, () -> BackupRestore.recoverIfNeeded(context));
        assertFalse(new File(journal,"pending").exists());
        assertTrue(ConfigurationExclusion.isRestoreRecoveryRequired());
        assertThrows(IOException.class, ConfigurationExclusion::prepare);
        assertFalse(ConfigurationExclusion.LOCK.isWriteLockedByCurrentThread());
        DurableFiles.directorySync=directory -> {};
        BackupRestore.recoverIfNeeded(context);
        assertFalse(ConfigurationExclusion.isRestoreRecoveryRequired());
        try (ConfigurationExclusion.Preparation ready=ConfigurationExclusion.prepare()) { }
    }
}
