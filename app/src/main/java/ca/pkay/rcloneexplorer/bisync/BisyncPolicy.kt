package ca.pkay.rcloneexplorer.bisync

import ca.pkay.rcloneexplorer.Items.Task
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Deliberately conservative first release: native local paths and approval-bound filter migration. */
object BisyncPolicy {
    fun validate(task: Task) {
        UUID.fromString(task.stableUUID)
        require(task.localPath.startsWith("/") && !task.localPath.contains('\u0000')) {
            "Bisync requires an absolute native local directory"
        }
        require(task.remoteId.isNotBlank() && !task.remoteId.contains(':')) { "Invalid remote" }
        require(task.remotePath.isNotBlank() && !task.remotePath.contains('\u0000')) { "Invalid remote path" }
        require(task.bisyncMaxDelete in 0..25) { "Deletion limit must be between 0 and 25 percent" }
        require(!task.deleteExcluded) {
            "Bisync never deletes excluded files"
        }
        task.transfers?.let { require(it in 1..8) { "Invalid transfer count" } }
        task.bisyncOptions.arguments(task.transfers)
        val options = task.bisyncOptions
        if (options.backupLocalPath.isNotBlank()) {
            require(options.backupLocalPath.startsWith("/")) { "Local backup must be an absolute native path" }
            val local = File(task.localPath).canonicalFile
            val backup = File(options.backupLocalPath).canonicalFile
            require(!overlap(local.path, backup.path)) { "Local backup must be outside the synchronized root" }
            require(options.backupRemotePath.startsWith("/") && !options.backupRemotePath.contains(':') &&
                options.backupRemotePath.none { it.isISOControl() }) { "Invalid remote backup path" }
            require(!overlap(normalizeRemote(task.remotePath), normalizeRemote(options.backupRemotePath))) {
                "Remote backup must be outside the synchronized root"
            }
        }
    }

    fun overlap(first: String, second: String): Boolean {
        val a = first.trimEnd('/').ifEmpty { "/" }
        val b = second.trimEnd('/').ifEmpty { "/" }
        return a == "/" || b == "/" || a == b || a.startsWith("$b/") || b.startsWith("$a/")
    }

    fun normalizeRemote(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Parent segments are not allowed in bisync paths" }
        return "/" + parts.joinToString("/")
    }

    fun fingerprint(task: Task): String {
        validate(task)
        val values = listOf("policy-v2", task.stableUUID, task.remoteId, task.remoteType.toString(),
            normalizeRemote(task.remotePath), File(task.localPath).canonicalPath, task.bisyncMaxDelete.toString(),
            kotlinx.serialization.json.Json.encodeToString(BisyncOptions.serializer(), task.bisyncOptions.copy(customOptions = task.bisyncOptions.customOptions.toSortedMap())))
        // Length-prefix fields so delimiters in filenames cannot create the same identity.
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
