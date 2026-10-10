package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps the native rclone configuration password; no custom config encryption format. */
public final class ConfigSecretStore {
    private static final String ALIAS = "cloudbridge-rclone-config";
    private static final String PREFS = "config_secret";
    private ConfigSecretStore() {}

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new IOException("Configuration key is unavailable; unlock again");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    public static synchronized void store(Context context, String password) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("wrapped", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                throw new IOException("Cannot persist configuration unlock");
            }
        } catch (Exception e) { throw new IOException("Cannot protect configuration unlock", e); }
    }

    /** Opaque Keystore-wrapped values only; used by the private restore rollback journal. */
    public static synchronized String snapshot(Context context) throws IOException {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            org.json.JSONObject value = new org.json.JSONObject();
            String wrapped = prefs.getString("wrapped", null);
            String iv = prefs.getString("iv", null);
            if (wrapped != null) value.put("wrapped", wrapped);
            if (iv != null) value.put("iv", iv);
            return value.toString();
        } catch (org.json.JSONException e) { throw new IOException("Cannot snapshot configuration unlock", e); }
    }

    public static synchronized void restore(Context context, String snapshot) throws IOException {
        try {
            org.json.JSONObject value = new org.json.JSONObject(snapshot);
            android.content.SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
            if (value.has("wrapped")) {
                editor.putString("wrapped", value.getString("wrapped"));
                editor.putString("iv", value.getString("iv"));
            }
            if (!editor.commit()) throw new IOException("Cannot restore configuration unlock");
        } catch (org.json.JSONException e) { throw new IOException("Invalid configuration unlock snapshot", e); }
    }

    public static synchronized void clear(Context context) throws IOException {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()) {
            throw new IOException("Cannot clear configuration unlock");
        }
    }

    public static synchronized String password(Context context) throws IOException {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wrapped = prefs.getString("wrapped", null);
        if (wrapped == null) return null;
        try {
            String iv = prefs.getString("iv", null);
            if (iv == null) throw new IOException("Configuration unlock is incomplete");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IOException("Configuration unlock is unavailable", e); }
    }
}
