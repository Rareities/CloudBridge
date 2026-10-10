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
    private static volatile long authenticatedUntil;
    private static final int AUTH_SECONDS = 60;
    private ConfigSecretStore() {}
    public static String newPassword() {
        byte[] entropy = new byte[32];
        new java.security.SecureRandom().nextBytes(entropy);
        StringBuilder result = new StringBuilder(64);
        for (byte value : entropy) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        java.util.Arrays.fill(entropy, (byte) 0);
        return result.toString();
    }

    private static SecretKey key(boolean create) throws Exception { return key(create, false); }

    private static SecretKey key(boolean create, boolean authenticated) throws Exception {
        String alias = authenticated ? ALIAS + "-authenticated" : ALIAS;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            if (!create) throw new IOException("Configuration key is unavailable; unlock again");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec.Builder specification = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE);
            if (authenticated) {
                specification.setUserAuthenticationRequired(true);
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    specification.setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL);
                } else specification.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS);
            }
            generator.init(specification.build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }

    public static synchronized String wrap(Context context, String value) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            org.json.JSONObject wrapped = new org.json.JSONObject();
            wrapped.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            wrapped.put("data", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
            return wrapped.toString();
        } catch (Exception e) { throw new IOException("Cannot wrap secret", e); }
    }

    public static synchronized String unwrap(Context context, String value) throws IOException {
        try {
            org.json.JSONObject wrapped = new org.json.JSONObject(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                    new GCMParameterSpec(128, Base64.decode(wrapped.getString("iv"), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(wrapped.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IOException("Secret is locked or unavailable", e); }
    }

    public static synchronized void store(Context context, String password) throws IOException {
        storeMode(context, password, isAuthenticatedMode(context));
    }

    private static void storeMode(Context context, String password, boolean authenticated) throws IOException {
        if (authenticated && !authenticationFresh()) throw new IOException("Device credential unlock required");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true, authenticated));
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("authenticated", authenticated)
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
            value.put("authenticated", prefs.getBoolean("authenticated", false));
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
            editor.putBoolean("authenticated", value.optBoolean("authenticated", false));
            if (!editor.commit()) throw new IOException("Cannot restore configuration unlock");
            authenticatedUntil = 0;
        } catch (org.json.JSONException e) { throw new IOException("Invalid configuration unlock snapshot", e); }
    }

    public static synchronized void clear(Context context) throws IOException {
        boolean authenticated = isAuthenticatedMode(context);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
                .putBoolean("authenticated", authenticated).commit()) {
            throw new IOException("Cannot clear configuration unlock");
        }
    }

    public static synchronized String password(Context context) throws IOException {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String wrapped = prefs.getString("wrapped", null);
        if (wrapped == null) return null;
        boolean authenticated = prefs.getBoolean("authenticated", false);
        if (authenticated && !authenticationFresh()) throw new IOException("Device credential unlock required");
        try {
            String iv = prefs.getString("iv", null);
            if (iv == null) throw new IOException("Configuration unlock is incomplete");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false, authenticated),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IOException("Configuration unlock is unavailable", e); }
    }
    private static boolean authenticationFresh() {
        return android.os.SystemClock.elapsedRealtime() < authenticatedUntil;
    }

    public static boolean isAuthenticatedMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("authenticated", false);
    }

    public static boolean requiresAuthentication(Context context) {
        return isAuthenticatedMode(context) && !authenticationFresh();
    }

    /** Call only following successful platform device-credential confirmation. Keystore enforces it too. */
    public static void deviceCredentialConfirmed() {
        authenticatedUntil = android.os.SystemClock.elapsedRealtime() + AUTH_SECONDS * 1000L;
    }

    public static void lock() { authenticatedUntil = 0; }

    /** Rewrap only; native configuration remains encrypted and unchanged. */
    public static void setAuthenticatedMode(Context context, boolean authenticated) throws IOException {
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (authenticated && (keyguard == null || !keyguard.isDeviceSecure()))
            throw new IOException("A secure device screen lock is required");
        if (!authenticationFresh()) throw new IOException("Device credential confirmation required");
        final String[] password = new String[1];
        java.io.File staged = java.io.File.createTempFile("vault-mode-", ".conf", context.getFilesDir());
        try {
            ConfigRestoreJournal.install(context, staged, () -> {
                try { storeMode(context, password[0], authenticated); }
                catch (IOException e) { throw new IllegalStateException("Cannot rewrap configuration unlock", e); }
            }, () -> {
                password[0] = password(context);
                if (password[0] == null) throw new IOException("Encrypted configuration unlock is unavailable");
                try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(context.getFilesDir(), "rclone.conf"));
                     java.io.FileOutputStream output = new java.io.FileOutputStream(staged)) {
                    byte[] buffer = new byte[8192]; int n; long total = 0;
                    while ((n = input.read(buffer)) != -1) {
                        if ((total += n) > 16L * 1024 * 1024) throw new IOException("Configuration exceeds size limit");
                        output.write(buffer, 0, n);
                    }
                    output.getFD().sync();
                }
            });
        } finally { password[0] = null; staged.delete(); }
    }

}
