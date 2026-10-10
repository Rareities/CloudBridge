package ca.pkay.rcloneexplorer.util;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.*;
import java.util.*;

/** Established WinZip AES-256 ZIP encryption; never extracts archive paths to the filesystem. */
public final class ProtectedBackup {
    public static final long MAX_COMPONENT = 16L * 1024 * 1024;
    public static final long MAX_TOTAL = 32L * 1024 * 1024;
    private static final Set<String> COMPONENTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "backup.json", "rcx.json", "rcx.prefs", "rclone.conf", "config-unlock.txt")));
    private ProtectedBackup() {}

    public static void write(File destination, char[] password, Map<String, byte[]> components) throws IOException {
        validatePassword(password);
        if (!components.keySet().equals(COMPONENTS)) throw new IOException("Incomplete backup components");
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace staged backup");
        long total = 0;
        try (ZipFile zip = new ZipFile(destination, password)) {
            for (String name : new TreeSet<>(COMPONENTS)) {
                byte[] value = components.get(name);
                if (value == null || value.length > MAX_COMPONENT || (total += value.length) > MAX_TOTAL)
                    throw new IOException("Backup exceeds size limit");
                ZipParameters parameters = new ZipParameters();
                parameters.setFileNameInZip(name);
                parameters.setEncryptFiles(true);
                parameters.setEncryptionMethod(EncryptionMethod.AES);
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
                parameters.setCompressionMethod(CompressionMethod.DEFLATE);
                zip.addStream(new ByteArrayInputStream(value), parameters);
            }
        } catch (IOException | RuntimeException e) {
            destination.delete();
            throw new IOException("Cannot create encrypted backup", e);
        }
    }

    public static boolean isEncryptedArchive(File source) throws IOException {
        try (ZipFile zip = new ZipFile(source)) {
            return zip.isValidZipFile() && zip.isEncrypted();
        }
    }

    public static Map<String, byte[]> read(File source, char[] password) throws IOException {
        validatePassword(password);
        if (source.length() > MAX_TOTAL) throw new IOException("Backup exceeds size limit");
        Map<String, byte[]> result = new HashMap<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(source, password)) {
            List<FileHeader> headers = zip.getFileHeaders();
            if (headers.size() != COMPONENTS.size()) throw new IOException("Unexpected backup components");
            for (FileHeader header : headers) {
                String name = header.getFileName();
                if (!COMPONENTS.contains(name) || result.containsKey(name) || header.isDirectory()
                        || !header.isEncrypted() || header.getEncryptionMethod() != EncryptionMethod.AES
                        || header.getAesExtraDataRecord() == null
                        || header.getAesExtraDataRecord().getAesKeyStrength() != AesKeyStrength.KEY_STRENGTH_256
                        || header.getUncompressedSize() > MAX_COMPONENT)
                    throw new IOException("Invalid protected backup component");
                try (InputStream input = zip.getInputStream(header); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        total += n;
                        if (bytes.size() + (long) n > MAX_COMPONENT || total > MAX_TOTAL)
                            throw new IOException("Backup exceeds size limit");
                        bytes.write(buffer, 0, n);
                    }
                    // Zip4j authenticates the complete AES entry on EOF/close.
                    result.put(name, bytes.toByteArray());
                }
            }
            if (!result.keySet().equals(COMPONENTS)) throw new IOException("Incomplete protected backup");
            return result;
        } catch (IOException | RuntimeException e) {
            clear(result);
            throw new IOException("Cannot authenticate protected backup", e);
        }
    }

    public static void clear(Map<String, byte[]> components) {
        for (byte[] bytes : components.values()) if (bytes != null) Arrays.fill(bytes, (byte) 0);
        components.clear();
    }

    private static void validatePassword(char[] password) throws IOException {
        if (password == null || password.length < 12 || password.length > 1024)
            throw new IOException("Backup password must contain 12 to 1024 characters");
    }
}
