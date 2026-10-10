package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtectedBackupTest {
    private Map<String, byte[]> components() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (String name : new String[]{"backup.json", "rcx.json", "rcx.prefs", "rclone.conf", "config-unlock.txt"}) {
            values.put(name, ("canary-credential-and-private-path-" + name).getBytes(StandardCharsets.UTF_8));
        }
        return values;
    }
    @Test public void allMetadataAndUnlockValuesAreEncryptedAndAuthenticated() throws Exception {
        File file = File.createTempFile("protected-backup", ".zip");
        char[] password = "disposable-test-password".toCharArray();
        try {
            Map<String, byte[]> expected = components();
            ProtectedBackup.write(file, password, expected);
            assertTrue(ProtectedBackup.isEncryptedArchive(file));
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("canary-credential-and-private-path"));
            Map<String, byte[]> actual = ProtectedBackup.read(file, password);
            assertEquals(expected.keySet(), actual.keySet());
            for (String name : expected.keySet()) assertArrayEquals(expected.get(name), actual.get(name));
            ProtectedBackup.clear(actual);
            assertTrue(actual.isEmpty());
            try {
                ProtectedBackup.read(file, "a-wrong-test-password".toCharArray());
                fail("Wrong archive password must fail authentication");
            } catch (IOException expectedFailure) { }
        } finally { java.util.Arrays.fill(password, '\0'); file.delete(); }
    }
    @Test public void ciphertextTamperingCannotRestoreMetadata() throws Exception {
        File file = File.createTempFile("tampered-backup", ".zip");
        char[] password = "disposable-test-password".toCharArray();
        try {
            ProtectedBackup.write(file, password, components());
            byte[] bytes = Files.readAllBytes(file.toPath());
            int nameLength = (bytes[26] & 255) | ((bytes[27] & 255) << 8);
            int extraLength = (bytes[28] & 255) | ((bytes[29] & 255) << 8);
            int data = 30 + nameLength + extraLength;
            bytes[data + 20] ^= 1;
            Files.write(file.toPath(), bytes);
            try {
                ProtectedBackup.read(file, password);
                fail("Modified encrypted content must not be accepted");
            } catch (IOException expectedFailure) { }
        } finally { java.util.Arrays.fill(password, '\0'); file.delete(); }
    }
}
