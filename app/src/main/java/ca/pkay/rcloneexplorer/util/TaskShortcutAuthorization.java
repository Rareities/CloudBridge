package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Private, task-scoped capabilities issued only when the user pins a task shortcut. */
public final class TaskShortcutAuthorization {
    public static final String EXTRA_CAPABILITY = "task_shortcut_capability";
    private static final String PREFS = "task_shortcut_capabilities";

    private TaskShortcutAuthorization() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized String issue(Context context, long taskId) {
        if (taskId < 0) throw new IllegalArgumentException("Invalid task ID");
        String key = Long.toString(taskId);
        String token = preferences(context).getString(key, null);
        if (token == null) {
            token = newCapability();
            // Persist before the launcher receives the capability. Never export this preference file.
            if (!preferences(context).edit().putString(key, token).commit()) {
                throw new IllegalStateException("Could not authorize shortcut");
            }
        }
        return token;
    }

    public static boolean isAuthorized(Context context, long taskId, String token) {
        return taskId >= 0 && matches(preferences(context).getString(Long.toString(taskId), null), token);
    }

    public static synchronized void revoke(Context context, long taskId) {
        if (!preferences(context).edit().remove(Long.toString(taskId)).commit()) {
            throw new IllegalStateException("Could not revoke shortcut");
        }
    }

    public static synchronized void revokeAll(Context context) {
        if (!preferences(context).edit().clear().commit()) {
            throw new IllegalStateException("Could not revoke shortcuts");
        }
    }

    static String newCapability() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    static boolean matches(String expected, String supplied) {
        return expected != null && supplied != null && supplied.length() == 64
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
