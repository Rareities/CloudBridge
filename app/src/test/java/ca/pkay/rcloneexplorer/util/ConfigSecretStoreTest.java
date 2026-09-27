package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ConfigSecretStoreTest {

    @Test
    public void legacyCiphertextRemainsReadableUntilAnImportInvalidatesIt() {
        SharedPreferences preferences = preferences(true,
                values("ciphertext", "legacy-ciphertext"), new AtomicInteger());

        assertTrue(ConfigSecretStore.hasUsableCiphertext(preferences));
        assertEquals("legacy-unbound", ConfigSecretStore.currentGeneration(preferences));
    }

    @Test
    public void ciphertextIsUsableOnlyForItsCurrentGeneration() {
        Map<String, Object> initial = values(
                "ciphertext", "encrypted-value",
                "generation", "generation-2",
                "bound_generation", "generation-2");
        SharedPreferences preferences = preferences(true, initial, new AtomicInteger());
        assertTrue(ConfigSecretStore.hasUsableCiphertext(preferences));

        initial.put("generation", "generation-3");
        assertFalse(ConfigSecretStore.hasUsableCiphertext(preferences));
    }

    @Test
    public void invalidationPreservesCiphertextButCanRestoreItsPriorGeneration() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        Map<String, Object> initial = values(
                "ciphertext", "encrypted-value",
                "generation", "generation-1",
                "bound_generation", "generation-1");
        SharedPreferences preferences = preferences(true, initial, commits);

        ConfigSecretStore.InvalidationToken token =
                ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-2");

        assertEquals(1, commits.get());
        assertEquals("generation-2", initial.get("generation"));
        assertEquals("encrypted-value", initial.get("ciphertext"));
        assertEquals("generation-1", initial.get("bound_generation"));
        assertFalse(ConfigSecretStore.hasUsableCiphertext(preferences));

        assertTrue(ConfigSecretStore.restoreInvalidation(preferences, token));
        assertEquals("generation-1", initial.get("generation"));
        assertTrue(ConfigSecretStore.hasUsableCiphertext(preferences));
    }

    @Test
    public void explicitClearRemovesCiphertextAndItsBinding() throws Exception {
        Map<String, Object> initial = values(
                "ciphertext", "encrypted-value",
                "generation", "generation-1",
                "bound_generation", "generation-1");
        SharedPreferences preferences = preferences(true, initial, new AtomicInteger());

        ConfigSecretStore.clearPersistedSecret(preferences, "generation-2");

        assertFalse(initial.containsKey("ciphertext"));
        assertFalse(initial.containsKey("bound_generation"));
        assertEquals("generation-2", initial.get("generation"));
        assertFalse(ConfigSecretStore.hasUsableCiphertext(preferences));
    }

    @Test
    public void failedInvalidationCommitIsReportedAndLeavesPriorStateUntouched() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        Map<String, Object> initial = values("ciphertext", "legacy-ciphertext");
        SharedPreferences preferences = preferences(false, initial, commits);

        try {
            ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-2");
            fail("a failed durable invalidation must abort config replacement");
        } catch (IOException expected) {
            assertEquals(1, commits.get());
            assertTrue(ConfigSecretStore.hasUsableCiphertext(preferences));
            assertFalse(initial.containsKey("generation"));
        }
    }

    @Test
    public void invalidationFailureAfterMemoryApplyReturnsTokenAndRecoveryRemainsUnconfirmed()
            throws Exception {
        Map<String, Object> original = values(
                "ciphertext", "opaque-ciphertext",
                "generation", "generation-1",
                "bound_generation", "generation-1");
        Map<String, Object> state = new HashMap<>(original);
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences preferences = preferences(false, true, state, commits);

        ConfigSecretStore.InvalidationToken token;
        try {
            ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-2");
            fail("a false commit must abort config replacement");
            return;
        } catch (ConfigSecretStore.InvalidationException failure) {
            token = failure.token();
        }

        assertEquals("generation-2", state.get("generation"));
        assertFalse(ConfigSecretStore.restoreInvalidation(preferences, token));
        // The fake applies editor changes in memory before returning false. Despite the
        // visible preimage, the owner must not clear its durable pending barrier.
        assertEquals(original, state);
        assertEquals(2, commits.get());
    }

    @Test
    public void currentSnapshotRoundTripsCiphertextAndBothGenerationValues() throws Exception {
        Map<String, Object> original = values(
                "ciphertext", "opaque-not-decrypted",
                "generation", "generation-1",
                "bound_generation", "generation-1");
        Map<String, Object> state = new HashMap<>(original);
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences preferences = preferences(true, state, commits);

        ConfigSecretStore.Snapshot snapshot = ConfigSecretStore.snapshotPersistedSecret(preferences);
        assertEquals(0, commits.get());
        ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-2");
        int commitsBeforeRestore = commits.get();

        assertTrue(ConfigSecretStore.restorePersistedSecret(
                preferences, snapshot, "generation-2"));

        assertEquals(commitsBeforeRestore + 1, commits.get());
        assertEquals(original, state);
    }

    @Test
    public void invalidationTokenCarriesAtomicOpaqueSecretPreimage() throws Exception {
        Map<String, Object> original = values(
                "ciphertext", "opaque-not-decrypted",
                "generation", "generation-1",
                "bound_generation", "generation-1");
        Map<String, Object> state = new HashMap<>(original);
        SharedPreferences preferences = preferences(true, state, new AtomicInteger());

        ConfigSecretStore.InvalidationToken token =
                ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-2");

        assertTrue(ConfigSecretStore.restorePersistedSecret(
                preferences, token.snapshot(), token.expectedGeneration()));
        assertEquals(original, state);
    }

    @Test
    public void invalidationChainDetectsALaterPasswordSave() throws Exception {
        Map<String, Object> state = values(
                "ciphertext", "initial-ciphertext",
                "generation", "generation-1",
                "bound_generation", "generation-1");
        SharedPreferences preferences = preferences(true, state, new AtomicInteger());

        ConfigSecretStore.InvalidationToken replacement =
                ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-2");
        ConfigSecretStore.InvalidationToken rollbackWithoutSave =
                ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-3");
        assertTrue(rollbackWithoutSave.follows(replacement));

        state.put("ciphertext", "later-password-ciphertext");
        state.put("generation", "generation-4");
        state.put("bound_generation", "generation-4");
        ConfigSecretStore.InvalidationToken rollbackAfterSave =
                ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-5");

        assertFalse(rollbackAfterSave.follows(replacement));
    }

    @Test
    public void legacySnapshotRestoresCiphertextWithoutAddingGenerationMetadata() throws Exception {
        Map<String, Object> original = values("ciphertext", "legacy-opaque-ciphertext");
        Map<String, Object> state = new HashMap<>(original);
        SharedPreferences preferences = preferences(true, state, new AtomicInteger());
        ConfigSecretStore.Snapshot snapshot = ConfigSecretStore.snapshotPersistedSecret(preferences);

        ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-after-legacy");
        assertTrue(ConfigSecretStore.restorePersistedSecret(
                preferences, snapshot, "generation-after-legacy"));

        assertEquals(original, state);
        assertTrue(ConfigSecretStore.hasUsableCiphertext(preferences));
        assertEquals("legacy-unbound", ConfigSecretStore.currentGeneration(preferences));
    }

    @Test
    public void absentSnapshotRestoresAbsenceOfCiphertextAndGenerationKeys() throws Exception {
        Map<String, Object> original = values();
        Map<String, Object> state = new HashMap<>(original);
        SharedPreferences preferences = preferences(true, state, new AtomicInteger());
        ConfigSecretStore.Snapshot snapshot = ConfigSecretStore.snapshotPersistedSecret(preferences);

        ConfigSecretStore.invalidatePersistedSecret(preferences, "generation-after-absence");
        assertTrue(ConfigSecretStore.restorePersistedSecret(
                preferences, snapshot, "generation-after-absence"));

        assertEquals(original, state);
        assertFalse(ConfigSecretStore.hasUsableCiphertext(preferences));
        assertEquals("legacy-unbound", ConfigSecretStore.currentGeneration(preferences));
    }

    @Test
    public void snapshotRestoreRefusesToOverwriteALaterPasswordSave() throws Exception {
        SharedPreferences oldPreferences = preferences(true,
                values("ciphertext", "old-ciphertext",
                        "generation", "generation-1",
                        "bound_generation", "generation-1"), new AtomicInteger());
        ConfigSecretStore.Snapshot snapshot =
                ConfigSecretStore.snapshotPersistedSecret(oldPreferences);

        Map<String, Object> laterSavedState = values(
                "ciphertext", "later-password-ciphertext",
                "generation", "generation-3",
                "bound_generation", "generation-3");
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences currentPreferences = preferences(true, laterSavedState, commits);

        assertFalse(ConfigSecretStore.restorePersistedSecret(
                currentPreferences, snapshot, "generation-2"));

        assertEquals(0, commits.get());
        assertEquals("later-password-ciphertext", laterSavedState.get("ciphertext"));
        assertEquals("generation-3", laterSavedState.get("generation"));
        assertEquals("generation-3", laterSavedState.get("bound_generation"));
    }

    @Test
    public void failedSnapshotRestoreCommitLeavesCurrentStateUntouched() throws Exception {
        SharedPreferences oldPreferences = preferences(true,
                values("ciphertext", "old-ciphertext",
                        "generation", "generation-1",
                        "bound_generation", "generation-1"), new AtomicInteger());
        ConfigSecretStore.Snapshot snapshot =
                ConfigSecretStore.snapshotPersistedSecret(oldPreferences);

        Map<String, Object> currentState = values(
                "ciphertext", "current-ciphertext",
                "generation", "generation-2",
                "bound_generation", "generation-2");
        Map<String, Object> beforeRestore = new HashMap<>(currentState);
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences preferences = preferences(false, currentState, commits);

        try {
            ConfigSecretStore.restorePersistedSecret(preferences, snapshot, "generation-2");
            fail("a failed snapshot commit must be reported");
        } catch (IOException expected) {
            assertEquals(1, commits.get());
            // This fake models a commit rejected before applying its pending editor values.
            // Android may mutate the in-memory map before a disk-commit failure is returned.
            assertEquals(beforeRestore, currentState);
        }
    }

    @Test
    public void failedSnapshotRestoreCommitAfterMemoryApplyStillThrows() throws Exception {
        SharedPreferences oldPreferences = preferences(true,
                values("ciphertext", "old-ciphertext",
                        "generation", "generation-1",
                        "bound_generation", "generation-1"), new AtomicInteger());
        ConfigSecretStore.Snapshot snapshot =
                ConfigSecretStore.snapshotPersistedSecret(oldPreferences);
        Map<String, Object> currentState = values(
                "ciphertext", "current-ciphertext",
                "generation", "generation-2",
                "bound_generation", "generation-2");
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences currentPreferences = preferences(false, true, currentState, commits);

        try {
            ConfigSecretStore.restorePersistedSecret(currentPreferences, snapshot, "generation-2");
            fail("a false commit is not durable evidence");
        } catch (IOException expected) {
            assertEquals(1, commits.get());
            assertEquals("old-ciphertext", currentState.get("ciphertext"));
            assertEquals("generation-1", currentState.get("generation"));
        }
    }

    private static Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    private static SharedPreferences preferences(boolean commitResult,
            Map<String, Object> values, AtomicInteger commitCalls) {
        return preferences(commitResult, false, values, commitCalls);
    }

    private static SharedPreferences preferences(boolean commitResult, boolean applyBeforeFailure,
            Map<String, Object> values, AtomicInteger commitCalls) {
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "edit":
                            return editor(commitResult, applyBeforeFailure, values, commitCalls);
                        case "getString": {
                            Object value = values.get(args[0]);
                            return value instanceof String ? value : args[1];
                        }
                        case "getBoolean": {
                            Object value = values.get(args[0]);
                            return value instanceof Boolean ? value : args[1];
                        }
                        default:
                            return null;
                    }
                });
    }

    private static SharedPreferences.Editor editor(boolean commitResult, boolean applyBeforeFailure,
            Map<String, Object> values, AtomicInteger commitCalls) {
        Map<String, Object> pending = new HashMap<>();
        java.util.Set<String> removals = new java.util.HashSet<>();
        boolean[] clear = {false};
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "remove":
                            removals.add((String) args[0]);
                            pending.remove(args[0]);
                            return proxy;
                        case "putString":
                        case "putBoolean":
                            pending.put((String) args[0], args[1]);
                            removals.remove(args[0]);
                            return proxy;
                        case "commit":
                            commitCalls.incrementAndGet();
                            if (commitResult || applyBeforeFailure) {
                                if (clear[0]) values.clear();
                                for (String key : removals) values.remove(key);
                                values.putAll(pending);
                            }
                            return commitResult;
                        case "clear":
                            clear[0] = true;
                            pending.clear();
                            removals.clear();
                            return proxy;
                        default:
                            return null;
                    }
                });
    }
}
