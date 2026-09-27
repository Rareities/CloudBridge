package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogRedactorTest {

    @Test
    public void redactsSecretsUrisAndPaths() {
        String source = "password=secret token=abc123\nAuthorization: Bearer bearer-value\n"
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
    public void redactsEntireBasicAndDigestAuthorizationHeaderLines() {
        String source = "Authorization: Basic basic-secret-canary\r\n"
                + "Authorization: Digest username=\"digest-user-canary\", "
                + "realm=\"realm-canary\", response=\"digest-secret-canary\"\r\n"
                + "unrelated-following-line";

        String redacted = LogRedactor.redact(source);

        assertFalse(redacted.contains("basic-secret-canary"));
        assertFalse(redacted.contains("digest-user-canary"));
        assertFalse(redacted.contains("realm-canary"));
        assertFalse(redacted.contains("digest-secret-canary"));
        assertTrue(redacted.contains("unrelated-following-line"));
        assertFalse(LogRedactor.redact(
                "{\"authorization\":\"Basic json-secret-canary\"}")
                .contains("json-secret-canary"));
    }

    @Test
    public void redactsUrlUserInfoWithoutDiscardingHostOrPath() {
        String source = "https://alice:pw%40canary@dav.example.invalid/private/file"
                + " sftp://obsidian-canary@files.example.invalid/vault";

        String redacted = LogRedactor.redact(source);

        assertFalse(redacted.contains("alice"));
        assertFalse(redacted.contains("pw%40canary"));
        assertFalse(redacted.contains("obsidian-canary"));
        assertTrue(redacted.contains("https://***redacted***@dav.example.invalid/private/file"));
        assertTrue(redacted.contains("sftp://***redacted***@files.example.invalid/vault"));
    }

    @Test
    public void redactsSignedObjectStoreQueryCredentials() {
        String source = "https://bucket.example.invalid/file?X-Amz-Signature=aws-signature-canary"
                + "&X-Amz-Credential=aws-credential-canary&part=visible"
                + " https://storage.example.invalid/file?X-Goog-Signature=google-signature-canary"
                + "&GoogleAccessId=google-id-canary"
                + " https://files.example.invalid/file?sig=generic-signature-canary"
                + "&oauth_signature=oauth-signature-canary";

        String redacted = LogRedactor.redact(source);

        assertFalse(redacted.contains("aws-signature-canary"));
        assertFalse(redacted.contains("aws-credential-canary"));
        assertFalse(redacted.contains("google-signature-canary"));
        assertFalse(redacted.contains("google-id-canary"));
        assertFalse(redacted.contains("generic-signature-canary"));
        assertFalse(redacted.contains("oauth-signature-canary"));
        assertTrue(redacted.contains("&part=visible"));
        assertTrue(redacted.contains("X-Amz-Signature=***redacted***"));
    }

    @Test
    public void redactsRclonePassOptionInSeparatedAndEqualsForms() {
        String redacted = LogRedactor.redact(
                "rclone serve --pass CLI_PASS_CANARY --pass=\"EQUALS PASS CANARY\"");

        assertFalse(redacted.contains("CLI_PASS_CANARY"));
        assertFalse(redacted.contains("EQUALS PASS CANARY"));
        assertTrue(redacted.contains("***redacted***"));
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
