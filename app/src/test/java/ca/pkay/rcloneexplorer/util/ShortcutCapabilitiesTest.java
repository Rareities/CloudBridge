package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ShortcutCapabilitiesTest {

    @Test
    public void intentCapabilityIsScopedDurableAndSingleUse() {
        MemoryCapabilityStore store = new MemoryCapabilityStore();
        SecureRandom random = new SecureRandom();

        String issued = ShortcutCapabilities.issueOrGetForIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", random);
        assertEquals(issued, ShortcutCapabilities.issueOrGetForIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", random));
        String otherRemote = ShortcutCapabilities.issueOrGetForIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-b", random);
        String otherAction = ShortcutCapabilities.issueOrGetForIntent(
                store, "MAIN_ACTIVITY_START_EXPORT", "remote-a", random);
        assertNotEquals(issued, otherRemote);
        assertNotEquals(issued, otherAction);

        assertFalse(ShortcutCapabilities.consumeIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-b", issued));
        assertFalse(ShortcutCapabilities.consumeIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", "forged-token"));
        assertTrue(ShortcutCapabilities.consumeIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", issued));
        assertFalse(ShortcutCapabilities.consumeIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", issued));
        assertNotNull(ShortcutCapabilities.issueOrGetForIntent(
                store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", random));
        assertEquals(3, store.values.size());
    }

    @Test
    public void launcherIntentCapabilityIsReusableButScopedAndNotConsumedByValidation() {
        MemoryCapabilityStore store = new MemoryCapabilityStore();
        String action = "android.intent.action.MAIN";
        String target = "remote-a";
        String token = ShortcutCapabilities.issueOrGetForIntent(
                store, action, target, new SecureRandom());

        assertTrue(ShortcutCapabilities.isValidIntent(store, action, target, token));
        assertTrue(ShortcutCapabilities.isValidIntent(store, action, target, token));
        assertFalse(ShortcutCapabilities.isValidIntent(store, "other.action", target, token));
        assertFalse(ShortcutCapabilities.isValidIntent(store, action, "remote-b", token));
        assertFalse(ShortcutCapabilities.isValidIntent(store, action, target, null));
        assertFalse(ShortcutCapabilities.isValidIntent(store, action, target, "malformed"));
        assertTrue(ShortcutCapabilities.isValidIntent(store, action, target, token));
    }

    @Test
    public void invalidIntentCapabilityScopesAreRejected() {
        MemoryCapabilityStore store = new MemoryCapabilityStore();
        String token = ShortcutCapabilities.issueOrGetForIntent(
                store, "action", "remote", new SecureRandom());

        assertFalse(ShortcutCapabilities.isValidIntent(store, null, "remote", token));
        assertFalse(ShortcutCapabilities.isValidIntent(store, "action", "", token));
        assertFalse(ShortcutCapabilities.isValidIntent(store, "action", "remote\u0000a", token));
    }

    @Test
    public void failedPersistenceDoesNotReturnAnIntentCapability() {
        MemoryCapabilityStore store = new MemoryCapabilityStore();
        store.failWrites = true;

        try {
            ShortcutCapabilities.issueOrGetForIntent(
                    store, "MAIN_ACTIVITY_START_REAUTH", "remote-a", new SecureRandom());
            fail("Expected persistence failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("persist"));
        }
        assertTrue(store.values.isEmpty());
    }

    @Test
    public void externalStringExtraRejectsWrongParcelableType() {
        assertEquals("remote-a", ShortcutCapabilities.stringValue("remote-a"));
        assertNull(ShortcutCapabilities.stringValue(42));
        assertNull(ShortcutCapabilities.stringValue(new Object()));
        assertNull(ShortcutCapabilities.stringValue(null));
    }

    private static final class MemoryCapabilityStore
            implements ShortcutCapabilities.IntentCapabilityStore {
        private final Map<String, String> values = new HashMap<>();
        private boolean failWrites;

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public boolean put(String key, String value) {
            if (failWrites) return false;
            values.put(key, value);
            return true;
        }

        @Override
        public boolean remove(String key) {
            values.remove(key);
            return true;
        }
    }
}
