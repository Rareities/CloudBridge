package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Private capability tokens for exported shortcuts and app-issued internal actions.
 *
 * The shortcut activity is necessarily exported so real launchers can invoke
 * it. A task ID alone is therefore not an authorization credential: an
 * untrusted caller must also present the token issued when the shortcut was
 * created.
 */
public final class ShortcutCapabilities {

    public static final String EXTRA_CAPABILITY =
            "ca.pkay.rcloneexplorer.extra.SHORTCUT_CAPABILITY";
    public static final String EXTRA_INTENT_CAPABILITY =
            "ca.pkay.rcloneexplorer.extra.INTERNAL_INTENT_CAPABILITY";

    private static final String PREFS_NAME = "shortcut_capabilities";
    private static final String INTENT_PREFS_NAME = "internal_intent_capabilities";
    private static final String TASK_KEY_PREFIX = "task:";
    private static final String INTENT_KEY_PREFIX = "intent:";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object INTENT_CAPABILITY_LOCK = new Object();

    private ShortcutCapabilities() {
    }

    @NonNull
    public static String issueOrGet(@NonNull Context context, long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        SharedPreferences preferences = preferences(context);
        String existing = preferences.getString(taskKey(taskId), null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
        preferences.edit().putString(taskKey(taskId), token).apply();
        return token;
    }

    public static boolean isValid(@NonNull Context context, long taskId,
                                  @Nullable String suppliedToken) {
        if (taskId <= 0 || suppliedToken == null || suppliedToken.isEmpty()) {
            return false;
        }
        String expectedToken = preferences(context).getString(taskKey(taskId), null);
        return tokensEqual(expectedToken, suppliedToken);
    }

    /**
     * Issue a durable, action-and-target-scoped capability for an internal PendingIntent.
     * Exported activities must not treat an action string or target name as authorization.
     */
    @NonNull
    public static String issueOrGetForIntent(@NonNull Context context, @NonNull String action,
                                             @NonNull String target) {
        SharedPreferences preferences = context.getSharedPreferences(
                INTENT_PREFS_NAME, Context.MODE_PRIVATE);
        return issueOrGetForIntent(new SharedPreferencesIntentCapabilityStore(preferences),
                action, target, RANDOM);
    }

    static String issueOrGetForIntent(IntentCapabilityStore store, String action, String target,
                                      SecureRandom random) {
        if (store == null || random == null) {
            throw new NullPointerException("capability store and random source are required");
        }
        String key = intentKey(action, target);
        synchronized (INTENT_CAPABILITY_LOCK) {
            String existing;
            try {
                existing = store.get(key);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Unable to read intent capability", failure);
            }
            if (isToken(existing)) {
                return existing;
            }

            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = toHex(bytes);
            try {
                if (!store.put(key, token)) {
                    throw new IllegalStateException("Intent capability was not persisted");
                }
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Unable to persist intent capability", failure);
            }
            return token;
        }
    }

    /** Validate and consume a capability so replayed external intents cannot repeat the action. */
    public static boolean consumeIntent(@NonNull Context context, @Nullable String action,
                                        @Nullable String target, @Nullable String suppliedToken) {
        if (suppliedToken == null) {
            return false;
        }
        final String key;
        try {
            key = intentKey(action, target);
        } catch (IllegalArgumentException invalidScope) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                INTENT_PREFS_NAME, Context.MODE_PRIVATE);
        return consumeIntent(new SharedPreferencesIntentCapabilityStore(preferences),
                key, suppliedToken);
    }

    /**
     * Validate a reusable capability carried by launcher shortcuts. Unlike internal
     * PendingIntent capabilities, a launcher invokes the same shortcut repeatedly, so this
     * check deliberately does not consume the stored token.
     */
    public static boolean isValidIntent(@NonNull Context context, @Nullable String action,
                                        @Nullable String target, @Nullable String suppliedToken) {
        if (!isToken(suppliedToken)) {
            return false;
        }
        final String key;
        try {
            key = intentKey(action, target);
        } catch (IllegalArgumentException invalidScope) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                INTENT_PREFS_NAME, Context.MODE_PRIVATE);
        return isValidIntent(new SharedPreferencesIntentCapabilityStore(preferences),
                key, suppliedToken);
    }

    static boolean isValidIntent(IntentCapabilityStore store, String action, String target,
                                 String suppliedToken) {
        if (store == null || !isToken(suppliedToken)) {
            return false;
        }
        final String key;
        try {
            key = intentKey(action, target);
        } catch (IllegalArgumentException invalidScope) {
            return false;
        }
        return isValidIntent(store, key, suppliedToken);
    }

    static boolean consumeIntent(IntentCapabilityStore store, String action, String target,
                                 String suppliedToken) {
        if (store == null) {
            return false;
        }
        final String key;
        try {
            key = intentKey(action, target);
        } catch (IllegalArgumentException invalidScope) {
            return false;
        }
        return consumeIntent(store, key, suppliedToken);
    }

    private static boolean consumeIntent(IntentCapabilityStore store, String key,
                                         String suppliedToken) {
        if (!isToken(suppliedToken)) {
            return false;
        }
        synchronized (INTENT_CAPABILITY_LOCK) {
            try {
                String expectedToken = store.get(key);
                if (!isToken(expectedToken) || !tokensEqual(expectedToken, suppliedToken)) {
                    return false;
                }
                return store.remove(key);
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }

    private static boolean isValidIntent(IntentCapabilityStore store, String key,
                                         String suppliedToken) {
        synchronized (INTENT_CAPABILITY_LOCK) {
            try {
                return tokensEqual(store.get(key), suppliedToken);
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }

    private static String intentKey(String action, String target) {
        if (action == null || action.isEmpty() || action.indexOf('\u0000') >= 0
                || target == null || target.isEmpty() || target.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Action and target are required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (action + '\u0000' + target).getBytes(StandardCharsets.UTF_8));
            return INTENT_KEY_PREFIX + toHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isToken(@Nullable String token) {
        if (token == null || token.length() != TOKEN_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String toHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    interface IntentCapabilityStore {
        String get(String key);
        boolean put(String key, String value);
        boolean remove(String key);
    }

    private static final class SharedPreferencesIntentCapabilityStore
            implements IntentCapabilityStore {
        private final SharedPreferences preferences;

        SharedPreferencesIntentCapabilityStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public String get(String key) {
            return preferences.getString(key, null);
        }

        @Override
        public boolean put(String key, String value) {
            return preferences.edit().putString(key, value).commit();
        }

        @Override
        public boolean remove(String key) {
            return preferences.edit().remove(key).commit();
        }
    }

    static boolean tokensEqual(@Nullable String expectedToken, @Nullable String suppliedToken) {
        if (expectedToken == null || suppliedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }

    /** Return an external extra only when its runtime type is actually String. */
    @Nullable
    public static String stringValue(@Nullable Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String taskKey(long taskId) {
        return TASK_KEY_PREFIX + taskId;
    }
}
