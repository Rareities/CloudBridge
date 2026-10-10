package ca.pkay.rcloneexplorer.bisync

import ca.pkay.rcloneexplorer.Items.Task
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

@Serializable
data class BisyncJobSpec(
    val schemaVersion: Int = 1,
    val jobUUID: String,
    val legacyTaskId: Long,
    val mode: Int,
    val path1: String,
    val path2: String,
    val configFingerprint: String,
    val engineSha: String,
    val capabilitySchema: Int = 1,
    val configGeneration: Long,
    val baselineGeneration: Long,
    val maxDeletePercent: Int,
    val options: BisyncOptions
) {
    companion object {
        fun from(task: Task, configFingerprint: String, engineSha: String, configGeneration: Long, baselineGeneration: Long) =
            BisyncJobSpec(jobUUID = task.stableUUID, legacyTaskId = task.id, mode = task.direction,
                path1 = File(task.localPath).canonicalPath,
                path2 = "${task.remoteId}:${BisyncPolicy.normalizeRemote(task.remotePath)}",
                configFingerprint = configFingerprint, engineSha = engineSha, configGeneration = configGeneration,
                baselineGeneration = baselineGeneration,
                maxDeletePercent = task.bisyncMaxDelete, options = task.bisyncOptions)
    }
}

/** Android files are private; fsync data before same-directory rename promotes a generation. */
internal object BisyncMetadata {
    fun write(file: File, content: String) {
        val staging = File(file.parentFile, file.name + ".new")
        FileOutputStream(staging).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        check(staging.renameTo(file)) { "Cannot atomically replace ${file.name}" }
    }
}
