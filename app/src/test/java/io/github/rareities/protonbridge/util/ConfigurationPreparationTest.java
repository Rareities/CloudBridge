package io.github.rareities.protonbridge.util;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ConfigurationPreparationTest {
    @Test(timeout=5000) public void editsAndRcAdmissionCannotInterleaveValidationAndSpawn() throws Exception {
        ExecutorService editor = Executors.newSingleThreadExecutor();
        try (ConfigurationExclusion.Preparation preparation = ConfigurationExclusion.prepare()) {
            assertFalse(editor.submit(() -> {
                if (!ConfigurationExclusion.LOCK.writeLock().tryLock()) return false;
                try { return true; } finally { ConfigurationExclusion.LOCK.writeLock().unlock(); }
            }).get());
            assertTrue(editor.submit(() -> {
                try { NativeSessionGate.GLOBAL.acquireRc(NativeSessionGate.GLOBAL.generation()).close(); return false; }
                catch (IOException excluded) { return true; }
            }).get());
            NativeSessionGate.Lease child = NativeSessionGate.GLOBAL.acquireCommand();
            preparation.close();
            // Thread-owned preparation has ended, but native ownership still excludes edits/jobs.
            assertThrows(IOException.class, ConfigurationExclusion::prepare);
            assertThrows(IOException.class, () -> NativeSessionGate.GLOBAL.acquireCommand());
            child.close();
        } finally { editor.shutdownNow(); }
        try (ConfigurationExclusion.Preparation next = ConfigurationExclusion.prepare()) {
            assertTrue(ConfigurationExclusion.LOCK.isWriteLockedByCurrentThread());
        }
    }

    @Test public void manualWritersAreDistinguishedFromRefreshAndReadOnlyMetadata() {
        for (String verb : new String[]{"create", "update", "delete", "rename", "reconnect", "touch", "password"})
            assertTrue(verb, NativeSessionGate.isConfigMutation(new String[]{"rclone", "--config", "private", "config", verb, "remote"}));
        assertTrue(NativeSessionGate.isConfigMutation(new String[]{"rclone", "--config=private", "config"}));
        assertFalse(NativeSessionGate.isConfigMutation(new String[]{"rclone", "config", "dump"}));
        assertFalse(NativeSessionGate.isConfigMutation(new String[]{"rclone", "config", "show", "remote"}));
        assertFalse(NativeSessionGate.isConfigMutation(new String[]{"rclone", "lsjson", "remote:"}));
    }
}
