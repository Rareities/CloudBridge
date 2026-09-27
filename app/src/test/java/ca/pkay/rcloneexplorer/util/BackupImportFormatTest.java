package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupImportFormatTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recognizesExportShapedDeflatedZipAndRestoresAllPeekedBytes() throws Exception {
        byte[] exportedZip = exportedZip();
        // ZipOutputStream's default DEFLATED entries use a data descriptor when sizes are unknown.
        assertTrue((exportedZip[6] & 0x08) != 0);

        try (BackupImportFormat.DetectedInput detected =
                     BackupImportFormat.inspect(new ByteArrayInputStream(exportedZip))) {
            assertTrue(detected.isZipArchive());
            assertArrayEquals(exportedZip, readFully(detected.getStream()));
        }
    }

    @Test
    public void sniffThenStageUsesTheSameProviderStreamExactlyOnce() throws Exception {
        byte[] exportedZip = exportedZip();
        final int[] openCount = {0};
        BackupArchiveStager.InputStreamSource provider = () -> {
            openCount[0]++;
            return new ByteArrayInputStream(exportedZip);
        };

        try (BackupImportFormat.DetectedInput detected =
                     BackupImportFormat.inspect(provider.open())) {
            assertTrue(detected.isZipArchive());
            try (BackupArchiveStager.StagedArchive archive = BackupArchiveStager.stage(
                    detected::getStream, temporaryFolder.getRoot())) {
                assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),
                        archive.readEntry(BackupArchiveStager.DATABASE_ENTRY));
            }
        }
        assertEquals(1, openCount[0]);
    }

    @Test
    public void rawConfigValidationCanConsumeTheInspectedNonRepeatableStream() throws Exception {
        byte[] inspectedConfig = "[first]\ntype = local\n"
                .getBytes(StandardCharsets.UTF_8);
        final int[] openCount = {0};
        BackupArchiveStager.InputStreamSource oneShotProvider = () -> {
            if (++openCount[0] != 1) {
                throw new IOException("provider stream can only be opened once");
            }
            return new ByteArrayInputStream(inspectedConfig);
        };

        try (BackupImportFormat.DetectedInput detected =
                     BackupImportFormat.inspect(oneShotProvider.open())) {
            assertFalse(detected.isZipArchive());
            // MainActivity passes this exact stream into Rclone.copyConfigFile(InputStream).
            assertArrayEquals(inspectedConfig, readFully(detected.getStream()));
        }
        assertEquals(1, openCount[0]);
    }

    @Test
    public void recognizesAnEmptyZipSignatureButLeavesFullValidationToTheStager() throws Exception {
        byte[] emptyZip = new byte[]{'P', 'K', 5, 6};
        try (BackupImportFormat.DetectedInput detected =
                     BackupImportFormat.inspect(new ByteArrayInputStream(emptyZip))) {
            assertTrue(detected.isZipArchive());
            assertArrayEquals(emptyZip, readFully(detected.getStream()));
        }
    }

    @Test
    public void rawConfigAndArbitraryMimeIndependentBytesRemainRawAndUnchanged() throws Exception {
        byte[] rawConfig = "[remote]\ntype = drive\ntoken = {}\n"
                .getBytes(StandardCharsets.UTF_8);
        try (BackupImportFormat.DetectedInput detected =
                     BackupImportFormat.inspect(new ByteArrayInputStream(rawConfig))) {
            assertFalse(detected.isZipArchive());
            assertArrayEquals(rawConfig, readFully(detected.getStream()));
        }
    }

    @Test
    public void incompleteOrNonZipPrefixIsNotClassifiedAsZipAndIsRestored() throws Exception {
        for (byte[] content : new byte[][]{
                new byte[0],
                new byte[]{'P'},
                new byte[]{'P', 'K', 3},
                new byte[]{'P', 'K', 0, 0}
        }) {
            try (BackupImportFormat.DetectedInput detected =
                         BackupImportFormat.inspect(new ByteArrayInputStream(content))) {
                assertFalse(detected.isZipArchive());
                assertArrayEquals(content, readFully(detected.getStream()));
            }
        }
    }

    @Test
    public void closesSourceWhenPrefixReadFails() {
        final boolean[] closed = {false};
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read failed");
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        try {
            BackupImportFormat.inspect(failing);
        } catch (IOException expected) {
            assertTrue(closed[0]);
            return;
        }
        throw new AssertionError("Expected prefix read to fail");
    }

    private static byte[] exportedZip() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addEntry(zip, BackupArchiveStager.DATABASE_ENTRY, "{}");
            addEntry(zip, BackupArchiveStager.PREFERENCES_ENTRY, "{}");
            addEntry(zip, BackupArchiveStager.CONFIG_ENTRY, "[remote]\ntype = drive\n");
        }
        return output.toByteArray();
    }

    private static void addEntry(ZipOutputStream zip, String name, String contents)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
