package ca.pkay.rcloneexplorer.util;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Owns private migration/import files, including cleanup after an interrupted process. */
public final class ConfigStaging {
    private ConfigStaging() {}
    private static final Set<String> PREFIXES = new HashSet<>(Arrays.asList(
            "config-protect-", "config-import-", "config-export-", "restore-config-"));
    private static final Set<File> ACTIVE = new HashSet<>();

    public static synchronized File create(Context context, String prefix, boolean cache) throws IOException {
        if (!PREFIXES.contains(prefix)) throw new IllegalArgumentException("Unknown configuration staging purpose");
        File file = File.createTempFile(prefix, ".conf", cache ? context.getCacheDir() : context.getFilesDir());
        ACTIVE.add(file);
        return file;
    }

    public static synchronized void discard(File file) {
        if (file == null) return;
        ACTIVE.remove(file);
        if (file.exists() && !file.delete()) FLog.w("ConfigStaging", "Private configuration staging cleanup is pending");
    }

    /** Deletes only recognised staging files; journals, baselines and current operations remain. */
    public static synchronized void recover(Context context) {
        for (File directory : new File[]{context.getFilesDir(), context.getCacheDir()}) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (ACTIVE.contains(file) || !file.isFile()) continue;
                String name = file.getName();
                boolean belongsToActive = false;
                for (File active : ACTIVE) {
                    if (active.getParentFile().equals(directory) && name.startsWith(active.getName()) &&
                            name.substring(active.getName().length()).matches("(\\.old)?[0-9]+")) {
                        belongsToActive = true;
                        break;
                    }
                }
                if (belongsToActive) continue;
                for (String prefix : PREFIXES) {
                    // rclone's atomic save can leave .old<digits> or a numeric
                    // suffix after a crash. These share the owned staging prefix.
                    if (name.startsWith(prefix) && name.matches(".*\\.(conf|tmp)(\\.old)?[0-9]*")) {
                        if (!file.delete()) FLog.w("ConfigStaging", "Abandoned configuration staging cleanup is pending");
                        break;
                    }
                }
            }
        }
    }
}
