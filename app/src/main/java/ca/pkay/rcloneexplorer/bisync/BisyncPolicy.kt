package ca.pkay.rcloneexplorer.bisync

import ca.pkay.rcloneexplorer.Items.Task
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Deliberately conservative first release: native local paths and no changing filters. */
object BisyncPolicy {
    fun validate(task: Task) {
        UUID.fromString(task.stableUUID)
        require(task.localPath.startsWith("/") && !task.localPath.contains('\u0000')) {
            "Bisync requires an absolute native local directory"
        }
        require(task.remoteId.isNotBlank() && !task.remoteId.contains(':')) { "Invalid remote" }
        require(task.remotePath.isNotBlank() && !task.remotePath.contains('\u0000')) { "Invalid remote path" }
        require(task.bisyncMaxDelete in 0..25) { "Deletion limit must be between 0 and 25 percent" }
        require(task.filterId == null && !task.deleteExcluded) {
            "Bisync filters are not supported until baseline-changing filter migration is implemented"
        }
        task.transfers?.let { require(it in 1..8) { "Invalid transfer count" } }
    }

    fun fingerprint(task: Task): String {
        validate(task)
        val values = listOf("policy-v1", task.stableUUID, task.remoteId, task.remoteType.toString(),
            task.remotePath, File(task.localPath).canonicalPath, task.bisyncMaxDelete.toString())
        // Length-prefix fields so delimiters in filenames cannot create the same identity.
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
