package ca.pkay.rcloneexplorer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BoundedFileAppenderTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resetsBeforeQueuedRecordsCanExceedByteLimit() throws Exception {
        File file = temporaryFolder.newFile("log.txt");

        assertTrue(BoundedFileAppender.append(file, bytes("abc"), 5));
        assertTrue(BoundedFileAppender.append(file, bytes("defg"), 5));

        assertArrayEquals(bytes("defg"), Files.readAllBytes(file.toPath()));
        assertTrue(file.length() <= 5);
    }

    @Test
    public void repairsExistingOversizedFileBeforeAppending() throws Exception {
        File file = temporaryFolder.newFile("oversized.txt");
        Files.write(file.toPath(), bytes("12345678"));

        assertTrue(BoundedFileAppender.append(file, bytes("ok"), 5));

        assertArrayEquals(bytes("ok"), Files.readAllBytes(file.toPath()));
        assertTrue(file.length() <= 5);
    }

    @Test
    public void refusesOversizedRecordWithoutGrowingExistingFile() throws Exception {
        File file = temporaryFolder.newFile("large-record.txt");
        Files.write(file.toPath(), bytes("ok"));

        assertFalse(BoundedFileAppender.append(file, bytes("record-too-large"), 5));

        assertArrayEquals(bytes("ok"), Files.readAllBytes(file.toPath()));
        assertTrue(file.length() <= 5);
    }

    @Test
    public void removesLegacyOversizedFileEvenWhenNewRecordIsRejected() throws Exception {
        File file = temporaryFolder.newFile("legacy-oversized.txt");
        Files.write(file.toPath(), bytes("12345678"));

        assertFalse(BoundedFileAppender.append(file, bytes("record-too-large"), 5));

        assertTrue(file.length() <= 5);
    }

    @Test
    public void concurrentAppendsRemainWithinHardLimit() throws Exception {
        File file = temporaryFolder.newFile("concurrent.txt");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> writes = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                writes.add(executor.submit(() -> {
                    start.await();
                    return BoundedFileAppender.append(file, bytes("xxxx"), 64);
                }));
            }
            start.countDown();
            for (Future<Boolean> write : writes) {
                assertTrue(write.get());
            }
        } finally {
            executor.shutdownNow();
        }

        byte[] finalContents = Files.readAllBytes(file.toPath());
        assertTrue(finalContents.length <= 64);
        assertTrue(finalContents.length % 4 == 0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
