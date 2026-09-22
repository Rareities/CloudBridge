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
 * Private capability tokens for pinned task shortcuts.
 *
 * The shortcut activity is necessarily exported so real launchers can invoke
 * it. A task ID alone is therefore not an authorization credential: an
 * untrusted caller must also present the token issued when the shortcut was
 * created.
 */
public final class ShortcutCapabilities {

    public static final String EXTRA_CAPABILITY =
            "ca.pkay.rcloneexplorer.extra.SHORTCUT_CAPABILITY";

    private static final String PREFS_NAME = "shortcut_capabilities";
    private static final String TASK_KEY_PREFIX = "task:";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

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

    static boolean tokensEqual(@Nullable String expectedToken, @Nullable String suppliedToken) {
        if (expectedToken == null || suppliedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String taskKey(long taskId) {
        return TASK_KEY_PREFIX + taskId;
    }
}
