package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticRedactorTest {
    @Test public void secretsAreRemovedFromJsonTextAndProxyUrls() {
        for (String input : new String[]{
                "{\"password\":\"CANARY\",\"level\":\"error\"}",
                "{\"client_refresh_token\":\"CANARY\"}",
                "{\"client_salted_key_pass\":\"CANARY\"}",
                "password=CANARY", "Authorization: Bearer CANARY", "api-key='CANARY'",
                "https://name:CANARY@proxy.example/", "totp_secret=CANARY",
                "Authorization: Basic CANARY", "RCLONE_CONFIG_PASS=CANARY", "rc_pass=CANARY",
                "{\"RCLONE_RC_PASS\":\"CANARY\"}", "Cookie: harmless=1; session=CANARY"
        }) assertFalse(input, DiagnosticRedactor.redact(input).contains("CANARY"));
    }
    @Test public void ordinaryFailureTextIsPreserved() {
        assertEquals("rclone exited with status 7", DiagnosticRedactor.redact("rclone exited with status 7"));
    }
}
