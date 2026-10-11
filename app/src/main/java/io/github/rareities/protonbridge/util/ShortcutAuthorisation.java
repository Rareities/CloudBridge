package io.github.rareities.protonbridge.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import io.github.rareities.protonbridge.Items.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Launcher shortcuts carry a scoped capability, not just a guessable database ID. */
public final class ShortcutAuthorisation {
    public static final String EXTRA_CAPABILITY = "task_capability";
    private ShortcutAuthorisation() {}
    private static SharedPreferences store(Context context) { return context.getSharedPreferences("task-shortcuts", Context.MODE_PRIVATE); }
    private static String identity(Task task) { return task.getId() + ":" + task.getBisyncUUID(); }

    public static synchronized String issue(Context context, Task task) {
        String key = identity(task);
        String existing = store(context).getString(key, null);
        if (existing != null) return existing;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        if (!store(context).edit().putString(key, token).commit()) throw new IllegalStateException("Could not save shortcut authorisation");
        return token;
    }

    public static boolean verify(Context context, Task task, String supplied) {
        if (supplied == null || supplied.length() != 43 || task.getBisyncUUID().isEmpty()) return false;
        String expected = store(context).getString(identity(task), null);
        return expected != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
