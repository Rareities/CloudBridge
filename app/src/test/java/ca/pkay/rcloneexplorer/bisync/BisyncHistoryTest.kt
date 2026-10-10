package ca.pkay.rcloneexplorer.bisync

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BisyncHistoryTest {
    @Test fun terminalRecordReplacesRunningRecordAndHistoryIsBounded() {
        val directory = Files.createTempDirectory("bisync-history-test").toFile()
        try {
            val history = BisyncHistory(directory)
            val running = BisyncRunRecord(runId = "run1", startedAt = 1, phase = "running", engineSha = "engine")
            history.save(running)
            assertEquals(1, history.interruptedRecords().size)
            history.save(running.copy(endedAt = 2, phase = "success", bytes = 123))
            assertEquals(0, history.interruptedRecords().size)
            assertEquals(123L, history.records().single().bytes)
            for (i in 2..61) history.save(running.copy(runId = "run$i", endedAt = i.toLong()))
            assertEquals(50, history.records().size)
            assertEquals("run12", history.records().first().runId)
            assertTrue(directory.listFiles()!!.all { it.name == "history.json" })
        } finally { directory.deleteRecursively() }
    }
    @Test(expected = IllegalStateException::class) fun refusesOversizedHistoryInsteadOfUnboundedRead() {
        val directory = Files.createTempDirectory("bisync-history-limit").toFile()
        try {
            directory.resolve("history.json").writeBytes(ByteArray(128 * 1024 + 1))
            BisyncHistory(directory).records()
        } finally { directory.deleteRecursively() }
    }
}
