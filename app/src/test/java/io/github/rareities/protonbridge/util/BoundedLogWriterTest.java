package io.github.rareities.protonbridge.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BoundedLogWriterTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void burstIsBoundedRedactedAndWriterRetires() throws Exception {
        BoundedLogWriter writer = new BoundedLogWriter();
        File log = new File(folder.getRoot(), "logs/log.txt");
        String record = "password=CANARY-secret " + new String(new char[10000]).replace('\0', 'x');
        for (int i = 0; i < 5000; i++) {
            writer.append(log, record);
            assertTrue(writer.pendingCount() <= 128);
        }
        assertTrue(writer.awaitIdle(10000));
        for (File file : log.getParentFile().listFiles()) {
            assertTrue(file.length() <= 2 * 1024 * 1024 + 128);
            assertFalse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("CANARY-secret"));
        }
        assertTrue(log.getParentFile().listFiles().length <= 2);
        writer.append(log, "final outcome");
        assertTrue(writer.awaitIdle(5000));
        assertTrue(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).endsWith("final outcome\n"));
    }

    @Test public void unwritableDestinationDoesNotStrandOtherLogs() throws Exception {
        BoundedLogWriter writer = new BoundedLogWriter();
        File blocked = folder.newFile("not-a-directory");
        writer.append(new File(blocked, "log"), "ignored");
        File good = new File(folder.getRoot(), "good/log");
        writer.append(good, "working");
        assertTrue(writer.awaitIdle(5000));
        assertEquals("working\n", new String(Files.readAllBytes(good.toPath()), StandardCharsets.UTF_8));
    }
}
