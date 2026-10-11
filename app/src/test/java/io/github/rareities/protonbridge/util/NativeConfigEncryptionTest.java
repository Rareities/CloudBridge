package io.github.rareities.protonbridge.util;

import org.junit.Test;
import org.junit.Assume;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class NativeConfigEncryptionTest {
    @Test public void credentialsAreEnvironmentValuesAndUnsafeInheritanceIsRemoved() {
        String[] result = NativeConfigEncryption.environment(new String[]{"TMPDIR=/private", "RCLONE_CONFIG_PASS=old",
                "RCLONE_PASSWORD_COMMAND=untrusted", "_RCLONE_CONFIG_KEY_FILE=/old/key"}, "new-password");
        assertTrue(Arrays.asList(result).contains("RCLONE_CONFIG_PASS=new-password"));
        assertTrue(Arrays.asList(result).contains("RCLONE_ASK_PASSWORD=false"));
        assertFalse(Arrays.asList(result).contains("RCLONE_CONFIG_PASS=old"));
        assertFalse(Arrays.toString(result).contains("untrusted"));
        assertFalse(Arrays.toString(result).contains("/old/key"));
        assertThrows(IllegalArgumentException.class, () -> NativeConfigEncryption.validatePassword("one\ntwo"));
    }

    /** Set PROTONBRIDGE_TEST_RCLONE to a locally built engine. No remote or account is contacted. */
    @Test(timeout = 120000) public void nativeEncryptionRetainsContentsAndRejectsWrongPasswords() throws Exception {
        String executable = System.getenv("PROTONBRIDGE_TEST_RCLONE");
        Assume.assumeTrue(executable != null && new File(executable).isFile());
        File root = Files.createTempDirectory("protonbridge-config-fixture-").toFile();
        File config = new File(root, "rclone.conf");
        Files.write(config.toPath(), "[test]\ntype = local\ncanary = plaintext-must-not-leak\n".getBytes(StandardCharsets.UTF_8));
        String[] environment = {"TMPDIR=" + root.getAbsolutePath()};
        NativeConfigEncryption.encrypt(executable, config, null, "first-configuration-password", environment);
        assertTrue(NativeConfigEncryption.isEncrypted(config));
        assertFalse(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8).contains("plaintext-must-not-leak"));
        assertFalse(NativeConfigEncryption.canRead(executable, config, "wrong-password", environment));
        NativeConfigEncryption.encrypt(executable, config, "first-configuration-password", "portable-backup-password", environment);
        assertFalse(NativeConfigEncryption.canRead(executable, config, "first-configuration-password", environment));
        assertTrue(NativeConfigEncryption.canRead(executable, config, "portable-backup-password", environment));
    }
}
