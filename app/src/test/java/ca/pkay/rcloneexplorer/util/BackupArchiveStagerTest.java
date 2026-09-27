package ca.pkay.rcloneexplorer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupArchiveStagerTest {
    private static final byte[] EMPTY = new byte[0];

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sourceIsOpenedOnceAndSnapshotIsRemovedOnClose() throws Exception {
        byte[] database = bytes("database snapshot");
        byte[] archiveBytes = requiredArchive(database, bytes("preferences"), bytes("config"));
        final int[] openCount = {0};

        BackupArchiveStager.StagedArchive archive = BackupArchiveStager.stage(() -> {
            openCount[0]++;
            return new ByteArrayInputStream(archiveBytes);
        }, temporaryFolder.getRoot());

        assertEquals(1, openCount[0]);
        int sourcePayload = indexOf(archiveBytes, database);
        assertTrue(sourcePayload >= 0);
        archiveBytes[sourcePayload] ^= 0x01;
        assertArrayEquals(database, archive.readEntry(BackupArchiveStager.DATABASE_ENTRY));
        assertArrayEquals(bytes("preferences"),
                archive.readEntry(BackupArchiveStager.PREFERENCES_ENTRY));
        assertArrayEquals(bytes("config"), archive.readEntry(BackupArchiveStager.CONFIG_ENTRY));
        assertEquals(1, temporaryFolder.getRoot().list().length);

        archive.close();
        archive.close();
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void normalDeflatedZipWithCommentIsAccepted() throws Exception {
        byte[] normalArchive = deflatedArchiveWithComment(
                bytes("database"), bytes("preferences"), bytes("config"));

        BackupArchiveStager.StagedArchive staged = stage(normalArchive);
        assertArrayEquals(bytes("database"),
                staged.readEntry(BackupArchiveStager.DATABASE_ENTRY));
        assertArrayEquals(bytes("preferences"),
                staged.readEntry(BackupArchiveStager.PREFERENCES_ENTRY));
        assertArrayEquals(bytes("config"), staged.readEntry(BackupArchiveStager.CONFIG_ENTRY));
        staged.close();
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void centralEntryCountIsRejectedDuringPreflight() throws Exception {
        Entry[] atLimit = new Entry[BackupArchiveStager.MAX_ARCHIVE_ENTRIES];
        atLimit[0] = entry(BackupArchiveStager.DATABASE_ENTRY, EMPTY);
        atLimit[1] = entry(BackupArchiveStager.PREFERENCES_ENTRY, EMPTY);
        atLimit[2] = entry(BackupArchiveStager.CONFIG_ENTRY, EMPTY);
        for (int index = 3; index < atLimit.length; index++) {
            atLimit[index] = entry("tiny-" + index, EMPTY);
        }
        BackupArchiveStager.StagedArchive accepted = stage(storedZip(atLimit));
        accepted.close();

        Entry[] entries = new Entry[BackupArchiveStager.MAX_ARCHIVE_ENTRIES + 1];
        entries[0] = entry(BackupArchiveStager.DATABASE_ENTRY, EMPTY);
        entries[1] = entry(BackupArchiveStager.PREFERENCES_ENTRY, EMPTY);
        entries[2] = entry(BackupArchiveStager.CONFIG_ENTRY, EMPTY);
        for (int index = 3; index < entries.length; index++) {
            entries[index] = entry("tiny-" + index, EMPTY);
        }
        byte[] manyTinyEntries = storedZip(entries);

        expectIOExceptionContaining(() -> stage(manyTinyEntries), "entry count limit");

        byte[] understatedCount = manyTinyEntries.clone();
        int eocd = eocdOffset(understatedCount);
        understatedCount[eocd + 8] = 1;
        understatedCount[eocd + 9] = 0;
        understatedCount[eocd + 10] = 1;
        understatedCount[eocd + 11] = 0;
        expectIOExceptionContaining(() -> stage(understatedCount), "entry count limit");
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void zip64AndMultiDiskArchivesAreExplicitlyRejected() throws Exception {
        byte[] zip64 = requiredArchive(EMPTY, EMPTY, EMPTY);
        int eocd = eocdOffset(zip64);
        zip64[eocd + 10] = (byte) 0xff;
        zip64[eocd + 11] = (byte) 0xff;
        expectIOExceptionContaining(() -> stage(zip64), "ZIP64");

        byte[] zip64Locator = requiredArchive(EMPTY, EMPTY, EMPTY);
        eocd = eocdOffset(zip64Locator);
        byte[] withLocator = new byte[zip64Locator.length + 20];
        System.arraycopy(zip64Locator, 0, withLocator, 0, eocd);
        withLocator[eocd] = 0x50;
        withLocator[eocd + 1] = 0x4b;
        withLocator[eocd + 2] = 0x06;
        withLocator[eocd + 3] = 0x07;
        System.arraycopy(zip64Locator, eocd, withLocator, eocd + 20,
                zip64Locator.length - eocd);
        expectIOExceptionContaining(() -> stage(withLocator), "ZIP64");

        byte[] multiDisk = requiredArchive(EMPTY, EMPTY, EMPTY);
        eocd = eocdOffset(multiDisk);
        multiDisk[eocd + 4] = 1;
        expectIOExceptionContaining(() -> stage(multiDisk), "Multi-disk");
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void startupCleanupDeletesOnlyExactStagerNamesAndIsBounded() throws Exception {
        File directory = temporaryFolder.getRoot();
        for (int index = 0;
             index < BackupArchiveStager.MAX_ORPHAN_ARCHIVES_PER_CLEANUP + 2; index++) {
            new File(directory, "backup-import-orphan" + index + ".zip").createNewFile();
        }
        File unrelatedPrefix = new File(directory, "old-backup-import-orphan.zip");
        File wrongSuffix = new File(directory, "backup-import-orphan.tmp");
        File invalidGeneratedName = new File(directory, "backup-import-orphan name.zip");
        File nestedDirectory = new File(directory, "backup-import-directory.zip");
        assertTrue(unrelatedPrefix.createNewFile());
        assertTrue(wrongSuffix.createNewFile());
        assertTrue(invalidGeneratedName.createNewFile());
        assertTrue(nestedDirectory.mkdir());

        assertEquals(BackupArchiveStager.MAX_ORPHAN_ARCHIVES_PER_CLEANUP,
                BackupArchiveStager.cleanupOrphanedArchives(directory));
        assertEquals(2, matchingStagerArchiveCount(directory));
        assertTrue(unrelatedPrefix.exists());
        assertTrue(wrongSuffix.exists());
        assertTrue(invalidGeneratedName.exists());
        assertTrue(nestedDirectory.exists());

        assertEquals(2, BackupArchiveStager.cleanupOrphanedArchives(directory));
        assertEquals(0, matchingStagerArchiveCount(directory));
        assertTrue(unrelatedPrefix.exists());
        assertTrue(wrongSuffix.exists());
        assertTrue(invalidGeneratedName.exists());
        assertTrue(nestedDirectory.exists());
    }

    @Test
    public void duplicateRequiredEntryIsRejectedAndTemporaryArchiveRemoved() throws Exception {
        byte[] duplicate = storedZip(
                entry(BackupArchiveStager.DATABASE_ENTRY, bytes("first")),
                entry(BackupArchiveStager.DATABASE_ENTRY, bytes("second")),
                entry(BackupArchiveStager.PREFERENCES_ENTRY, bytes("preferences")),
                entry(BackupArchiveStager.CONFIG_ENTRY, bytes("config")));

        expectIOException(() -> stage(duplicate));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void missingRequiredEntryIsRejected() throws Exception {
        byte[] missing = storedZip(
                entry(BackupArchiveStager.DATABASE_ENTRY, bytes("database")),
                entry(BackupArchiveStager.PREFERENCES_ENTRY, bytes("preferences")));

        expectIOException(() -> stage(missing));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void payloadCorruptionAndTruncatedDirectoryAreRejected() throws Exception {
        byte[] valid = requiredArchive(bytes("database-corruption-canary"),
                bytes("preferences"), bytes("config"));
        byte[] corrupted = valid.clone();
        int payloadOffset = indexOf(corrupted, bytes("database-corruption-canary"));
        assertTrue(payloadOffset >= 0);
        corrupted[payloadOffset] ^= 0x01;

        expectIOException(() -> stage(corrupted));
        assertEquals(0, temporaryFolder.getRoot().list().length);

        byte[] truncated = Arrays.copyOf(valid, valid.length - 7);
        expectIOException(() -> stage(truncated));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void centralDirectoryCannotRemapRequiredEntryPayloads() throws Exception {
        byte[] mismatched = requiredArchive(bytes("AAAA"), bytes("BBBB"), bytes("CCCC"));
        int databaseRecord = centralRecordOffset(mismatched, BackupArchiveStager.DATABASE_ENTRY);
        int preferencesRecord = centralRecordOffset(mismatched,
                BackupArchiveStager.PREFERENCES_ENTRY);
        assertTrue(databaseRecord >= 0);
        assertTrue(preferencesRecord >= 0);
        for (int fieldOffset : new int[]{16, 20, 24, 42}) {
            swap(mismatched, databaseRecord + fieldOffset,
                    preferencesRecord + fieldOffset, 4);
        }

        expectIOException(() -> stage(mismatched));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void compressedArchiveLimitAcceptsExactBoundaryAndRejectsOneByteOver() throws Exception {
        byte[] valid = requiredArchive(bytes("database"), bytes("preferences"), bytes("config"));
        BackupArchiveStager.StagedArchive boundary = stage(valid, valid.length,
                BackupArchiveStager.MAX_ENTRY_BYTES, BackupArchiveStager.MAX_EXPANDED_BYTES);
        boundary.close();

        expectIOException(() -> stage(valid, valid.length - 1,
                BackupArchiveStager.MAX_ENTRY_BYTES, BackupArchiveStager.MAX_EXPANDED_BYTES));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void eachExistingFourMiBEntryLimitIsInclusive() throws Exception {
        assertEquals(4L * 1024L * 1024L, BackupArchiveStager.MAX_ENTRY_BYTES);
        byte[] exact = requiredArchive(new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES],
                EMPTY, EMPTY);
        BackupArchiveStager.StagedArchive accepted = stage(exact);
        accepted.close();

        byte[] over = requiredArchive(new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES + 1],
                EMPTY, EMPTY);
        expectIOException(() -> stage(over));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void totalExpandedLimitAllowsExactlyThreeEntriesAndRejectsOneExtraByte() throws Exception {
        assertEquals(3L * BackupArchiveStager.MAX_ENTRY_BYTES,
                BackupArchiveStager.MAX_EXPANDED_BYTES);
        byte[] exact = requiredArchive(new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES],
                new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES],
                new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES]);
        BackupArchiveStager.StagedArchive accepted = stage(exact);
        accepted.close();

        byte[] over = storedZip(
                entry(BackupArchiveStager.DATABASE_ENTRY,
                        new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES]),
                entry(BackupArchiveStager.PREFERENCES_ENTRY,
                        new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES]),
                entry(BackupArchiveStager.CONFIG_ENTRY,
                        new byte[(int) BackupArchiveStager.MAX_ENTRY_BYTES]),
                entry("extra.bin", bytes("x")));
        expectIOException(() -> stage(over));
        assertEquals(0, temporaryFolder.getRoot().list().length);
    }

    @Test
    public void compressedArchiveCapHasTheExplicitProvisionalValue() {
        assertEquals(16L * 1024L * 1024L, BackupArchiveStager.MAX_ARCHIVE_BYTES);
    }

    private BackupArchiveStager.StagedArchive stage(byte[] archive) throws IOException {
        return BackupArchiveStager.stage(() -> new ByteArrayInputStream(archive),
                temporaryFolder.getRoot());
    }

    private BackupArchiveStager.StagedArchive stage(byte[] archive, long maxArchiveBytes,
                                                     long maxEntryBytes, long maxExpandedBytes)
            throws IOException {
        return BackupArchiveStager.stage(() -> new ByteArrayInputStream(archive),
                temporaryFolder.getRoot(), maxArchiveBytes, maxEntryBytes, maxExpandedBytes);
    }

    private static byte[] requiredArchive(byte[] database, byte[] preferences, byte[] config)
            throws IOException {
        return storedZip(
                entry(BackupArchiveStager.DATABASE_ENTRY, database),
                entry(BackupArchiveStager.PREFERENCES_ENTRY, preferences),
                entry(BackupArchiveStager.CONFIG_ENTRY, config));
    }

    private static byte[] deflatedArchiveWithComment(byte[] database, byte[] preferences,
                                                      byte[] config) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            putZipEntry(zip, BackupArchiveStager.DATABASE_ENTRY, database);
            putZipEntry(zip, BackupArchiveStager.PREFERENCES_ENTRY, preferences);
            putZipEntry(zip, BackupArchiveStager.CONFIG_ENTRY, config);
            zip.setComment("ordinary backup comment");
        }
        return output.toByteArray();
    }

    private static void putZipEntry(ZipOutputStream zip, String name, byte[] data)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private int matchingStagerArchiveCount(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("backup-import-")
                    && name.endsWith(".zip")
                    && name.substring("backup-import-".length(), name.length() - 4)
                    .matches("[A-Za-z0-9_-]+")) {
                count++;
            }
        }
        return count;
    }

    private static int eocdOffset(byte[] archive) {
        for (int offset = archive.length - 22; offset >= 0; offset--) {
            if (readInt(archive, offset) == 0x06054b50) {
                int commentLength = readUnsignedShort(archive, offset + 20);
                if (offset + 22 + commentLength == archive.length) {
                    return offset;
                }
            }
        }
        return -1;
    }

    private static Entry entry(String name, byte[] data) {
        return new Entry(name, data);
    }

    private static byte[] storedZip(Entry... entries) throws IOException {
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        List<byte[]> centralRecords = new ArrayList<>();
        for (Entry entry : entries) {
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.data);
            long checksum = crc.getValue();
            long offset = local.size();

            writeInt(local, 0x04034b50);
            writeShort(local, 20);
            writeShort(local, 0);
            writeShort(local, 0);
            writeShort(local, 0);
            writeShort(local, 0);
            writeInt(local, checksum);
            writeInt(local, entry.data.length);
            writeInt(local, entry.data.length);
            writeShort(local, name.length);
            writeShort(local, 0);
            local.write(name);
            local.write(entry.data);

            ByteArrayOutputStream central = new ByteArrayOutputStream();
            writeInt(central, 0x02014b50);
            writeShort(central, 20);
            writeShort(central, 20);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, checksum);
            writeInt(central, entry.data.length);
            writeInt(central, entry.data.length);
            writeShort(central, name.length);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, 0);
            writeInt(central, offset);
            central.write(name);
            centralRecords.add(central.toByteArray());
        }

        long centralOffset = local.size();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        local.writeTo(archive);
        for (byte[] record : centralRecords) {
            archive.write(record);
        }
        long centralSize = archive.size() - centralOffset;
        writeInt(archive, 0x06054b50);
        writeShort(archive, 0);
        writeShort(archive, 0);
        writeShort(archive, entries.length);
        writeShort(archive, entries.length);
        writeInt(archive, centralSize);
        writeInt(archive, centralOffset);
        writeShort(archive, 0);
        return archive.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
    }

    private static int indexOf(byte[] input, byte[] needle) {
        outer:
        for (int start = 0; start <= input.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (input[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }

    private static int centralRecordOffset(byte[] archive, String name) {
        byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= archive.length - 46; offset++) {
            if (readInt(archive, offset) != 0x02014b50) {
                continue;
            }
            int nameLength = readUnsignedShort(archive, offset + 28);
            int extraLength = readUnsignedShort(archive, offset + 30);
            int commentLength = readUnsignedShort(archive, offset + 32);
            int recordEnd = offset + 46 + nameLength + extraLength + commentLength;
            if (recordEnd <= archive.length && nameLength == encodedName.length
                    && Arrays.equals(encodedName,
                    Arrays.copyOfRange(archive, offset + 46, offset + 46 + nameLength))) {
                return offset;
            }
        }
        return -1;
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static void swap(byte[] bytes, int firstOffset, int secondOffset, int length) {
        for (int index = 0; index < length; index++) {
            byte value = bytes[firstOffset + index];
            bytes[firstOffset + index] = bytes[secondOffset + index];
            bytes[secondOffset + index] = value;
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected validation failure.
        }
    }

    private static void expectIOExceptionContaining(IoAction action, String expectedMessage)
            throws Exception {
        try {
            action.run();
            fail("Expected IOException containing: " + expectedMessage);
        } catch (IOException expected) {
            assertTrue("Unexpected IOException: " + expected.getMessage(),
                    expected.getMessage() != null
                            && expected.getMessage().contains(expectedMessage));
        }
    }

    private interface IoAction {
        void run() throws Exception;
    }

    private static final class Entry {
        private final String name;
        private final byte[] data;

        private Entry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }
}
