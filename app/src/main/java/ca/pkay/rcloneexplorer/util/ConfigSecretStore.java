package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the rclone config password as Keystore-wrapped ciphertext.
 *
 * The password is deliberately not part of the ordinary backup JSON or a log
 * record. A Keystore invalidation leaves the ciphertext in place and makes the
 * caller require explicit recovery instead of clearing a usable encrypted
 * config.
 */
public final class ConfigSecretStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cloudbridge.rclone.config.password.v1";
    private static final String PREFS = "cloudbridge.config-secret.v1";
    private static final String CIPHERTEXT = "ciphertext";
    private static final int IV_BYTES = 12;

    private final Context context;
    private final SecureRandom random = new SecureRandom();

    public ConfigSecretStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public String load() throws GeneralSecurityException {
        String encoded = preferences().getString(CIPHERTEXT, null);
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        byte[] packed;
        try {
            packed = Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Stored config secret is malformed", e);
        }
        if (packed.length <= IV_BYTES) {
            throw new GeneralSecurityException("Stored config secret is incomplete");
        }

        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[packed.length - IV_BYTES];
        System.arraycopy(packed, 0, iv, 0, IV_BYTES);
        System.arraycopy(packed, IV_BYTES, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    public void save(String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw new GeneralSecurityException("Config password is empty");
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);

        boolean committed = preferences().edit()
                .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP | Base64.URL_SAFE))
                .commit();
        if (!committed) {
            throw new GeneralSecurityException("Unable to persist config secret");
        }
    }

    /** Remove a stale passphrase after replacing the config with a new file. */
    public void clear() {
        preferences().edit().remove(CIPHERTEXT).commit();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                generator.generateKey();
            }
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        } catch (Exception e) {
            if (e instanceof GeneralSecurityException) {
                throw (GeneralSecurityException) e;
            }
            throw new GeneralSecurityException("Unable to access config secret key", e);
        }
    }
}
