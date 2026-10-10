package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import java.io.IOException;

/** Secret preference values use Keystore wrapping; ordinary settings remain compatible. */
public final class SecretSettings {
    private static final String PREFS = "wrapped_secret_settings";
    private SecretSettings() {}

    public static synchronized String password(Context context, String key) throws IOException {
        SharedPreferences protectedValues = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = protectedValues.getString(key, null);
        if (encrypted != null) return ConfigSecretStore.unwrap(context, encrypted);
        SharedPreferences legacy = PreferenceManager.getDefaultSharedPreferences(context);
        String plaintext = legacy.getString(key, null);
        if (plaintext == null) return "";
        store(context, key, plaintext);
        return plaintext;
    }

    public static synchronized void store(Context context, String key, String value) throws IOException {
        String encrypted = ConfigSecretStore.wrap(context, value == null ? "" : value);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, encrypted).commit())
            throw new IOException("Cannot protect secret setting");
        if (!PreferenceManager.getDefaultSharedPreferences(context).edit().remove(key).commit())
            throw new IOException("Cannot retire plaintext secret setting");
    }

    /** Opaque device-wrapped values for crash recovery; never a portable credential export. */
    public static synchronized String snapshot(Context context) throws IOException {
        password(context, context.getString(ca.pkay.rcloneexplorer.R.string.pref_key_proxy_password));
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            for (java.util.Map.Entry<String, ?> entry : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
                if (!(entry.getValue() instanceof String)) throw new IOException("Invalid protected setting");
                result.put(entry.getKey(), entry.getValue());
            }
            return result.toString();
        } catch (org.json.JSONException e) { throw new IOException("Cannot snapshot protected settings", e); }
    }

    public static synchronized void restore(Context context, String snapshot) throws IOException {
        try {
            org.json.JSONObject values = new org.json.JSONObject(snapshot);
            SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                editor.putString(key, values.getString(key));
            }
            if (!editor.commit()) throw new IOException("Cannot restore protected settings");
        } catch (org.json.JSONException e) { throw new IOException("Invalid protected setting snapshot", e); }
    }
}
