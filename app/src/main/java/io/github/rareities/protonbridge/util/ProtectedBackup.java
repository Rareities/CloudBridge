package io.github.rareities.protonbridge.util;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import java.io.*;
import java.util.*;

/** Standard WinZip AES-256 for the existing three backup components; no path extraction. */
public final class ProtectedBackup {
    public static final int MAX_TOTAL = 9 * 1024 * 1024;
    public static final int MAX_ARCHIVE = 10 * 1024 * 1024;
    private static final Set<String> NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "rclone.conf", "rcx.json", "rcx.prefs")));
    private ProtectedBackup() {}

    private static int limit(String name) { return name.equals("rcx.prefs") ? 256 * 1024 : 4 * 1024 * 1024; }

    public static void write(File destination, char[] password, Map<String, byte[]> parts) throws IOException {
        password(password);
        if (!parts.keySet().equals(NAMES)) throw new IOException("Incomplete backup components");
        int total = 0;
        for (String name : NAMES) {
            byte[] bytes = parts.get(name);
            if (bytes == null || bytes.length > limit(name) || bytes.length > MAX_TOTAL - total)
                throw new IOException("Configuration backup exceeds size limits");
            total += bytes.length;
        }
        if (destination.exists() && (destination.length() != 0 || !destination.delete()))
            throw new IOException("Backup staging destination is not a new empty file");
        try (ZipFile zip = new ZipFile(destination, password)) {
            for (String name : new TreeSet<>(NAMES)) {
                ZipParameters parameters = new ZipParameters();
                parameters.setFileNameInZip(name);
                parameters.setEncryptFiles(true);
                parameters.setEncryptionMethod(EncryptionMethod.AES);
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                parameters.setCompressionMethod(CompressionMethod.DEFLATE);
                zip.addStream(new ByteArrayInputStream(parts.get(name)), parameters);
            }
        } catch (IOException | RuntimeException error) {
            if (destination.exists()) destination.delete();
            throw new IOException("Could not create the encrypted backup", error);
        }
    }

    public static boolean isEncrypted(File source) throws IOException {
        if (source.length() > MAX_ARCHIVE) throw new IOException("Backup archive exceeds size limits");
        try (ZipFile zip = new ZipFile(source)) { return zip.isEncrypted(); }
    }

    public static Map<String, byte[]> read(File source, char[] password) throws IOException {
        password(password);
        if (source.length() > MAX_ARCHIVE) throw new IOException("Backup archive exceeds size limits");
        Map<String, byte[]> parts = new HashMap<>();
        int total = 0;
        try (ZipFile zip = new ZipFile(source, password)) {
            List<FileHeader> headers = zip.getFileHeaders();
            if (headers.size() != NAMES.size()) throw new IOException("Unexpected backup components");
            for (FileHeader entry : headers) {
                String name = entry.getFileName();
                if (!NAMES.contains(name) || parts.containsKey(name) || entry.isDirectory() ||
                        !entry.isEncrypted() || entry.getEncryptionMethod() != EncryptionMethod.AES ||
                        entry.getAesExtraDataRecord() == null ||
                        entry.getAesExtraDataRecord().getAesKeyStrength() != AesKeyStrength.KEY_STRENGTH_256 ||
                        entry.getUncompressedSize() < 0 || entry.getUncompressedSize() > limit(name))
                    throw new IOException("Invalid encrypted backup component");
                try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (n > limit(name) - output.size() || n > MAX_TOTAL - total)
                            throw new IOException("Configuration backup exceeds size limits");
                        output.write(buffer, 0, n);
                        total += n;
                    }
                    // Zip4j verifies the entry authentication code at EOF; partial entries are never accepted.
                    parts.put(name, output.toByteArray());
                    Arrays.fill(buffer, (byte)0);
                }
            }
            if (!parts.keySet().equals(NAMES)) throw new IOException("Incomplete encrypted backup");
            return parts;
        } catch (IOException | RuntimeException failure) {
            clear(parts);
            throw new IOException("Could not authenticate the backup. Check its password and integrity.", failure);
        }
    }

    public static void clear(Map<String, byte[]> parts) {
        for (byte[] bytes : parts.values()) if (bytes != null) Arrays.fill(bytes, (byte)0);
        parts.clear();
    }

    private static void password(char[] password) throws IOException {
        if (password == null || password.length < 12 || password.length > 1024)
            throw new IOException("Encrypted backups require a password of 12 to 1024 characters");
    }
}
