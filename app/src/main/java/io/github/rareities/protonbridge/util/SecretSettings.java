package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.preference.PreferenceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Keystore-backed settings, authenticated to their package and setting name. */
public final class SecretSettings {
    static final String PREFS = "wrapped_secret_settings_v1";
    private static final String ALIAS = "protonbridge.secret-settings.v1";
    interface KeyProvider { SecretKey get(boolean create) throws Exception; }
    private final Context context;
    private final KeyProvider keys;
    SecretSettings(Context context, KeyProvider keys) { this.context = context; this.keys = keys; }
    private static SecretSettings create(Context context) {
        return new SecretSettings(context, create -> {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
            if (existing != null) return existing;
            if (!create) throw new IOException("The settings wrapping key is unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        });
    }
    public static synchronized String password(Context context, String key) throws IOException { return create(context).read(key); }
    public static synchronized void store(Context context, String key, String value) throws IOException { create(context).write(key, value); }
    public static synchronized void migrateLegacy(Context context, String key) throws IOException {
        if (PreferenceManager.getDefaultSharedPreferences(context).contains(key)) create(context).read(key);
    }
    private byte[] aad(String key) { return (context.getPackageName() + "\u0000" + key).getBytes(StandardCharsets.UTF_8); }

    String read(String key) throws IOException {
        SharedPreferences protectedValues = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences legacy = PreferenceManager.getDefaultSharedPreferences(context);
        String record = protectedValues.getString(key, null);
        if (record == null) {
            String value = legacy.getString(key, null);
            if (value == null) return "";
            write(key, value);
            return value;
        }
        byte[] plaintext = null;
        try {
            if (record.length() > 32768) throw new IOException("Invalid protected setting size");
            JSONObject object = new JSONObject(record);
            if (object.getInt("version") != 1) throw new IOException("Unsupported protected setting version");
            byte[] nonce = Base64.decode(object.getString("iv"), Base64.NO_WRAP);
            if (nonce.length != 12) throw new IOException("Invalid protected setting nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(false), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(key));
            plaintext = cipher.doFinal(Base64.decode(object.getString("ciphertext"), Base64.NO_WRAP));
            if (plaintext.length > 8192) throw new IOException("Invalid protected setting size");
            if (legacy.contains(key) && !legacy.edit().remove(key).commit()) throw new IOException("Cannot retire a legacy secret setting");
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception failure) { throw new IOException("Protected proxy password is unavailable. Re-enter it in settings.", failure); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte)0); }
    }

    void write(String key, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).commit() ||
                    !PreferenceManager.getDefaultSharedPreferences(context).edit().remove(key).commit())
                throw new IOException("Cannot clear the protected secret setting");
            return;
        }
        byte[] plaintext = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        try {
            if (plaintext.length > 8192) throw new IOException("Secret setting is too long");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(true));
            cipher.updateAAD(aad(key));
            String record = new JSONObject().put("version", 1)
                    .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP)).toString();
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, record).commit())
                throw new IOException("Cannot persist a protected setting");
            if (!PreferenceManager.getDefaultSharedPreferences(context).edit().remove(key).commit())
                throw new IOException("Cannot retire a legacy secret setting");
        } catch (Exception failure) { throw new IOException("Cannot protect the proxy password with Android Keystore", failure); }
        finally { Arrays.fill(plaintext, (byte)0); }
    }

    /** Device-bound ciphertext only, for the existing private rollback journal. */
    public static synchronized JSONObject snapshot(Context context) throws IOException {
        try {
            migrateLegacy(context, context.getString(io.github.rareities.protonbridge.R.string.pref_key_proxy_password));
            JSONObject result = new JSONObject();
            for (java.util.Map.Entry<String, ?> item : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
                if (!(item.getValue() instanceof String)) throw new IOException("Invalid protected setting");
                result.put(item.getKey(), item.getValue());
            }
            return result;
        } catch (org.json.JSONException invalid) { throw new IOException("Cannot snapshot protected settings", invalid); }
    }

    public static synchronized void restoreSnapshot(Context context, JSONObject snapshot) throws IOException {
        if (snapshot.toString().length() > 65536) throw new IOException("Protected settings journal is too large");
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
        try {
            java.util.Iterator<String> keys = snapshot.keys();
            while (keys.hasNext()) { String key = keys.next(); editor.putString(key, snapshot.getString(key)); }
            if (!editor.commit()) throw new IOException("Cannot restore protected settings");
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid protected settings journal", invalid); }
    }
}
