package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FLogTest {

    @Test
    public void boundsComposedThrowableMessageAndMissingDetailSuffix() {
        String message = repeated('m', LogRedactor.MAX_DIAGNOSTIC_CHARS - 8);
        String detail = repeated('d', LogRedactor.MAX_DIAGNOSTIC_CHARS - 8);

        String composed = FLog.withThrowable(message, new IllegalStateException(detail));

        assertEquals(LogRedactor.MAX_DIAGNOSTIC_CHARS, composed.length());
        assertTrue(composed.endsWith("\n***diagnostic-output-truncated***"));

        String withoutDetail = FLog.withThrowable(
                repeated('n', LogRedactor.MAX_DIAGNOSTIC_CHARS), new IllegalStateException());
        assertEquals(LogRedactor.MAX_DIAGNOSTIC_CHARS, withoutDetail.length());
        assertTrue(withoutDetail.endsWith("\n***diagnostic-output-truncated***"));
    }

    @Test
    public void redactsSecretCanaryInOversizedComposedThrowableMessage() {
        String canary = "FLOG_WP02_SECRET_CANARY";
        String detail = "provider failure --pass=" + canary + " "
                + repeated('x', LogRedactor.MAX_DIAGNOSTIC_CHARS);

        String composed = FLog.withThrowable("rclone operation failed",
                new IllegalArgumentException(detail));

        assertFalse(composed.contains(canary));
        assertTrue(composed.contains("***redacted***"));
        assertTrue(composed.length() <= LogRedactor.MAX_DIAGNOSTIC_CHARS);
    }

    @Test
    public void boundsNullThrowableAndHandlesEmptyDetail() {
        String longMessage = repeated('m', LogRedactor.MAX_DIAGNOSTIC_CHARS * 2);
        String withoutThrowable = FLog.withThrowable(longMessage, null);
        assertEquals(LogRedactor.MAX_DIAGNOSTIC_CHARS, withoutThrowable.length());

        String emptyDetail = FLog.withThrowable("operation failed", new IllegalStateException(""));
        assertEquals("operation failed [IllegalStateException]", emptyDetail);

        String alreadyBounded = LogRedactor.redact(longMessage);
        String composed = FLog.withThrowable(alreadyBounded, new IllegalStateException("tail"));
        assertTrue(composed.length() <= LogRedactor.MAX_DIAGNOSTIC_CHARS);
    }

    @Test
    public void redactsSecretCanaryForNullAndAlreadyBoundedMessages() {
        String canary = "FLOG_WP02_NULL_OR_BOUNDED_SECRET_CANARY";
        String oversizedMessage = "operation --pass=" + canary + " "
                + repeated('x', LogRedactor.MAX_DIAGNOSTIC_CHARS * 2);

        String withoutThrowable = FLog.withThrowable(oversizedMessage, null);
        assertFalse(withoutThrowable.contains(canary));
        assertTrue(withoutThrowable.contains("***redacted***"));
        assertTrue(withoutThrowable.length() <= LogRedactor.MAX_DIAGNOSTIC_CHARS);

        String alreadyBounded = LogRedactor.redact(oversizedMessage);
        assertEquals(LogRedactor.MAX_DIAGNOSTIC_CHARS, alreadyBounded.length());
        assertTrue(alreadyBounded.endsWith("\n***diagnostic-output-truncated***"));

        String composed = FLog.withThrowable(alreadyBounded, new IllegalStateException("tail"));
        assertFalse(composed.contains(canary));
        assertTrue(composed.contains("***redacted***"));
        assertTrue(composed.length() <= LogRedactor.MAX_DIAGNOSTIC_CHARS);
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
