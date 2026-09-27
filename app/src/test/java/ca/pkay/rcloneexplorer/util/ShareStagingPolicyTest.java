package ca.pkay.rcloneexplorer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ShareStagingPolicyTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void duplicateAndCaseCollidingNamesAreDeterministicAndNeverOverwrite() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        ShareStagingPolicy.StagedShare staged = ShareStagingPolicy.stage(cache, Arrays.asList(
                source("report.txt", bytes("first")),
                source("REPORT.txt", bytes("second")),
                source("report.txt", bytes("third"))
        ), limits(32, 64, 8, 32));

        List<File> files = staged.getFiles();
        assertEquals(3, files.size());
        assertEquals("report.txt", files.get(0).getName());
        assertEquals("REPORT (2).txt", files.get(1).getName());
        assertEquals("report (3).txt", files.get(2).getName());
        assertArrayEquals(bytes("first"), Files.readAllBytes(files.get(0).toPath()));
        assertArrayEquals(bytes("second"), Files.readAllBytes(files.get(1).toPath()));
        assertArrayEquals(bytes("third"), Files.readAllBytes(files.get(2).toPath()));

        try {
            files.add(new File("not-owned"));
            fail("Staged file list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        File sibling = new File(cache, "unrelated.txt");
        Files.write(sibling.toPath(), bytes("preserve"));
        File invocation = staged.getDirectory();
        staged.cleanup();
        staged.cleanup();
        assertFalse(invocation.exists());
        assertTrue(sibling.isFile());
        assertArrayEquals(bytes("preserve"), Files.readAllBytes(sibling.toPath()));
    }

    @Test
    public void traversalAndPlatformPathNamesAreReducedToSafeBasenames() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        ShareStagingPolicy.StagedShare staged = ShareStagingPolicy.stage(cache, Arrays.asList(
                source("../../escape.txt", bytes("one")),
                source("C:\\private\\secret.txt", bytes("two")),
                source("..", bytes("three")),
                source("/../", bytes("four"))
        ), limits(32, 128, 8, 32));

        assertEquals("escape.txt", staged.getFiles().get(0).getName());
        assertEquals("secret.txt", staged.getFiles().get(1).getName());
        assertEquals("shared-file", staged.getFiles().get(2).getName());
        for (File file : staged.getFiles()) {
            assertEquals(staged.getDirectory().getCanonicalFile(),
                    file.getCanonicalFile().getParentFile());
            assertFalse(file.getName().contains("/"));
            assertFalse(file.getName().contains("\\"));
        }
        assertFalse(new File(staged.getDirectory(), "../../escape.txt").getCanonicalFile().exists());
        staged.cleanup();
    }

    @Test
    public void countsUnknownSizeStreamInsteadOfTrustingAvailable() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        ShareStagingPolicy.Limits small = limits(4, 8, 8, 32);
        ShareStagingPolicy.StagedShare exact = ShareStagingPolicy.stage(cache,
                Arrays.asList(new ShareStagingPolicy.Source("exact.bin", () ->
                        unknownSizeStream(bytes("1234")))), small);
        assertEquals(4L, exact.getFiles().get(0).length());
        exact.cleanup();

        try {
            ShareStagingPolicy.stage(cache, Arrays.asList(
                    new ShareStagingPolicy.Source("too-large.bin", () ->
                            unknownSizeStream(bytes("12345")))), small);
            fail("Expected the stream to be rejected at the per-file limit");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("per-file"));
        }
        assertEquals("Failed staging must remove its invocation directory", 0,
                cache.listFiles().length);
    }

    @Test
    public void enforcesAggregateLimitAcrossIndividuallyValidUnknownSizeStreams() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        try {
            ShareStagingPolicy.stage(cache, Arrays.asList(
                    new ShareStagingPolicy.Source("first.bin", () ->
                            unknownSizeStream(bytes("1234"))),
                    new ShareStagingPolicy.Source("second.bin", () ->
                            unknownSizeStream(bytes("5678")))
            ), limits(5, 7, 8, 32));
            fail("Expected the aggregate stream size to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("aggregate"));
        }
        assertEquals(0, cache.listFiles().length);
    }

    @Test
    public void removesOnlyInvocationArtifactsAfterMidCopyFailure() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File sentinel = new File(cache, "user-data.txt");
        Files.write(sentinel.toPath(), bytes("keep"));

        InputStreamFactoryWithFailure failing = new InputStreamFactoryWithFailure();
        try {
            ShareStagingPolicy.stage(cache, Arrays.asList(
                    new ShareStagingPolicy.Source("interrupted.bin", failing::open)
            ), limits(32, 32, 8, 32));
            fail("Expected the synthetic source failure");
        } catch (IOException expected) {
            assertEquals("synthetic mid-copy failure", expected.getMessage());
        }

        assertTrue(sentinel.isFile());
        assertArrayEquals(bytes("keep"), Files.readAllBytes(sentinel.toPath()));
        assertEquals("The invocation directory and partial file must be removed", 1,
                cache.listFiles().length);
    }

    @Test
    public void cancellationDuringStreamingRemovesTheWholeUnpublishedShare() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            ShareStagingPolicy.stage(cache, Arrays.asList(
                    new ShareStagingPolicy.Source("cancelled.bin", () ->
                            new ByteArrayInputStream(bytes("partial")) {
                                @Override
                                public synchronized int read(byte[] buffer, int offset, int length) {
                                    int count = super.read(buffer, offset, length);
                                    if (count > 0) cancelled.set(true);
                                    return count;
                                }
                            })
            ), limits(32, 32, 8, 32), cancelled::get);
            fail("Expected cancelled staging to stop before publishing the file");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        }
        assertEquals("Cancelled staging must remove its private directory", 0,
                cache.listFiles().length);
    }

    @Test
    public void enforcesFileCountAndUnicodeCodePointNameLimit() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        try {
            ShareStagingPolicy.stage(cache, Arrays.asList(
                    source("one", bytes("1")), source("two", bytes("2"))
            ), limits(8, 8, 1, 8));
            fail("Expected the file count to be rejected before staging");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("file"));
        }
        assertEquals(0, cache.listFiles().length);

        ShareStagingPolicy.StagedShare staged = ShareStagingPolicy.stage(cache, Arrays.asList(
                source("😀😀😀😀.txt", bytes("a")),
                source("😀😀😀😀.txt", bytes("b"))
        ), limits(8, 8, 8, 8));
        for (File file : staged.getFiles()) {
            assertTrue(file.getName().codePointCount(0, file.getName().length()) <= 8);
        }
        assertNotEquals(staged.getFiles().get(0).getName(), staged.getFiles().get(1).getName());
        staged.cleanup();
    }

    @Test
    public void truncatesMultibyteNamesToTheFilesystemComponentByteLimit() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        StringBuilder supplementaryCharacters = new StringBuilder();
        for (int i = 0; i < ShareStagingPolicy.HARD_MAX_FILENAME_CODE_POINTS; i++) {
            supplementaryCharacters.append("\uD83D\uDE00");
        }
        String longName = supplementaryCharacters + ".txt";
        ShareStagingPolicy.StagedShare staged = ShareStagingPolicy.stage(cache, Arrays.asList(
                source(longName, bytes("first")),
                source(longName, bytes("second"))
        ), limits(16, 32, 4,
                ShareStagingPolicy.HARD_MAX_FILENAME_CODE_POINTS));

        for (File file : staged.getFiles()) {
            assertTrue(file.getName().getBytes(StandardCharsets.UTF_8).length <=
                    ShareStagingPolicy.HARD_MAX_FILENAME_UTF8_BYTES);
            assertTrue(file.getName().codePointCount(0, file.getName().length()) <=
                    ShareStagingPolicy.HARD_MAX_FILENAME_CODE_POINTS);
            assertTrue(file.getName().endsWith(".txt"));
        }
        assertTrue(staged.getFiles().get(1).getName().contains(" (2).txt"));
        staged.cleanup();
    }

    @Test
    public void injectableLimitsCannotExceedHardCeilings() {
        try {
            new ShareStagingPolicy.Limits(
                    ShareStagingPolicy.HARD_MAX_BYTES_PER_FILE + 1,
                    ShareStagingPolicy.HARD_MAX_TOTAL_BYTES,
                    ShareStagingPolicy.HARD_MAX_FILES,
                    ShareStagingPolicy.HARD_MAX_FILENAME_CODE_POINTS
            );
            fail("Expected the hard per-file ceiling to be immutable");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("hard limit"));
        }
    }

    @Test
    public void rejectsAnEmptyShareWithoutCreatingAStagingDirectory() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        try {
            ShareStagingPolicy.stage(cache, Arrays.asList(), limits(8, 8, 8, 8));
            fail("Expected an empty share to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("no files"));
        }
        assertEquals(0, cache.listFiles().length);
    }

    @Test
    public void workerCleanupRemovesOnlyItsStagedFileAndLastWorkerRemovesDirectory()
            throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File unrelated = new File(cache, "unrelated.txt");
        Files.write(unrelated.toPath(), bytes("keep"));
        ShareStagingPolicy.StagedShare staged = ShareStagingPolicy.stage(cache, Arrays.asList(
                source("one.txt", bytes("one")), source("two.txt", bytes("two"))
        ), limits(16, 32, 8, 32));

        File invocationDirectory = staged.getDirectory();
        ShareStagingPolicy.cleanupUploadedFile(cache, staged.getFiles().get(0));
        assertTrue(invocationDirectory.isDirectory());
        assertFalse(staged.getFiles().get(0).exists());
        assertTrue(staged.getFiles().get(1).isFile());

        ShareStagingPolicy.cleanupUploadedFile(cache, staged.getFiles().get(1));
        assertFalse(invocationDirectory.exists());
        assertTrue(unrelated.isFile());
        assertArrayEquals(bytes("keep"), Files.readAllBytes(unrelated.toPath()));
    }

    @Test
    public void workerCleanupRejectsUserCacheFilesAndPreservesThem() throws Exception {
        File cache = temporaryFolder.newFolder("cache");
        File userFile = new File(cache, "user-file.txt");
        Files.write(userFile.toPath(), bytes("keep"));
        try {
            ShareStagingPolicy.cleanupUploadedFile(cache, userFile);
            fail("Expected cleanup to reject a file outside a generated share directory");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("escaped"));
        }
        assertTrue(userFile.isFile());
        assertArrayEquals(bytes("keep"), Files.readAllBytes(userFile.toPath()));
    }

    private static ShareStagingPolicy.Limits limits(long perFile, long total, int files,
                                                    int codePoints) {
        return new ShareStagingPolicy.Limits(perFile, total, files, codePoints);
    }

    private static ShareStagingPolicy.Source source(String name, byte[] contents) {
        return new ShareStagingPolicy.Source(name, () -> new ByteArrayInputStream(contents));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static InputStream unknownSizeStream(byte[] contents) {
        return new ByteArrayInputStream(contents) {
            @Override
            public int available() {
                return 0;
            }
        };
    }

    private static final class InputStreamFactoryWithFailure {
        private InputStream open() {
            return new InputStream() {
                private boolean delivered;

                @Override
                public int read() throws IOException {
                    throw new IOException("synthetic mid-copy failure");
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (delivered) {
                        throw new IOException("synthetic mid-copy failure");
                    }
                    delivered = true;
                    byte[] initial = bytes("partial");
                    int count = Math.min(length, initial.length);
                    System.arraycopy(initial, 0, buffer, offset, count);
                    return count;
                }
            };
        }
    }
}
