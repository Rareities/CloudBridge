package ca.pkay.rcloneexplorer.bisync

import android.content.Context
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Rclone
import org.json.JSONObject
import java.io.File
import java.util.ArrayList
import java.util.UUID
import ca.pkay.rcloneexplorer.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private fun approvalIdentity(context: Context, task: Task, filters: String = BisyncFilters.resolve(context, task)): String {
        val remote = JSONObject(Rclone(context).configDump()).getJSONObject(task.remoteId)
        check(remote.optString("type") == "protondrive") {
            "Initial bisync support requires a native local directory and a Proton Drive remote"
        }
        // Ignore rotating session secrets, but bind approval to account and all declared options.
        val rotating = setOf("client_uid", "client_access_token", "client_refresh_token", "client_salted_key_pass", "client_token_generation")
        val values = remote.keys().asSequence().filter { it !in rotating }.sorted()
            .joinToString("") { key -> val value = remote.get(key).toString(); "${key.length}:$key${value.length}:$value" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(values.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "${BisyncPolicy.fingerprint(task)}:${engineIdentity(context)}:${BisyncFilters.digest(filters)}:$digest"
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
        if (task.bisyncOptions.requireAccess) check(File(task.localPath, accessFilename(task)).isFile) {
            "Local access marker is missing; no bisync changes were started"
        }
        val directory = root(context, task)
        // Conservatively serialize all app bisync jobs, including aliases/overlapping roots.
        val lease = previewLease(context)
        try {
            ca.pkay.rcloneexplorer.proton.ProtonAccountState.requireReady(context,
                Rclone(context).protonAccountIdentity(task.remoteId))
            val p = prefs(context)
            check(!needsReview(context, task)) { "Bisync needs a successful preview and explicit initialization/recovery approval in the task editor" }
            val initialize = p.getString("${task.stableUUID}.state", null) == "approved"
            val runId = UUID.randomUUID().toString()
            check(initialize || directory.listFiles()?.count { it.name.endsWith(".lst") } == 2) {
                "Bisync baseline is missing; review initialization again"
            }
            val generation = p.getLong("${task.stableUUID}.generation", 0)
            val configGeneration = ca.pkay.rcloneexplorer.util.ConfigEpoch.current(context)
            val spec = BisyncJobSpec.from(task, approvalIdentity(context, task).substringAfterLast(':'),
                BuildConfig.RCLONE_SOURCE_SHA, configGeneration, generation)
            BisyncMetadata.write(File(directory, "job.json"), Json.encodeToString(spec))
            check(p.edit().putBoolean("${task.stableUUID}.initialize", initialize)
                .putString("${task.stableUUID}.runId", runId)
                .putString("${task.stableUUID}.state", "running").commit()) { "Cannot persist run state" }
            val record = BisyncRunRecord(runId = runId, startedAt = System.currentTimeMillis(),
                phase = if (initialize) "initializing" else "running", engineSha = BuildConfig.RCLONE_SOURCE_SHA,
                configGeneration = configGeneration, baselineGeneration = generation)
            val history = BisyncHistory(directory)
            history.save(record)
            return Run(context.applicationContext, task.stableUUID, lease, history, record)
        } catch (e: Exception) { lease.close(); throw e }
    }

    @JvmOverloads @JvmStatic fun arguments(context: Context, task: Task, preview: Boolean, expectedIdentity: String? = null): ArrayList<String> {
        val directory = root(context, task)
        val filters = BisyncFilters.resolve(context, task)
        if (expectedIdentity != null) check(expectedIdentity == approvalIdentity(context, task, filters)) {
            "Filter or configuration changed before preview; review again"
        }
        if (!preview) check(prefs(context).getString("${task.stableUUID}.fingerprint", null) == approvalIdentity(context, task, filters)) {
            "Filter or configuration changed; preview and approve initialization again"
        }
        val filterFile = BisyncFilters.snapshot(directory, filters)
        val result = arrayListOf("bisync", File(task.localPath).canonicalPath,
            "${task.remoteId}:${task.remotePath}", "--workdir",
            if (preview) File(directory, "preview").apply { mkdirs() }.absolutePath else directory.absolutePath,
            "--protondrive-original-file-size=true", "--protondrive-enable-caching=false",
            "--compare", "size,checksum", "--checksum", "--check-sync", "true", "--max-delete",
            task.bisyncMaxDelete.toString(), "--conflict-resolve", "none", "--conflict-loser", "num")
        result.addAll(listOf("--filters-file", filterFile.absolutePath))
        if (preview || prefs(context).getBoolean("${task.stableUUID}.initialize", false)) {
            result.addAll(listOf("--resync", "--resync-mode", "path1"))
        }
        if (preview) result.add("--dry-run")
        BisyncCapabilities.validateCustom(context, task.bisyncOptions.customOptions.keys)
        result.addAll(task.bisyncOptions.arguments(task.transfers))
        if (task.bisyncOptions.requireAccess) result.addAll(listOf("--check-access", "--check-filename", accessFilename(task)))
        if (task.bisyncOptions.backupLocalPath.isNotBlank()) {
            val suffix = if (preview) "preview" else checkNotNull(prefs(context).getString("${task.stableUUID}.runId", null))
            result.addAll(listOf("--backup-dir1", File(task.bisyncOptions.backupLocalPath, suffix).canonicalPath,
                "--backup-dir2", "${task.remoteId}:${BisyncPolicy.normalizeRemote(task.bisyncOptions.backupRemotePath)}/$suffix"))
        }
        return result
    }

    @JvmStatic fun accessFilename(task: Task) = ".cloudbridge-access-${task.stableUUID}"

    fun history(context: Context, task: Task): List<BisyncRunRecord> = BisyncHistory(root(context, task)).records()

    fun status(context: Context, task: Task): String = prefs(context).getString("${task.stableUUID}.state", "needs initialization")
        ?: "needs initialization"

    fun effectiveCommand(context: Context, task: Task): String = arguments(context, task, true)
        .joinToString(" ") { arg -> "'" + arg.replace("'", "'\\''") + "'" }

    class Run internal constructor(private val context: Context, private val id: String,
        private val lease: java.io.Closeable, private val history: BisyncHistory,
        private var record: BisyncRunRecord) {
        private var finished = false
        private var lastProgressWrite = 0L
        @Synchronized fun progress(bytes: Long, transfers: Long, errors: Long) {
            if (finished) return
            record = record.copy(bytes = bytes.coerceAtLeast(0), transfers = transfers.coerceAtLeast(0), errors = errors.coerceAtLeast(0))
            val now = System.currentTimeMillis()
            if (now - lastProgressWrite >= 5000) {
                history.save(record); lastProgressWrite = now
            }
        }
        @JvmOverloads @Synchronized fun complete(success: Boolean, exitCode: Int? = null) {
            if (finished) return
            finished = true
            try {
                // Record durable terminal evidence before promoting a successful baseline.
                history.save(record.copy(endedAt = System.currentTimeMillis(), exitCode = exitCode,
                    phase = if (success) "success" else "recovery required"))
                check(prefs(context).edit().putString("$id.state", if (success) "ready" else "recovery")
                    .putBoolean("$id.initialize", false)
                    .putLong("$id.generation", prefs(context).getLong("$id.generation", 0) + 1).commit()) { "Cannot persist terminal state" }
            } finally { lease.close() }
        }
    }
}
