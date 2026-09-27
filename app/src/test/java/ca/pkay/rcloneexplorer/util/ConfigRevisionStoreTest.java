package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ConfigRevisionStoreTest {
    private static final String BEFORE = "00000000-0000-0000-0000-000000000001";

    @Test
    public void failedFinalizationAfterMemoryApplyKeepsRevisionUnavailable() {
        Map<String, Object> values = new HashMap<>();
        values.put("revision", BEFORE);
        values.put("mutation_pending", true);
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences preferences = preferences(values, false, commits);
        ConfigRevisionStore.ProcessState state = new ConfigRevisionStore.ProcessState();

        assertFalse(state.finishMutation(preferences));
        assertEquals(2, commits.get());
        assertTrue((Boolean) values.get("mutation_pending"));
        assertNull(state.current(preferences));
        assertFalse(state.beginMutation(preferences));
    }

    @Test
    public void successfulFinalizationPublishesFreshRevision() {
        Map<String, Object> values = new HashMap<>();
        values.put("revision", BEFORE);
        values.put("mutation_pending", true);
        SharedPreferences preferences = preferences(values, true, new AtomicInteger());
        ConfigRevisionStore.ProcessState state = new ConfigRevisionStore.ProcessState();

        assertTrue(state.finishMutation(preferences));
        String current = state.current(preferences);
        assertTrue(ConfigRevisionPolicy.isValidRevision(current));
        assertNotEquals(BEFORE, current);
        assertFalse((Boolean) values.get("mutation_pending"));
    }

    private static SharedPreferences preferences(
            Map<String, Object> values, boolean commitResult, AtomicInteger commits) {
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "edit":
                            return editor(values, commitResult, commits);
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

    private static SharedPreferences.Editor editor(
            Map<String, Object> values, boolean commitResult, AtomicInteger commits) {
        Map<String, Object> updates = new HashMap<>();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "putString":
                        case "putBoolean":
                            updates.put((String) args[0], args[1]);
                            return proxy;
                        case "commit":
                            values.putAll(updates);
                            commits.incrementAndGet();
                            return commitResult;
                        default:
                            return proxy;
                    }
                });
    }
}
