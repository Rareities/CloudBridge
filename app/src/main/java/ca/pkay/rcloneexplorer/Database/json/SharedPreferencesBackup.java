package ca.pkay.rcloneexplorer.Database.json;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.DARK;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.FOLLOW_SYSTEM;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.LIGHT;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import ca.pkay.rcloneexplorer.R;

public class SharedPreferencesBackup {

    private static final int MAX_IMPORT_CHARS = 256 * 1024;
    private static final int MAX_STRING_CHARS = 16 * 1024;
    private static final int MAX_JSON_NUMBER_TOKEN_CHARS = 128;
    private static final int MAX_JSON_NUMBER_EXPONENT = 128;
    // Maximum array/object nesting inside the top-level preferences object.
    private static final int MAX_JSON_NESTING_DEPTH = 64;

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

        int documentStart = jsonDocumentStart(json);
        if (documentStart > 0 && documentStart < json.length()
                && json.charAt(documentStart) == '\uFEFF') {
            throw new JSONException("Preferences import JSON is malformed");
        }
        String document = documentStart == 0 ? json : json.substring(documentStart);
        validateTopLevelKeysAndNesting(document);

        JSONObject object;
        try {
            object = new JSONObject(document);
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
        int proxyPort = intValue(object, "proxyPort", 8080, document);
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new JSONException("Proxy port is out of range");
        }
        String proxyUser = stringValue(object, "proxyUser", "");
        String transfers = stringValue(object, "transfers", "4");
        int transferCount = exactIntegerValue(transfers, "Transfer count is invalid");
        if (transferCount < 1 || transferCount > 1024) {
            throw new JSONException("Transfer count is out of range");
        }

        boolean safEnabled = booleanValue(object, "safEnabled", false);
        boolean refreshLaEnabled = booleanValue(object, "refreshLaEnabled", true);
        boolean vcpEnabled = booleanValue(object, "vcpEnabled", false);
        boolean vcpDeclareLocal = booleanValue(object, "vcpDeclareLocal", true);
        boolean vcpGrantAll = booleanValue(object, "vcpGrantAll", false);
        int theme = themeValue(object, document);
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

    private static int intValue(JSONObject object, String key, int defaultValue, String json)
            throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return defaultValue;
        }
        Object value = object.get(key);
        if (!(value instanceof Number)) {
            throw new JSONException("Preference field is not numeric: " + key);
        }
        return exactJsonIntegerValue(json, key,
                "Preference field is not an in-range integer: " + key);
    }

    private static int themeValue(JSONObject object, String json) throws JSONException {
        if (!object.has("isDarkTheme") || object.isNull("isDarkTheme")) {
            return FOLLOW_SYSTEM;
        }
        Object value = object.get("isDarkTheme");
        int result;
        if (value instanceof Boolean) {
            result = (Boolean) value ? DARK : LIGHT;
        } else if (value instanceof String) {
            result = exactIntegerValue((String) value, "Theme value is invalid");
        } else if (value instanceof Number) {
            result = exactJsonIntegerValue(json, "isDarkTheme", "Theme value is invalid");
        } else {
            throw new JSONException("Theme value is invalid");
        }
        if (result != FOLLOW_SYSTEM && result != DARK && result != LIGHT) {
            throw new JSONException("Theme value is unsupported");
        }
        return result;
    }

    private static int exactIntegerValue(String value, String errorMessage) throws JSONException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new JSONException(errorMessage);
        }
    }

    private static int exactJsonIntegerValue(String json, String key, String errorMessage)
            throws JSONException {
        String rawValue = rawTopLevelValue(json, key);
        if (rawValue == null || rawValue.length() > MAX_JSON_NUMBER_TOKEN_CHARS
                || !hasBoundedExponent(rawValue)) {
            throw new JSONException(errorMessage);
        }
        try {
            return new BigDecimal(rawValue).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new JSONException(errorMessage);
        }
    }

    /** Validate strict JSON syntax, complete consumption, nesting, and duplicate keys up front. */
    private static void validateTopLevelKeysAndNesting(String json) throws JSONException {
        new StrictJsonParser(json).validateObjectDocument();
    }

    /** Android's JSONTokener accepts one UTF-8 BOM at the very beginning of a document. */
    private static int jsonDocumentStart(String json) {
        return json != null && !json.isEmpty() && json.charAt(0) == '\uFEFF' ? 1 : 0;
    }

    /** A bounded strict parser runs before Android's permissive JSONTokener. */
    private static final class StrictJsonParser {
        private final String json;
        private int position;

        private StrictJsonParser(String json) {
            this.json = json;
        }

        private void validateObjectDocument() throws JSONException {
            skipWhitespace();
            if (position >= json.length() || json.charAt(position) != '{') {
                throw malformedJson();
            }
            parseObject(0);
            skipWhitespace();
            if (position != json.length()) {
                throw malformedJson();
            }
        }

        private void parseObject(int depth) throws JSONException {
            checkDepth(depth);
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return;
            }

            Set<String> keys = new HashSet<>();
            while (true) {
                if (position >= json.length() || json.charAt(position) != '"') {
                    throw malformedJson();
                }
                String key = parseString();
                if (!keys.add(key)) {
                    throw new JSONException("Duplicate preference field");
                }
                skipWhitespace();
                expect(':');
                parseValue(depth);
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void parseArray(int depth) throws JSONException {
            checkDepth(depth);
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return;
            }

            while (true) {
                parseValue(depth);
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private void parseValue(int parentDepth) throws JSONException {
            skipWhitespace();
            if (position >= json.length()) {
                throw malformedJson();
            }

            char first = json.charAt(position);
            if (first == '"') {
                parseString();
            } else if (first == '{') {
                parseObject(parentDepth + 1);
            } else if (first == '[') {
                parseArray(parentDepth + 1);
            } else if (first == 't') {
                parseLiteral("true");
            } else if (first == 'f') {
                parseLiteral("false");
            } else if (first == 'n') {
                parseLiteral("null");
            } else if (isJsonNumberStart(first)) {
                parseNumber();
            } else {
                throw malformedJson();
            }
        }

        private String parseString() throws JSONException {
            expect('"');
            StringBuilder decoded = new StringBuilder();
            while (position < json.length()) {
                char character = json.charAt(position++);
                if (character == '"') {
                    return decoded.toString();
                }
                if (character < 0x20) {
                    throw malformedJson();
                }
                if (character != '\\') {
                    decoded.append(character);
                    continue;
                }
                if (position >= json.length()) {
                    throw malformedJson();
                }
                char escape = json.charAt(position++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        decoded.append(escape);
                        break;
                    case 'b':
                        decoded.append('\b');
                        break;
                    case 'f':
                        decoded.append('\f');
                        break;
                    case 'n':
                        decoded.append('\n');
                        break;
                    case 'r':
                        decoded.append('\r');
                        break;
                    case 't':
                        decoded.append('\t');
                        break;
                    case 'u':
                        decoded.append(parseUnicodeEscape());
                        break;
                    default:
                        throw malformedJson();
                }
            }
            throw malformedJson();
        }

        private char parseUnicodeEscape() throws JSONException {
            if (json.length() - position < 4) {
                throw malformedJson();
            }
            int codeUnit = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hexValue(json.charAt(position++));
                if (digit < 0) {
                    throw malformedJson();
                }
                codeUnit = (codeUnit << 4) | digit;
            }
            return (char) codeUnit;
        }

        private void parseNumber() throws JSONException {
            int start = position;
            if (consume('-') && position >= json.length()) {
                throw malformedJson();
            }
            if (consume('0')) {
                // A leading zero cannot be followed by another integer digit.
            } else if (isDigitBetween('1', '9')) {
                while (isDigit()) {
                    position++;
                }
            } else {
                throw malformedJson();
            }

            if (consume('.')) {
                if (!isDigit()) {
                    throw malformedJson();
                }
                while (isDigit()) {
                    position++;
                }
            }

            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                if (!isDigit()) {
                    throw malformedJson();
                }
                while (isDigit()) {
                    position++;
                }
            }
            validateJsonNumberToken(json, start, position);
        }

        private boolean isDigit() {
            return position < json.length()
                    && json.charAt(position) >= '0' && json.charAt(position) <= '9';
        }

        private boolean isDigitBetween(char lower, char upper) {
            return position < json.length()
                    && json.charAt(position) >= lower && json.charAt(position) <= upper;
        }

        private void parseLiteral(String literal) throws JSONException {
            if (!json.regionMatches(position, literal, 0, literal.length())) {
                throw malformedJson();
            }
            position += literal.length();
        }

        private void checkDepth(int depth) throws JSONException {
            if (depth > MAX_JSON_NESTING_DEPTH) {
                throw new JSONException("Preferences import exceeds the maximum nesting depth");
            }
        }

        private void skipWhitespace() {
            while (position < json.length() && isJsonWhitespace(json.charAt(position))) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (position < json.length() && json.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) throws JSONException {
            if (!consume(expected)) {
                throw malformedJson();
            }
        }

        private static int hexValue(char character) {
            if (character >= '0' && character <= '9') {
                return character - '0';
            }
            if (character >= 'a' && character <= 'f') {
                return character - 'a' + 10;
            }
            if (character >= 'A' && character <= 'F') {
                return character - 'A' + 10;
            }
            return -1;
        }

        private static JSONException malformedJson() {
            return new JSONException("Preferences import JSON is malformed");
        }
    }

    private static String decodeJsonString(String rawString) throws JSONException {
        try {
            Object value = new JSONTokener(rawString).nextValue();
            if (value instanceof String) {
                return (String) value;
            }
        } catch (JSONException e) {
            throw new JSONException("Preferences import JSON is malformed");
        }
        throw new JSONException("Preferences import JSON is malformed");
    }

    /** Bound BigDecimal parsing/conversion work for untrusted numeric tokens. */
    private static boolean hasBoundedExponent(String numericToken) {
        int exponentIndex = Math.max(numericToken.lastIndexOf('e'), numericToken.lastIndexOf('E'));
        if (exponentIndex < 0) {
            return true;
        }
        try {
            int exponent = Integer.parseInt(numericToken.substring(exponentIndex + 1));
            return exponent >= -MAX_JSON_NUMBER_EXPONENT && exponent <= MAX_JSON_NUMBER_EXPONENT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isJsonNumberStart(char character) {
        return character == '-' || (character >= '0' && character <= '9');
    }

    /** Return the original token for a top-level field, before JSONObject can round its number. */
    private static String rawTopLevelValue(String json, String targetKey) throws JSONException {
        int position = skipJsonWhitespace(json, 0);
        if (position >= json.length() || json.charAt(position) != '{') {
            throw new JSONException("Preferences import JSON is malformed");
        }
        position++;
        String rawValue = null;
        boolean found = false;

        while (position < json.length()) {
            position = skipJsonWhitespace(json, position);
            if (position < json.length() && json.charAt(position) == '}') {
                return found ? rawValue : null;
            }

            int keyStart = position;
            position = scanJsonStringEnd(json, position);
            String key = decodeJsonString(json.substring(keyStart, position));

            position = skipJsonWhitespace(json, position);
            if (position >= json.length() || json.charAt(position) != ':') {
                throw new JSONException("Preferences import JSON is malformed");
            }
            int valueStart = skipJsonWhitespace(json, position + 1);
            position = scanJsonValueEnd(json, valueStart);
            if (targetKey.equals(key)) {
                if (found) {
                    throw new JSONException("Preference field is duplicated: " + targetKey);
                }
                rawValue = json.substring(valueStart, position);
                found = true;
            }

            position = skipJsonWhitespace(json, position);
            if (position < json.length() && json.charAt(position) == ',') {
                position++;
            } else if (position < json.length() && json.charAt(position) == '}') {
                return found ? rawValue : null;
            } else {
                throw new JSONException("Preferences import JSON is malformed");
            }
        }
        throw new JSONException("Preferences import JSON is malformed");
    }

    private static int scanJsonStringEnd(String json, int start) throws JSONException {
        if (start >= json.length() || json.charAt(start) != '"') {
            throw new JSONException("Preferences import JSON is malformed");
        }
        for (int position = start + 1; position < json.length(); position++) {
            char character = json.charAt(position);
            if (character == '\\') {
                position++;
            } else if (character == '"') {
                return position + 1;
            }
        }
        throw new JSONException("Preferences import JSON is malformed");
    }

    private static int scanJsonValueEnd(String json, int start) throws JSONException {
        if (start >= json.length()) {
            throw new JSONException("Preferences import JSON is malformed");
        }
        char first = json.charAt(start);
        if (first == '"') {
            return scanJsonStringEnd(json, start);
        }
        if (first != '{' && first != '[') {
            int position = start;
            while (position < json.length()) {
                char character = json.charAt(position);
                if (isJsonValueDelimiter(character)) {
                    break;
                }
                position++;
            }
            if (isJsonNumberStart(first)) {
                validateJsonNumberToken(json, start, position);
            }
            return position;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int position = start; position < json.length(); position++) {
            char character = json.charAt(position);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
            } else if (character == '"') {
                inString = true;
            } else if (character == '{' || character == '[') {
                depth++;
                if (depth > MAX_JSON_NESTING_DEPTH) {
                    throw new JSONException("Preferences import exceeds the maximum nesting depth");
                }
            } else if (character == '}' || character == ']') {
                depth--;
                if (depth == 0) {
                    return position + 1;
                }
            } else if (isJsonNumberStart(character)) {
                int numberEnd = position + 1;
                while (numberEnd < json.length()
                        && !isJsonValueDelimiter(json.charAt(numberEnd))) {
                    numberEnd++;
                }
                validateJsonNumberToken(json, position, numberEnd);
                position = numberEnd - 1;
            }
        }
        throw new JSONException("Preferences import JSON is malformed");
    }

    private static void validateJsonNumberToken(String json, int start, int end)
            throws JSONException {
        if (end - start > MAX_JSON_NUMBER_TOKEN_CHARS
                || !hasBoundedExponent(json.substring(start, end))) {
            throw new JSONException("Preference numeric value exceeds safe parsing limits");
        }
    }

    private static boolean isJsonValueDelimiter(char character) {
        return character == ',' || character == ']' || character == '}'
                || character == ':' || isJsonWhitespace(character);
    }

    private static int skipJsonWhitespace(String json, int start) {
        int position = start;
        while (position < json.length() && isJsonWhitespace(json.charAt(position))) {
            position++;
        }
        return position;
    }

    private static boolean isJsonWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }

}
