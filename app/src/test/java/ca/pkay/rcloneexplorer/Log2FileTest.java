package ca.pkay.rcloneexplorer;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Log2FileTest {

    @Test
    public void safeLogMessageRedactsSecretsAndKeepsUsefulContext() {
        String safe = Log2File.safeLogMessage("Upload failed: password=top-secret; retry later");

        assertTrue(safe.contains("Upload failed"));
        assertTrue(safe.contains("retry later"));
        assertFalse(safe.contains("top-secret"));
    }

    @Test
    public void safeLogMessageTruncatesOversizedRecords() {
        String safe = Log2File.safeLogMessage("x".repeat(40_000));

        assertEquals(ca.pkay.rcloneexplorer.util.LogRedactor.MAX_DIAGNOSTIC_CHARS, safe.length());
        assertTrue(safe.endsWith("***diagnostic-output-truncated***"));
    }

    @Test
    public void writerQueueHasFixedCapacityAndRejectsExcessRecords() throws Exception {
        ThreadPoolExecutor writer = Log2File.createLogWriter();
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try {
            writer.execute(() -> {
                writerStarted.countDown();
                try {
                    releaseWriter.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < Log2File.MAX_PENDING_LOG_RECORDS; i++) {
                writer.execute(() -> { });
            }
            assertEquals(Log2File.MAX_PENDING_LOG_RECORDS, writer.getQueue().size());

            try {
                writer.execute(() -> { });
                fail("A record beyond the bounded queue should be rejected");
            } catch (RejectedExecutionException expected) {
                assertEquals(Log2File.MAX_PENDING_LOG_RECORDS, writer.getQueue().size());
            }
        } finally {
            releaseWriter.countDown();
            writer.shutdownNow();
        }
    }
}
