package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteErrorMessagePolicyTest {

    @Test
    public void redactsCredentialsAndPrivateLocations() {
        String safe = RemoteErrorMessagePolicy.forUser(
                "Upload failed https://alice:pw-canary@storage.example/path?X-Amz-Signature=sig-canary "
                        + "content://private-provider/secret Authorization: Bearer token-canary C:\\Users\\Alice\\secret");

        assertFalse(safe.contains("pw-canary"));
        assertFalse(safe.contains("sig-canary"));
        assertFalse(safe.contains("token-canary"));
        assertFalse(safe.contains("C:\\Users\\Alice"));
        assertFalse(safe.contains("content://private-provider"));
        assertTrue(safe.contains("storage.example"));
    }

    @Test
    public void collapsesControlCharactersToPreventMultilineIpcMessages() {
        assertEquals("failure detail next line",
                RemoteErrorMessagePolicy.forUser("failure\r\ndetail\u0000next\tline"));
    }

    @Test
    public void emptyAndOversizedMessagesAreSafeAndBounded() {
        assertEquals("Remote operation failed", RemoteErrorMessagePolicy.forUser(" \n\u0000"));

        String safe = RemoteErrorMessagePolicy.forUser(repeat('x', 4096));
        assertTrue(safe.length() <= RemoteErrorMessagePolicy.MAX_MESSAGE_CHARS);
        assertTrue(safe.endsWith("…"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
