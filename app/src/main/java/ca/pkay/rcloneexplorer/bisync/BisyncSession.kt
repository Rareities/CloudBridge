package ca.pkay.rcloneexplorer.bisync

import android.content.Context
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Rclone
import org.json.JSONObject
import java.io.File
import java.util.ArrayList

/** State is durable/private, separate from cache and exports. Unclean exits never auto-resync. */
object BisyncSession {
    private var cachedEngineIdentity: String? = null
    @Synchronized private fun engineIdentity(context: Context): String {
        cachedEngineIdentity?.let { return it }
        val engine = File(context.applicationInfo.nativeLibraryDir, "librclone.so")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        engine.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { cachedEngineIdentity = it }
    }
    private fun approvalIdentity(context: Context, task: Task): String {
        val remote = JSONObject(Rclone(context).configDump()).getJSONObject(task.remoteId)
        check(remote.optString("type") == "protondrive") {
            "Initial bisync support requires a native local directory and a Proton Drive remote"
        }
        // Ignore rotating session secrets, but bind approval to account and all declared options.
        val rotating = setOf("client_uid", "client_access_token", "client_refresh_token", "client_salted_key_pass")
        val values = remote.keys().asSequence().filter { it !in rotating }.sorted()
            .joinToString("") { key -> val value = remote.get(key).toString(); "${key.length}:$key${value.length}:$value" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(values.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "${BisyncPolicy.fingerprint(task)}:${engineIdentity(context)}:$digest"
    }
    private fun prefs(context: Context) = context.getSharedPreferences("bisync_state", Context.MODE_PRIVATE)
    private fun root(context: Context, task: Task): File {
        BisyncPolicy.validate(task)
        return File(context.filesDir, "bisync/${task.stableUUID}").apply {
            check(isDirectory || mkdirs()) { "Cannot create private bisync state" }
        }
    }

    @JvmStatic fun needsReview(context: Context, task: Task): Boolean {
        val p = prefs(context)
        return p.getString("${task.stableUUID}.fingerprint", null) != approvalIdentity(context, task) ||
            p.getString("${task.stableUUID}.state", null) !in listOf("ready", "approved")
    }

    data class PreviewApproval internal constructor(val identity: String, val generation: Long, val state: String?)

    /** Capture under the admission lease before preview; bind confirmation to these exact inputs. */
    fun previewApproval(context: Context, task: Task): PreviewApproval {
        val p = prefs(context)
        return PreviewApproval(approvalIdentity(context, task),
            p.getLong("${task.stableUUID}.generation", 0), p.getString("${task.stableUUID}.state", null))
    }

    /** Called only after successful real dry-run and explicit user confirmation. */
    fun approve(context: Context, task: Task, preview: PreviewApproval) {
        previewLease(context).use {
            check(preview == previewApproval(context, task) && preview.state != "running") {
                "Configuration or job state changed after preview; review it again"
            }
            check(prefs(context).edit()
                .putString("${task.stableUUID}.fingerprint", preview.identity)
                .putString("${task.stableUUID}.state", "approved")
                .putLong("${task.stableUUID}.generation", preview.generation + 1).commit()) { "Cannot persist approval" }
        }
    }

    /** Preview shares admission with jobs; it must not race baseline-changing operations. */
    @JvmStatic fun previewLease(context: Context): java.io.Closeable {
        return ca.pkay.rcloneexplorer.util.OperationAdmission.acquire(
            File(context.filesDir, "bisync-admission.lock"))
    }

    fun prepare(context: Context, task: Task): Run {
        check(File(task.localPath).isDirectory && File(task.localPath).canRead()) {
            "Local root is missing or unavailable; bisync was not started"
        }
        val directory = root(context, task)
        // Conservatively serialize all app bisync jobs, including aliases/overlapping roots.
        val lease = previewLease(context)
        try {
            val p = prefs(context)
            check(!needsReview(context, task)) { "Bisync needs a successful preview and explicit initialization/recovery approval in the task editor" }
            val initialize = p.getString("${task.stableUUID}.state", null) == "approved"
            check(initialize || directory.listFiles()?.count { it.name.endsWith(".lst") } == 2) {
                "Bisync baseline is missing; review initialization again"
            }
            check(p.edit().putBoolean("${task.stableUUID}.initialize", initialize)
                .putString("${task.stableUUID}.state", "running").commit()) { "Cannot persist run state" }
            return Run(context.applicationContext, task.stableUUID, lease)
        } catch (e: Exception) { lease.close(); throw e }
    }

    @JvmStatic fun arguments(context: Context, task: Task, preview: Boolean): ArrayList<String> {
        val directory = root(context, task)
        val result = arrayListOf("bisync", File(task.localPath).canonicalPath,
            "${task.remoteId}:${task.remotePath}", "--workdir",
            if (preview) File(directory, "preview").apply { mkdirs() }.absolutePath else directory.absolutePath,
            "--protondrive-original-file-size=true", "--protondrive-enable-caching=false",
            "--compare", "size,checksum", "--checksum", "--check-sync", "true", "--max-delete",
            task.bisyncMaxDelete.toString(), "--conflict-resolve", "none", "--conflict-loser", "num")
        if (preview || prefs(context).getBoolean("${task.stableUUID}.initialize", false)) {
            result.addAll(listOf("--resync", "--resync-mode", "path1"))
        }
        if (preview) result.add("--dry-run")
        task.transfers?.let { result.addAll(listOf("--transfers", it.toString())) }
        return result
    }

    class Run internal constructor(private val context: Context, private val id: String,
        private val lease: java.io.Closeable) {
        private var finished = false
        @Synchronized fun complete(success: Boolean) {
            if (finished) return
            finished = true
            try {
                check(prefs(context).edit().putString("$id.state", if (success) "ready" else "recovery")
                    .putBoolean("$id.initialize", false)
                    .putLong("$id.generation", prefs(context).getLong("$id.generation", 0) + 1).commit()) { "Cannot persist terminal state" }
            } finally { lease.close() }
        }
    }
}
