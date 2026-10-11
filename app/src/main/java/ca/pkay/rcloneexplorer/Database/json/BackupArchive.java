package ca.pkay.rcloneexplorer.Database.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded, non-mutating parser for the existing three-part backup format. */
public final class BackupArchive {
    public final byte[] config;
    public final String tasks;
    public final String preferences;
    private BackupArchive(Map<String, byte[]> parts) {
        config = parts.get("rclone.conf");
        tasks = new String(parts.get("rcx.json"), StandardCharsets.UTF_8);
        preferences = new String(parts.get("rcx.prefs"), StandardCharsets.UTF_8);
    }

    public static BackupArchive read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Could not open the selected backup");
        Map<String, byte[]> parts = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.equals("rclone.conf") && !name.equals("rcx.json") && !name.equals("rcx.prefs"))
                    throw new IOException("Unexpected file in configuration backup");
                if (entry.isDirectory() || parts.containsKey(name)) throw new IOException("Duplicate or invalid backup entry");
                int limit = name.equals("rcx.prefs") ? 256 * 1024 : 4 * 1024 * 1024;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    if (n > limit - output.size() || n > 9 * 1024 * 1024 - total) throw new IOException("Configuration backup exceeds size limits");
                    output.write(buffer, 0, n);
                    total += n;
                }
                parts.put(name, output.toByteArray());
                zip.closeEntry();
            }
        }
        if (parts.size() != 3) throw new IOException("Backup must contain rclone.conf, rcx.json and rcx.prefs");
        return new BackupArchive(parts);
    }
}
