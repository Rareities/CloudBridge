package ca.pkay.rcloneexplorer.Database.json;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.DARK;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.FOLLOW_SYSTEM;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.LIGHT;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import ca.pkay.rcloneexplorer.R;

public class SharedPreferencesBackup {

    private static final int MAX_IMPORT_CHARS = 256 * 1024;
    private static final int MAX_STRING_CHARS = 16 * 1024;

    private static final class ValidatedPreferences {
        private final boolean showThumbnails;
        private final boolean isWifiOnly;
        private final boolean allowWhileIdle;
        private final boolean useProxy;
        private final String proxyProtocol;
        private final String proxyHost;
        private final int proxyPort;
        private final String proxyUser;
        private final String transfers;
        private final boolean safEnabled;
        private final boolean refreshLaEnabled;
        private final boolean vcpEnabled;
        private final boolean vcpDeclareLocal;
        private final boolean vcpGrantAll;
        private final int theme;
        private final boolean wrapFilenames;
        private final boolean appUpdates;
        private final boolean useLogs;

        private ValidatedPreferences(boolean showThumbnails, boolean isWifiOnly, boolean allowWhileIdle,
                                     boolean useProxy, String proxyProtocol, String proxyHost,
                                     int proxyPort, String proxyUser, String transfers, boolean safEnabled,
                                     boolean refreshLaEnabled, boolean vcpEnabled, boolean vcpDeclareLocal,
                                     boolean vcpGrantAll, int theme, boolean wrapFilenames,
                                     boolean appUpdates, boolean useLogs) {
            this.showThumbnails = showThumbnails;
            this.isWifiOnly = isWifiOnly;
            this.allowWhileIdle = allowWhileIdle;
            this.useProxy = useProxy;
            this.proxyProtocol = proxyProtocol;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyUser = proxyUser;
            this.transfers = transfers;
            this.safEnabled = safEnabled;
            this.refreshLaEnabled = refreshLaEnabled;
            this.vcpEnabled = vcpEnabled;
            this.vcpDeclareLocal = vcpDeclareLocal;
            this.vcpGrantAll = vcpGrantAll;
            this.theme = theme;
            this.wrapFilenames = wrapFilenames;
            this.appUpdates = appUpdates;
            this.useLogs = useLogs;
        }
    }

    public static String export(Context context) throws JSONException {

        //General Settings
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean showThumbnails = sharedPreferences.getBoolean(context.getString(R.string.pref_key_show_thumbnails), false);
        boolean isWifiOnly = sharedPreferences.getBoolean(context.getString(R.string.pref_key_wifi_only_transfers), false);
        boolean allowWhileIdle = sharedPreferences.getBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), false);
        boolean useProxy = sharedPreferences.getBoolean(context.getString(R.string.pref_key_use_proxy), false);
        String proxyProtocol = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_protocol), "http");
        String proxyHost = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_host), "localhost");
        int proxyPort = sharedPreferences.getInt(context.getString(R.string.pref_key_proxy_port), 8080);
        String proxyUser = sharedPreferences.getString(context.getString(R.string.pref_key_proxy_username), "");
        String transfers = sharedPreferences.getString(context.getString(R.string.pref_key_transfers), "4");

        // File Access
        boolean safEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_saf), false);
        boolean refreshLaEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_refresh_local_aliases), true);
        boolean vcpEnabled = sharedPreferences.getBoolean(context.getString(R.string.pref_key_enable_vcp), false);
        boolean vcpDeclareLocal = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_declare_local), true);
        boolean vcpGrantAll = sharedPreferences.getBoolean(context.getString(R.string.pref_key_vcp_grant_all), false);

        // Look and Feel
        int oldTheme = sharedPreferences.getInt(context.getString(R.string.pref_key_theme_old), FOLLOW_SYSTEM);
        String newTheme = sharedPreferences.getString(context.getString(R.string.pref_key_theme), String.valueOf(oldTheme));
        boolean isWrapFilenames = sharedPreferences.getBoolean(context.getString(R.string.pref_key_wrap_filenames), true);

        // Notifications
        boolean appUpdates = sharedPreferences.getBoolean(context.getString(R.string.pref_key_app_updates), false);

        // Logging
        boolean useLogs = sharedPreferences.getBoolean(context.getString(R.string.pref_key_logs), false);


        JSONObject main = new JSONObject();

        main.put("showThumbnails", showThumbnails);
        main.put("isWifiOnly", isWifiOnly);
        main.put("allowWhileIdle", allowWhileIdle);
        main.put("useProxy", useProxy);
        main.put("proxyProtocol", proxyProtocol);
        main.put("proxyHost", proxyHost);
        main.put("proxyPort", proxyPort);
        main.put("proxyUser", proxyUser);
        main.put("transfers", transfers);
        main.put("safEnabled", safEnabled);
        main.put("refreshLaEnabled", refreshLaEnabled);
        main.put("vcpEnabled", vcpEnabled);
        main.put("vcpDeclareLocal", vcpDeclareLocal);
        main.put("vcpGrantAll", vcpGrantAll);
        main.put("isDarkTheme", newTheme);
        main.put("isWrapFilenames", isWrapFilenames);
        main.put("appUpdates", appUpdates);
        main.put("useLogs", useLogs);

        return main.toString();
    }

    public static void importJson(String json, Context context) throws JSONException {
        ValidatedPreferences values = validateAndParse(json);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        //General Settings
        editor.putBoolean(context.getString(R.string.pref_key_show_thumbnails), values.showThumbnails);
        editor.putBoolean(context.getString(R.string.pref_key_wifi_only_transfers), values.isWifiOnly);
        editor.putBoolean(context.getString(R.string.pref_key_use_proxy), values.useProxy);
        editor.putBoolean(context.getString(R.string.shared_preferences_allow_sync_trigger_while_idle), values.allowWhileIdle);
        editor.putString(context.getString(R.string.pref_key_proxy_protocol), values.proxyProtocol);
        editor.putString(context.getString(R.string.pref_key_proxy_host), values.proxyHost);
        editor.putInt(context.getString(R.string.pref_key_proxy_port), values.proxyPort);
        editor.putString(context.getString(R.string.pref_key_proxy_username), values.proxyUser);
        editor.putString(context.getString(R.string.pref_key_transfers), values.transfers);

        // File Access
        editor.putBoolean(context.getString(R.string.pref_key_enable_saf), values.safEnabled);
        editor.putBoolean(context.getString(R.string.pref_key_refresh_local_aliases), values.refreshLaEnabled);
        editor.putBoolean(context.getString(R.string.pref_key_enable_vcp), values.vcpEnabled);
        editor.putBoolean(context.getString(R.string.pref_key_vcp_declare_local), values.vcpDeclareLocal);
        editor.putBoolean(context.getString(R.string.pref_key_vcp_grant_all), values.vcpGrantAll);

        // Look and Feel
        editor.putString(context.getString(R.string.pref_key_theme), String.valueOf(values.theme));
        editor.putBoolean(context.getString(R.string.pref_key_wrap_filenames), values.wrapFilenames);

        // Notifications
        editor.putBoolean(context.getString(R.string.pref_key_app_updates), values.appUpdates);

        // Logging
        editor.putBoolean(context.getString(R.string.pref_key_logs), values.useLogs);

        if (!editor.commit()) {
            throw new JSONException("Unable to persist imported preferences");
        }
    }

    /** Parse all fields and ranges before an Editor is created or any preference is changed. */
    public static void validate(String json) throws JSONException {
        validateAndParse(json);
    }

    private static ValidatedPreferences validateAndParse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            throw new JSONException("Preferences import is empty");
        }
        if (json.length() > MAX_IMPORT_CHARS) {
            throw new JSONException("Preferences import exceeds the maximum size");
        }

        JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (JSONException e) {
            throw new JSONException("Preferences import JSON is malformed");
        }

        boolean showThumbnails = booleanValue(object, "showThumbnails", false);
        boolean isWifiOnly = booleanValue(object, "isWifiOnly", false);
        boolean allowWhileIdle = booleanValue(object, "allowWhileIdle", false);
        boolean useProxy = booleanValue(object, "useProxy", false);
        String proxyProtocol = stringValue(object, "proxyProtocol", "http");
        if (!(proxyProtocol.equals("http") || proxyProtocol.equals("https") || proxyProtocol.equals("socks5"))) {
            throw new JSONException("Unsupported proxy protocol");
        }
        String proxyHost = stringValue(object, "proxyHost", "localhost");
        int proxyPort = intValue(object, "proxyPort", 8080);
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new JSONException("Proxy port is out of range");
        }
        String proxyUser = stringValue(object, "proxyUser", "");
        String transfers = stringValue(object, "transfers", "4");
        try {
            int transferCount = Integer.parseInt(transfers);
            if (transferCount < 1 || transferCount > 1024) {
                throw new JSONException("Transfer count is out of range");
            }
        } catch (NumberFormatException e) {
            throw new JSONException("Transfer count is invalid");
        }

        boolean safEnabled = booleanValue(object, "safEnabled", false);
        boolean refreshLaEnabled = booleanValue(object, "refreshLaEnabled", true);
        boolean vcpEnabled = booleanValue(object, "vcpEnabled", false);
        boolean vcpDeclareLocal = booleanValue(object, "vcpDeclareLocal", true);
        boolean vcpGrantAll = booleanValue(object, "vcpGrantAll", false);
        int theme = themeValue(object);
        boolean wrapFilenames = booleanValue(object, "isWrapFilenames", true);
        boolean appUpdates = booleanValue(object, "appUpdates", false);
        boolean useLogs = booleanValue(object, "useLogs", false);

        return new ValidatedPreferences(showThumbnails, isWifiOnly, allowWhileIdle, useProxy,
                proxyProtocol, proxyHost, proxyPort, proxyUser, transfers, safEnabled,
                refreshLaEnabled, vcpEnabled, vcpDeclareLocal, vcpGrantAll, theme,
                wrapFilenames, appUpdates, useLogs);
    }

    private static boolean booleanValue(JSONObject object, String key, boolean defaultValue)
            throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new JSONException("Preference field is not boolean: " + key);
        }
        return (Boolean) value;
    }

    private static String stringValue(JSONObject object, String key, String defaultValue)
            throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new JSONException("Preference field is not text: " + key);
        }
        String result = (String) value;
        if (result.length() > MAX_STRING_CHARS) {
            throw new JSONException("Preference field exceeds the maximum size: " + key);
        }
        return result;
    }

    private static int intValue(JSONObject object, String key, int defaultValue) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof Number)) {
            throw new JSONException("Preference field is not numeric: " + key);
        }
        return ((Number) value).intValue();
    }

    private static int themeValue(JSONObject object) throws JSONException {
        if (!object.has("isDarkTheme") || object.isNull("isDarkTheme")) {
            return FOLLOW_SYSTEM;
        }
        Object value = object.get("isDarkTheme");
        int result;
        if (value instanceof Boolean) {
            result = (Boolean) value ? DARK : LIGHT;
        } else if (value instanceof String) {
            try {
                result = Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                throw new JSONException("Theme value is invalid");
            }
        } else if (value instanceof Number) {
            result = ((Number) value).intValue();
        } else {
            throw new JSONException("Theme value is invalid");
        }
        if (result != FOLLOW_SYSTEM && result != DARK && result != LIGHT) {
            throw new JSONException("Theme value is unsupported");
        }
        return result;
    }

}
