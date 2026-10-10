package ca.pkay.rcloneexplorer.bisync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BisyncRunRecord(
    val version: Int = 1,
    val runId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val phase: String,
    val engineSha: String,
    val bytes: Long = 0,
    val transfers: Long = 0,
    val errors: Long = 0,
    val configGeneration: Long = 0,
    val baselineGeneration: Long = 0,
    val trigger: String = "worker",
    val exitCode: Int? = null
)

/** Private, structured metadata only: no filenames, logs, auth/config data or argv. */
class BisyncHistory(private val directory: File) {
    private val file = File(directory, "history.json")
    @Synchronized fun records(): List<BisyncRunRecord> {
        if (!file.exists()) return emptyList()
        check(file.length() <= 128 * 1024) { "History exceeds retention limit" }
        return Json.decodeFromString<List<BisyncRunRecord>>(file.readText()).takeLast(50)
    }

    @Synchronized fun save(record: BisyncRunRecord) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create history directory" }
        val records = records().filter { it.runId != record.runId }.plus(record).takeLast(50)
        val json = Json.encodeToString(records)
        check(json.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "History exceeds retention limit" }
        BisyncMetadata.write(file, json)
    }

    fun interruptedRecords(): List<BisyncRunRecord> = records().filter { it.endedAt == null }
}
