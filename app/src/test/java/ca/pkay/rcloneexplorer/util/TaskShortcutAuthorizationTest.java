package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskShortcutAuthorizationTest {
    @Test public void taskIdentifierIsNotACapability() {
        String authorized = TaskShortcutAuthorization.newCapability();
        assertTrue(TaskShortcutAuthorization.matches(authorized, authorized));
        assertFalse(TaskShortcutAuthorization.matches(authorized, "42"));
        assertFalse(TaskShortcutAuthorization.matches(null, authorized));
        assertFalse(TaskShortcutAuthorization.matches(authorized, null));
        assertFalse(TaskShortcutAuthorization.matches(authorized, TaskShortcutAuthorization.newCapability()));
        String tampered = (authorized.charAt(0) == 'a' ? "b" : "a") + authorized.substring(1);
        assertFalse(TaskShortcutAuthorization.matches(authorized, tampered));
    }

    @Test public void capabilitiesAreIndependentAndUnpredictableLength() {
        String first = TaskShortcutAuthorization.newCapability();
        String second = TaskShortcutAuthorization.newCapability();
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
    }
}
