package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogRedactorTest {

    @Test
    public void redactsSecretsUrisAndPaths() {
        String source = "password=secret token=abc123 Authorization: Bearer bearer-value "
                + "content://provider/private/item /storage/emulated/0/private.txt";

        String redacted = LogRedactor.redact(source);

        assertFalse(redacted.contains("secret"));
        assertFalse(redacted.contains("abc123"));
        assertFalse(redacted.contains("bearer-value"));
        assertFalse(redacted.contains("content://provider/private/item"));
        assertFalse(redacted.contains("/storage/emulated/0/private.txt"));
        assertTrue(redacted.contains("***redacted***"));
        assertTrue(redacted.contains("***redacted-uri***"));
        assertTrue(redacted.contains("***redacted-path***"));
    }

    @Test
    public void leavesOrdinaryDiagnosticsReadable() {
        assertTrue(LogRedactor.redact("rclone exited with status 7").contains("status 7"));
    }

    @Test
    public void boundsLargeDiagnostics() {
        String redacted = LogRedactor.redact(new String(new char[LogRedactor.MAX_DIAGNOSTIC_CHARS * 2])
                .replace('\0', 'x'));

        assertTrue(redacted.length() <= LogRedactor.MAX_DIAGNOSTIC_CHARS);
        assertTrue(redacted.contains("diagnostic-output-truncated"));
    }

    @Test
    public void comparesShortcutCapabilitiesWithoutAcceptingMissingValues() {
        assertTrue(ShortcutCapabilities.tokensEqual("capability", "capability"));
        assertFalse(ShortcutCapabilities.tokensEqual("capability", "other"));
        assertFalse(ShortcutCapabilities.tokensEqual("capability", null));
        assertFalse(ShortcutCapabilities.tokensEqual(null, "capability"));
    }
}
