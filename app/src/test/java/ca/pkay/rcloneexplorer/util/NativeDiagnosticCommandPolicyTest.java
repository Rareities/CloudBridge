package ca.pkay.rcloneexplorer.util;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NativeDiagnosticCommandPolicyTest {

    @Test
    public void stripsUnsafeDiagnosticSinksAndKeepsSupportedOptionsAndAuthentication() {
        String[] safe = NativeDiagnosticCommandPolicy.withoutNativeDiagnostics(new String[]{
                "librclone.so", "--config", "/private/rclone.conf",
                "--rc-user", "rc-user", "--rc-pass", "pass;$(still-one-argv-value)",
                "--transfers", "4", "--buffer-size", "16M",
                "--log-level", "NOTICE", "--stats", "1m",
                "-vvv", "--log-file", "/private/serve.log", "--dump", "headers",
                "--log-file=/private/rcd.log", "--dump=bodies", "--dump-headers",
                "--verbose", "--verbose=DEBUG", "rcd"
        });

        assertArrayEquals(new String[]{
                "librclone.so", "--config", "/private/rclone.conf",
                "--rc-user", "rc-user", "--rc-pass", "pass;$(still-one-argv-value)",
                "--transfers", "4", "--buffer-size", "16M",
                "--log-level", "NOTICE", "--stats", "1m", "rcd"
        }, safe);
    }

    @Test
    public void supportsServeArgumentsWithRequiredBasicAuthentication() {
        String[] safe = NativeDiagnosticCommandPolicy.withoutNativeDiagnostics(new String[]{
                "librclone.so", "serve", "http", "--addr", "127.0.0.1:5572",
                "remote:folder", "--user", "serve-user", "--pass", "p@ss;word"
        });

        assertArrayEquals(new String[]{
                "librclone.so", "serve", "http", "--addr", "127.0.0.1:5572",
                "remote:folder", "--user", "serve-user", "--pass", "p@ss;word"
        }, safe);
    }

    @Test
    public void rejectsShellSyntaxOutsideOpaqueAuthenticationValues() {
        assertRejected("librclone.so", "serve", "http", "remote:path;touch-marker");
        assertRejected("librclone.so", "--config=/safe/rclone.conf$(bad)", "rcd");
        assertRejected("librclone.so", "--baseurl", "https://host/path?x=1&y=2", "serve", "http");
    }

    @Test
    public void rejectsUnsafeExecutablesAndPathTraversal() {
        assertRejected("../librclone.so", "rcd");
        assertRejected("/system/bin/sh", "rcd");
        assertRejected("librclone.so", "--config", "/data/../private/rclone.conf", "rcd");
        assertRejected("librclone.so", "--baseurl", "https://host/a/../private", "serve", "http");
        assertRejected("librclone.so", "serve", "http", "remote:folder/../private");
    }

    @Test
    public void rejectsOversizedArgumentsAndMalformedArgv() {
        char[] oversized = new char[NativeDiagnosticCommandPolicy.MAX_ARGUMENT_CHARS + 1];
        java.util.Arrays.fill(oversized, 'x');
        assertRejected("librclone.so", "serve", "http", new String(oversized));
        assertRejected("librclone.so", "--config", "/safe/rclone.conf");
        assertRejected("librclone.so", "--pass", "value");
        assertRejected("librclone.so", "--rc-pass");
        assertRejected("librclone.so", "--token", "unclassified-secret", "rcd");
        assertRejected("librclone.so", "--config", "", "rcd");
        assertRejected("librclone.so", "rcd", null);
        assertRejected("librclone.so", "serve");

        String[] oversizedCommand = new String[10];
        oversizedCommand[0] = "librclone.so";
        char[] largeArgument = new char[NativeDiagnosticCommandPolicy.MAX_ARGUMENT_CHARS];
        java.util.Arrays.fill(largeArgument, 'x');
        for (int i = 1; i < oversizedCommand.length; i++) {
            oversizedCommand[i] = new String(largeArgument);
        }
        assertRejected(oversizedCommand);
    }

    @Test
    public void rejectsSecretMaterialInDiagnosticFieldsWithoutEchoingIt() {
        String sentinel = "diagnostic-sentinel-secret";
        try {
            NativeDiagnosticCommandPolicy.withoutNativeDiagnostics(new String[]{
                    "librclone.so", "--log-level", "Authorization: Bearer " + sentinel, "rcd"
            });
            fail("Expected diagnostic secret to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsafe native command arguments", expected.getMessage());
            assertFalse(expected.getMessage().contains(sentinel));
            assertFalse(NativeDiagnosticCommandPolicy.DISABLED_NOTICE.contains(sentinel));
        }

        assertRejected("librclone.so", "--log-file=/private/diagnostic-token=" + sentinel, "rcd");
        assertRejected("librclone.so", "--dump", "password=" + sentinel, "rcd");
    }

    @Test
    public void diagnosticDisabledNoticeIsGenericAndExplainsTheTradeoff() {
        assertTrue(NativeDiagnosticCommandPolicy.DISABLED_NOTICE.contains("not persisted"));
        assertTrue(NativeDiagnosticCommandPolicy.DISABLED_NOTICE.contains("credentials"));
    }

    private static void assertRejected(String... command) {
        try {
            NativeDiagnosticCommandPolicy.withoutNativeDiagnostics(command);
            fail("Expected unsafe native command arguments to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsafe native command arguments", expected.getMessage());
        }
    }
}
