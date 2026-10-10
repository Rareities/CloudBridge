package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import java.io.IOException;

/** Invalidates queued operations when a restore can change the meaning of remote names. */
public final class ConfigEpoch {
    public static final String WORK_KEY = "config_epoch";
    private ConfigEpoch() {}
    public static long current(Context context) {
        return context.getSharedPreferences("config_epoch", Context.MODE_PRIVATE).getLong("value", 0);
    }
    /** Called under operation admission before restore changes any configuration. */
    public static void advance(Context context) throws IOException {
        long value = current(context);
        if (value == Long.MAX_VALUE) throw new IOException("Configuration generation exhausted");
        if (!context.getSharedPreferences("config_epoch", Context.MODE_PRIVATE).edit()
                .putLong("value", value + 1).commit()) {
            throw new IOException("Cannot invalidate queued operations before restore");
        }
    }
}
