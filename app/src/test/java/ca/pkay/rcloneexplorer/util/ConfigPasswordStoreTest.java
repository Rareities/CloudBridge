package ca.pkay.rcloneexplorer.util;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ConfigPasswordStoreTest {
    private File path() throws IOException {
        Application app = ApplicationProvider.getApplicationContext();
        return new File(Files.createTempDirectory(app.getFilesDir().toPath(), "password-test-").toFile(), "wrapped.json");
    }
    private SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }
    @Test public void wrappedPasswordRoundTripDoesNotExposePlaintext() throws Exception {
        File path = path();
        SecretKey key = key();
        ConfigPasswordStore store = new ConfigPasswordStore(path, (alias, create) -> key);
        assertNull(store.read());
        String secret = "config-password-canary-123456";
        store.save(secret);
        byte[] first = Files.readAllBytes(path.toPath());
        assertFalse(new String(first, StandardCharsets.UTF_8).contains(secret));
        assertEquals(secret, store.read());
        store.save(secret);
        assertFalse(java.util.Arrays.equals(first, Files.readAllBytes(path.toPath())));
    }
    @Test public void wrongKeyAndTamperingFailWithoutClearingWrappedPassword() throws Exception {
        File path = path();
        SecretKey key = key(), other = key();
        ConfigPasswordStore store = new ConfigPasswordStore(path, (alias, create) -> key);
        store.save("password-that-must-survive-errors");
        byte[] original = Files.readAllBytes(path.toPath());
        assertThrows(IOException.class, () -> new ConfigPasswordStore(path, (alias, create) -> other).read());
        assertArrayEquals(original, Files.readAllBytes(path.toPath()));
        org.json.JSONObject record = new org.json.JSONObject(new String(original, StandardCharsets.UTF_8));
        byte[] encrypted = android.util.Base64.decode(record.getString("ciphertext"), android.util.Base64.NO_WRAP);
        encrypted[0] ^= 1;
        record.put("ciphertext", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP));
        Files.write(path.toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, store::read);
        assertTrue(path.isFile());
    }
    @Test public void invalidatedKeystoreKeyAllowsStagedRecoveryWithoutOverwritingOriginal() throws Exception {
        File path = path();
        SecretKey oldKey = key(), recoveryKey = key();
        java.util.Map<String, SecretKey> keys = new java.util.HashMap<>();
        String legacyAlias = "cloudbridge.rclone.config.v1";
        keys.put(legacyAlias, oldKey);
        ConfigPasswordStore original = new ConfigPasswordStore(path, (alias, create) -> {
            SecretKey selected = keys.get(alias);
            if (selected == null && create) {
                keys.put(alias, recoveryKey);
                return recoveryKey;
            }
            if (selected == null) throw new java.security.GeneralSecurityException("missing alias");
            return selected;
        });
        original.save("original-wrapped-password");
        byte[] before = Files.readAllBytes(path.toPath());
        keys.remove(legacyAlias); // Simulate invalidated/lost Android Keystore key.
        assertThrows(IOException.class, original::read);
        byte[] staged = original.prepareRecord("verified-recovery-password", true);
        assertArrayEquals("preparation must not change the live wrapped password", before, Files.readAllBytes(path.toPath()));
        original.installRecord(staged);
        assertEquals("verified-recovery-password", original.read());
        original.installRecord(before);
        assertArrayEquals(before, Files.readAllBytes(path.toPath()));
        assertThrows(IOException.class, original::read);
    }
}
