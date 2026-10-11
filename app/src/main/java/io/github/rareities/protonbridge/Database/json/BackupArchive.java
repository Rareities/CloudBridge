package io.github.rareities.protonbridge.Database.json;

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
    public final boolean encrypted;
    private BackupArchive(Map<String, byte[]> parts) {
        this(parts, false);
    }
    private BackupArchive(Map<String, byte[]> parts, boolean encrypted) {
        config = parts.get("rclone.conf");
        tasks = new String(parts.get("rcx.json"), StandardCharsets.UTF_8);
        preferences = new String(parts.get("rcx.prefs"), StandardCharsets.UTF_8);
        this.encrypted = encrypted;
        java.util.Arrays.fill(parts.get("rcx.json"), (byte)0);
        java.util.Arrays.fill(parts.get("rcx.prefs"), (byte)0);
    }

    public static BackupArchive read(android.content.Context context, android.net.Uri uri, String password) throws IOException {
        java.io.File staged = io.github.rareities.protonbridge.util.ConfigStaging.create(context, "backup-input-", true);
        char[] secret = password == null ? null : password.toCharArray();
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream output = new java.io.FileOutputStream(staged)) {
                if (input == null) throw new IOException("Could not open the selected backup");
                int total = 0, count;
                byte[] buffer = new byte[8192];
                while ((count = input.read(buffer)) != -1) {
                    if (count > io.github.rareities.protonbridge.util.ProtectedBackup.MAX_ARCHIVE - total)
                        throw new IOException("Backup archive exceeds size limits");
                    output.write(buffer, 0, count);
                    total += count;
                }
            }
            if (io.github.rareities.protonbridge.util.ProtectedBackup.isEncrypted(staged))
                return new BackupArchive(io.github.rareities.protonbridge.util.ProtectedBackup.read(staged, secret), true);
            return read(new java.io.FileInputStream(staged));
        } finally {
            if (secret != null) java.util.Arrays.fill(secret, '\0');
            io.github.rareities.protonbridge.util.ConfigStaging.discard(staged);
        }
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
