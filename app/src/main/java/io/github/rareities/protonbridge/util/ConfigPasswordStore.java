package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The native config password is wrapped by a non-exportable, per-install Android Keystore key. */
public final class ConfigPasswordStore {
    private static final String ALIAS = "protonbridge.rclone.config.v1";
    private static final byte[] AAD = "protonbridge-native-config-password-v1".getBytes(StandardCharsets.UTF_8);
    interface KeyProvider { SecretKey get(String alias, boolean create) throws GeneralSecurityException, IOException; }
    private final AtomicFile file;
    private final KeyProvider keys;

    public ConfigPasswordStore(Context context) {
        this(new File(context.getFilesDir(), "config-password.json"), (alias, create) -> {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey existing = (SecretKey) store.getKey(alias, null);
            if (existing != null) return existing;
            if (!create) throw new GeneralSecurityException("Configuration wrapping key is unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false).build());
            return generator.generateKey();
        });
    }

    ConfigPasswordStore(File path, KeyProvider keys) { this.file = new AtomicFile(path); this.keys = keys; }

    private synchronized byte[] readRecord() throws IOException {
        final byte[] data;
        try (java.io.FileInputStream input = file.openRead()) {
            byte[] buffer = new byte[32769];
            int used = 0, count;
            while (used < buffer.length && (count = input.read(buffer, used, buffer.length - used)) > 0) used += count;
            if (used > 32768) throw new IOException("Wrapped configuration password is invalid");
            data = Arrays.copyOf(buffer, used);
        } catch (FileNotFoundException missing) { return null; }
        return data;
    }

    private static String alias(JSONObject object) throws Exception {
        int version = object.getInt("version");
        if (version == 1) return ALIAS;
        if (version != 2) throw new IOException("Unsupported wrapped-password version");
        String selected = object.getString("alias");
        if (!selected.startsWith(ALIAS + ".") || selected.length() > 128 ||
                !selected.matches("[A-Za-z0-9._-]+")) throw new IOException("Invalid wrapping key alias");
        return selected;
    }

    public synchronized String read() throws IOException {
        byte[] data = readRecord();
        if (data == null) return null;
        byte[] plaintext = null;
        try {
            JSONObject object = new JSONObject(new String(data, StandardCharsets.UTF_8));
            byte[] iv = Base64.decode(object.getString("iv"), Base64.NO_WRAP);
            if (iv.length != 12) throw new IOException("Invalid wrapped-password nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(alias(object), false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(Base64.decode(object.getString("ciphertext"), Base64.NO_WRAP));
            String password = new String(plaintext, StandardCharsets.UTF_8);
            NativeConfigEncryption.validatePassword(password);
            return password;
        } catch (Exception error) {
            throw new IOException("Configuration is locked. Restore an encrypted backup or unlock with its configuration password.", error);
        } finally {
            Arrays.fill(data, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Encrypts a replacement record without altering the currently installed record.
     * A fresh alias retains the previous Keystore key for journal rollback.
     */
    public synchronized byte[] prepareRecord(String password, boolean freshKey) throws IOException {
        NativeConfigEncryption.validatePassword(password);
        byte[] plaintext = password.getBytes(StandardCharsets.UTF_8);
        try {
            String alias;
            if (freshKey) {
                alias = ALIAS + "." + UUID.randomUUID();
            } else {
                byte[] old = readRecord();
                try {
                    alias = old == null ? ALIAS : alias(new JSONObject(new String(old, StandardCharsets.UTF_8)));
                } finally { if (old != null) Arrays.fill(old, (byte) 0); }
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(alias, true));
            cipher.updateAAD(AAD);
            JSONObject object = new JSONObject().put("version", alias.equals(ALIAS) ? 1 : 2);
            if (!alias.equals(ALIAS)) object.put("alias", alias);
            object
                    .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP));
            return object.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IOException("Could not protect the configuration password with Android Keystore", error); }
        finally { Arrays.fill(plaintext, (byte) 0); }
    }

    public synchronized void save(String password) throws IOException {
        byte[] record = prepareRecord(password, false);
        try { installRecord(record); }
        finally { Arrays.fill(record, (byte) 0); }
    }

    public synchronized void installRecord(byte[] record) throws IOException {
        FileOutputStream output = file.startWrite();
        try { output.write(record); output.getFD().sync(); file.finishWrite(output); }
        catch (IOException error) { file.failWrite(output); throw error; }
        DurableFiles.syncDirectory(file.getBaseFile().getParentFile());
        try (java.io.ByteArrayInputStream expected = new java.io.ByteArrayInputStream(record)) {
            DurableFiles.verifyContents(file, expected, 32768);
        }
    }
}
