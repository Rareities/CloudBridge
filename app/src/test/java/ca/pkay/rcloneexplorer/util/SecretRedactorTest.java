package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class SecretRedactorTest {
    @Test public void labeledSecretsNeverSurvive() {
        String[] samples = {
            "{\"client_access_token\":\"CANARY\",\"otp_secret_key\":\"CANARY\"}",
            "password=CANARY token=CANARY RCLONE_CONFIG_PASS=CANARY",
            "Authorization: Bearer CANARY",
            "Cookie: session=CANARY; other=CANARY",
            "https://user:CANARY@example.com/path",
            "--rc-pass CANARY --config-pass='CANARY'",
            "{\"password\":\"CANARY with spaces and \\\"quotes\\\"\"}"
        };
        for (String sample : samples) assertFalse(SecretRedactor.redact(sample).contains("CANARY"));
    }
    @Test public void ordinaryDiagnosticsRemainUseful() {
        assertEquals("transfer finished: 12 files", SecretRedactor.redact("transfer finished: 12 files"));
        assertEquals("", SecretRedactor.redact(null));
    }
    @Test public void privateKeyAndLargeValueAreRemovedBeforeTruncation() {
        String text = "-----BEGIN PRIVATE KEY-----\nCANARY\n-----END PRIVATE KEY-----";
        assertFalse(SecretRedactor.redact(text).contains("CANARY"));
        StringBuilder json = new StringBuilder("{\"token\":\"CANARY");
        for (int i = 0; i < 20000; i++) json.append('x');
        json.append("\"}");
        assertFalse(SecretRedactor.redact(json.toString()).contains("CANARY"));
    }
}
