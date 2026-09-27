package ca.pkay.rcloneexplorer.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Copies and validates one backup archive before callers inspect any imported component.
 * The returned handle intentionally exposes entry bytes, not its mutable backing file.
 */
public final class BackupArchiveStager {
    public static final String DATABASE_ENTRY = "rcx.json";
    public static final String PREFERENCES_ENTRY = "rcx.prefs";
    public static final String CONFIG_ENTRY = "rclone.conf";

    /**
     * Provisional compressed-archive ceiling: review against real user backup sizes before
     * treating this as a permanent compatibility limit. It allows the three existing 4 MiB
     * entries plus ZIP overhead, while bounding private staging storage.
     */
    public static final long MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L;
    public static final long MAX_ENTRY_BYTES = 4L * 1024L * 1024L;
    /** Three supported components, each already limited to MAX_ENTRY_BYTES. */
    public static final long MAX_EXPANDED_BYTES = 3L * MAX_ENTRY_BYTES;
    /** Caps central-directory work before the platform ZIP reader is opened. */
    public static final int MAX_ARCHIVE_ENTRIES = 64;
    /** Caps stale stager files removed by one fresh application startup. */
    public static final int MAX_ORPHAN_ARCHIVES_PER_CLEANUP = 32;

    private static final String STAGED_ARCHIVE_PREFIX = "backup-import-";
    private static final String STAGED_ARCHIVE_SUFFIX = ".zip";
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L;
    private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final int EOCD_FIXED_BYTES = 22;
    private static final int EOCD_MAX_COMMENT_BYTES = 0xffff;
    private static final long ZIP32_SENTINEL = 0xffffffffL;
    private static final int ZIP16_SENTINEL = 0xffff;

    public interface InputStreamSource {
        InputStream open() throws IOException;
    }

    private BackupArchiveStager() {
    }

    /**
     * Removes at most a bounded number of orphaned archives created by this stager.
     * Only direct child files with the exact private-temp prefix/suffix and generated-name
     * alphabet are eligible; unrelated files and directories are never traversed.
     */
    public static int cleanupOrphanedArchives(File privateDirectory) throws IOException {
        if (privateDirectory == null) {
            throw new IllegalArgumentException("Private staging directory is required");
        }
        if (!privateDirectory.isDirectory()) {
            throw new IOException("Private archive staging directory is unavailable");
        }
        File[] children = privateDirectory.listFiles();
        if (children == null) {
            throw new IOException("Unable to list private archive staging directory");
        }

        int deleted = 0;
        for (File child : children) {
            if (deleted >= MAX_ORPHAN_ARCHIVES_PER_CLEANUP) {
                break;
            }
            if (child.isFile() && isStagerTemporaryArchiveName(child.getName())) {
                if (!child.delete()) {
                    throw new IOException("Unable to remove an orphaned staged backup archive");
                }
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean isStagerTemporaryArchiveName(String name) {
        if (name == null || !name.startsWith(STAGED_ARCHIVE_PREFIX)
                || !name.endsWith(STAGED_ARCHIVE_SUFFIX)
                || name.length() <= STAGED_ARCHIVE_PREFIX.length()
                + STAGED_ARCHIVE_SUFFIX.length()) {
            return false;
        }
        int end = name.length() - STAGED_ARCHIVE_SUFFIX.length();
        for (int index = STAGED_ARCHIVE_PREFIX.length(); index < end; index++) {
            char character = name.charAt(index);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '-')) {
                return false;
            }
        }
        return true;
    }

    public static StagedArchive stage(InputStreamSource source, File privateDirectory)
            throws IOException {
        return stage(source, privateDirectory, MAX_ARCHIVE_BYTES,
                MAX_ENTRY_BYTES, MAX_EXPANDED_BYTES);
    }

    // Package-private limit seam keeps boundary tests small and deterministic.
    static StagedArchive stage(InputStreamSource source, File privateDirectory,
                               long maxArchiveBytes, long maxEntryBytes,
                               long maxExpandedBytes) throws IOException {
        if (source == null || privateDirectory == null) {
            throw new IllegalArgumentException("Archive source and staging directory are required");
        }
        if (!privateDirectory.isDirectory()) {
            throw new IOException("Private archive staging directory is unavailable");
        }
        if (maxArchiveBytes < 1 || maxEntryBytes < 1 || maxExpandedBytes < 1) {
            throw new IllegalArgumentException("Archive limits must be positive");
        }

        File stagedFile = File.createTempFile(STAGED_ARCHIVE_PREFIX,
                STAGED_ARCHIVE_SUFFIX, privateDirectory);
        boolean complete = false;
        Throwable failure = null;
        try {
            InputStream opened = source.open();
            if (opened == null) {
                throw new IOException("Unable to open backup archive");
            }
            try (InputStream input = opened;
                 FileOutputStream output = new FileOutputStream(stagedFile, false)) {
                byte[] buffer = new byte[8192];
                long copied = 0;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    if (count > maxArchiveBytes - copied) {
                        throw new IOException("Backup archive exceeds the compressed size limit");
                    }
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
            }

            validateArchive(stagedFile, maxEntryBytes, maxExpandedBytes);
            StagedArchive result = new StagedArchive(stagedFile, maxEntryBytes);
            complete = true;
            return result;
        } catch (IOException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            if (!complete && stagedFile.exists() && !stagedFile.delete()) {
                IOException cleanupFailure = new IOException(
                        "Unable to remove invalid staged backup archive");
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void validateArchive(File archive, long maxEntryBytes,
                                        long maxExpandedBytes) throws IOException {
        preflightCentralDirectory(archive);
        int centralCount;
        RequiredEntries centralRequired = new RequiredEntries();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            long totalExpanded = 0;
            centralCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                centralCount++;
                EntryVerification verification = verifyEntry(zip, entry, maxEntryBytes,
                        maxExpandedBytes - totalExpanded);
                centralRequired.record(entry.getName(), verification.digest);
                totalExpanded += verification.expandedBytes;
            }
        } catch (IllegalArgumentException malformedArchive) {
            throw new IOException("Backup archive directory is invalid", malformedArchive);
        }
        centralRequired.requireAll();

        int localCount = 0;
        RequiredEntries localRequired = new RequiredEntries();
        long totalExpanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                localCount++;
                boolean required = isRequiredEntry(entry.getName());
                MessageDigest digest = required ? newSha256() : null;
                long entryExpanded = 0;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    entryExpanded = addBounded(entryExpanded, count, maxEntryBytes,
                            "Backup entry exceeds the expanded size limit");
                    totalExpanded = addBounded(totalExpanded, count, maxExpandedBytes,
                            "Backup archive exceeds the total expanded size limit");
                    if (digest != null) {
                        digest.update(buffer, 0, count);
                    }
                }
                // ZipInputStream checks local-header size and CRC when the entry is closed.
                zip.closeEntry();
                localRequired.record(entry.getName(), digest == null ? null : digest.digest());
            }
        }
        localRequired.requireAll();
        if (localCount != centralCount) {
            throw new IOException("Backup archive local and central entry counts differ");
        }
        centralRequired.requireMatchingPayloads(localRequired);
    }

    /**
     * Strictly checks EOCD and central-directory bounds/counts before ZipFile can allocate or
     * enumerate entries. ZIP64 and split/multi-disk archives are deliberately unsupported.
     */
    private static void preflightCentralDirectory(File archive) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            long length = input.length();
            if (length < EOCD_FIXED_BYTES) {
                throw new IOException("Backup archive end-of-central-directory record is missing");
            }

            long earliestCandidate = Math.max(0L,
                    length - EOCD_FIXED_BYTES - EOCD_MAX_COMMENT_BYTES);
            IOException candidateFailure = null;
            for (long offset = length - EOCD_FIXED_BYTES;
                 offset >= earliestCandidate; offset--) {
                if (readUnsignedIntAt(input, offset) != EOCD_SIGNATURE) {
                    continue;
                }
                int commentLength = readUnsignedShortAt(input, offset + 20);
                if (offset + EOCD_FIXED_BYTES + commentLength != length) {
                    continue;
                }
                try {
                    validateCentralDirectoryAt(input, offset);
                    return;
                } catch (IOException invalidCandidate) {
                    // EOCD-like bytes may occur inside the comment. Keep scanning for the
                    // candidate whose directory bounds and records are internally consistent.
                    candidateFailure = invalidCandidate;
                }
            }
            if (candidateFailure != null) {
                throw candidateFailure;
            }
            throw new IOException("Backup archive end-of-central-directory record is invalid");
        }
    }

    private static void validateCentralDirectoryAt(RandomAccessFile input, long eocdOffset)
            throws IOException {
        int diskNumber = readUnsignedShortAt(input, eocdOffset + 4);
        int directoryDisk = readUnsignedShortAt(input, eocdOffset + 6);
        int entriesOnDisk = readUnsignedShortAt(input, eocdOffset + 8);
        int entriesTotal = readUnsignedShortAt(input, eocdOffset + 10);
        long directorySize = readUnsignedIntAt(input, eocdOffset + 12);
        long directoryOffset = readUnsignedIntAt(input, eocdOffset + 16);

        if (eocdOffset >= 20
                && readUnsignedIntAt(input, eocdOffset - 20) == ZIP64_LOCATOR_SIGNATURE) {
            throw new IOException("ZIP64 backup archives are not supported");
        }
        if (entriesOnDisk == ZIP16_SENTINEL || entriesTotal == ZIP16_SENTINEL
                || directorySize == ZIP32_SENTINEL || directoryOffset == ZIP32_SENTINEL) {
            throw new IOException("ZIP64 backup archives are not supported");
        }
        if (diskNumber != 0 || directoryDisk != 0 || entriesOnDisk != entriesTotal) {
            throw new IOException("Multi-disk backup archives are not supported");
        }
        if (entriesTotal > MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Backup archive exceeds the central-directory entry count limit");
        }

        if (directoryOffset > eocdOffset || directorySize > eocdOffset - directoryOffset
                || directoryOffset + directorySize != eocdOffset) {
            throw new IOException("Backup archive central-directory bounds are invalid");
        }

        long cursor = directoryOffset;
        long directoryEnd = eocdOffset;
        int parsedEntries = 0;
        while (cursor < directoryEnd) {
            if (directoryEnd - cursor < 46) {
                throw new IOException("Backup archive central-directory record is truncated");
            }
            if (readUnsignedIntAt(input, cursor) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw new IOException("Backup archive central-directory record is invalid");
            }

            long compressedSize = readUnsignedIntAt(input, cursor + 20);
            long expandedSize = readUnsignedIntAt(input, cursor + 24);
            int nameLength = readUnsignedShortAt(input, cursor + 28);
            int extraLength = readUnsignedShortAt(input, cursor + 30);
            int commentLength = readUnsignedShortAt(input, cursor + 32);
            int startDisk = readUnsignedShortAt(input, cursor + 34);
            long localHeaderOffset = readUnsignedIntAt(input, cursor + 42);
            if (compressedSize == ZIP32_SENTINEL || expandedSize == ZIP32_SENTINEL
                    || localHeaderOffset == ZIP32_SENTINEL || startDisk == ZIP16_SENTINEL) {
                throw new IOException("ZIP64 backup archives are not supported");
            }
            if (startDisk != 0) {
                throw new IOException("Multi-disk backup archives are not supported");
            }

            long recordLength = 46L + nameLength + extraLength + commentLength;
            if (recordLength > directoryEnd - cursor) {
                throw new IOException("Backup archive central-directory record is truncated");
            }
            validateExtraFields(input, cursor + 46 + nameLength, extraLength);
            if (localHeaderOffset >= directoryOffset) {
                throw new IOException("Backup archive local-header offset is invalid");
            }

            cursor += recordLength;
            parsedEntries++;
            if (parsedEntries > MAX_ARCHIVE_ENTRIES) {
                throw new IOException(
                        "Backup archive exceeds the central-directory entry count limit");
            }
        }
        if (cursor != directoryEnd || parsedEntries != entriesTotal) {
            throw new IOException("Backup archive central-directory entry count is inconsistent");
        }
    }

    private static void validateExtraFields(RandomAccessFile input, long offset, int length)
            throws IOException {
        long cursor = offset;
        long end = offset + length;
        while (cursor < end) {
            if (end - cursor < 4) {
                throw new IOException("Backup archive extra field is malformed");
            }
            int identifier = readUnsignedShortAt(input, cursor);
            int fieldLength = readUnsignedShortAt(input, cursor + 2);
            if (identifier == 0x0001) {
                throw new IOException("ZIP64 backup archives are not supported");
            }
            if (fieldLength > end - cursor - 4) {
                throw new IOException("Backup archive extra field is malformed");
            }
            cursor += 4L + fieldLength;
        }
    }

    private static int readUnsignedShortAt(RandomAccessFile input, long offset)
            throws IOException {
        input.seek(offset);
        return input.readUnsignedByte() | (input.readUnsignedByte() << 8);
    }

    private static long readUnsignedIntAt(RandomAccessFile input, long offset) throws IOException {
        input.seek(offset);
        return ((long) input.readUnsignedByte())
                | ((long) input.readUnsignedByte() << 8)
                | ((long) input.readUnsignedByte() << 16)
                | ((long) input.readUnsignedByte() << 24);
    }

    private static EntryVerification verifyEntry(ZipFile zip, ZipEntry entry, long maxEntryBytes,
                                                long remainingExpandedBytes) throws IOException {
        if (remainingExpandedBytes < 0) {
            throw new IOException("Backup archive exceeds the total expanded size limit");
        }
        CRC32 crc = new CRC32();
        MessageDigest digest = isRequiredEntry(entry.getName()) ? newSha256() : null;
        long expanded = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = zip.getInputStream(entry)) {
            if (input == null) {
                throw new IOException("Backup archive entry cannot be opened: " + entry.getName());
            }
            int count;
            while ((count = input.read(buffer)) != -1) {
                expanded = addBounded(expanded, count, maxEntryBytes,
                        "Backup entry exceeds the expanded size limit");
                if (count > remainingExpandedBytes - (expanded - count)) {
                    throw new IOException("Backup archive exceeds the total expanded size limit");
                }
                crc.update(buffer, 0, count);
                if (digest != null) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        if (entry.getSize() >= 0 && entry.getSize() != expanded) {
            throw new IOException("Backup archive entry size does not match its directory record");
        }
        if (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue()) {
            throw new IOException("Backup archive entry checksum is invalid: " + entry.getName());
        }
        return new EntryVerification(expanded, digest == null ? null : digest.digest());
    }

    private static boolean isRequiredEntry(String name) {
        return DATABASE_ENTRY.equals(name) || PREFERENCES_ENTRY.equals(name)
                || CONFIG_ENTRY.equals(name);
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable for backup validation", unavailable);
        }
    }

    private static long addBounded(long total, int count, long maximum, String message)
            throws IOException {
        if (count > maximum - total) {
            throw new IOException(message);
        }
        return total + count;
    }

    private static final class RequiredEntries {
        private int database;
        private int preferences;
        private int config;

        private byte[] databaseDigest;
        private byte[] preferencesDigest;
        private byte[] configDigest;

        private void record(String name, byte[] digest) throws IOException {
            if (DATABASE_ENTRY.equals(name) && ++database > 1) {
                throw new IOException("Backup contains duplicate entry: " + DATABASE_ENTRY);
            } else if (DATABASE_ENTRY.equals(name)) {
                databaseDigest = digest;
            }
            if (PREFERENCES_ENTRY.equals(name) && ++preferences > 1) {
                throw new IOException("Backup contains duplicate entry: " + PREFERENCES_ENTRY);
            } else if (PREFERENCES_ENTRY.equals(name)) {
                preferencesDigest = digest;
            }
            if (CONFIG_ENTRY.equals(name) && ++config > 1) {
                throw new IOException("Backup contains duplicate entry: " + CONFIG_ENTRY);
            } else if (CONFIG_ENTRY.equals(name)) {
                configDigest = digest;
            }
        }

        private void requireAll() throws IOException {
            if (database != 1 || preferences != 1 || config != 1
                    || databaseDigest == null || preferencesDigest == null || configDigest == null) {
                throw new IOException("Backup is missing a required entry");
            }
        }

        private void requireMatchingPayloads(RequiredEntries other) throws IOException {
            if (!MessageDigest.isEqual(databaseDigest, other.databaseDigest)
                    || !MessageDigest.isEqual(preferencesDigest, other.preferencesDigest)
                    || !MessageDigest.isEqual(configDigest, other.configDigest)) {
                throw new IOException("Backup local headers and directory identify different data");
            }
        }
    }

    private static final class EntryVerification {
        private final long expandedBytes;
        private final byte[] digest;

        private EntryVerification(long expandedBytes, byte[] digest) {
            this.expandedBytes = expandedBytes;
            this.digest = digest;
        }
    }

    public static final class StagedArchive implements AutoCloseable {
        private final File archive;
        private final long maxEntryBytes;
        private boolean closed;

        private StagedArchive(File archive, long maxEntryBytes) {
            this.archive = archive;
            this.maxEntryBytes = maxEntryBytes;
        }

        public synchronized boolean hasEntry(String name) throws IOException {
            if (closed) {
                throw new IOException("Staged backup archive is closed");
            }
            try (ZipFile zip = new ZipFile(archive)) {
                return zip.getEntry(name) != null;
            }
        }

        /** Reads a validated entry from the private snapshot without exposing its backing file. */
        public synchronized byte[] readEntry(String name) throws IOException {
            if (closed) {
                throw new IOException("Staged backup archive is closed");
            }
            try (ZipFile zip = new ZipFile(archive)) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    throw new IOException("Backup entry is missing: " + name);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        entry.getSize() > 0 && entry.getSize() <= maxEntryBytes
                                ? (int) entry.getSize() : 1024);
                CRC32 crc = new CRC32();
                byte[] buffer = new byte[8192];
                long total = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total = addBounded(total, count, maxEntryBytes,
                                "Backup entry exceeds the expanded size limit");
                        output.write(buffer, 0, count);
                        crc.update(buffer, 0, count);
                    }
                }
                if ((entry.getSize() >= 0 && entry.getSize() != total)
                        || (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())) {
                    throw new IOException("Backup archive entry changed or failed validation: " + name);
                }
                return output.toByteArray();
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            if (archive.exists() && !archive.delete()) {
                throw new IOException("Unable to remove staged backup archive");
            }
            closed = true;
        }
    }
}
