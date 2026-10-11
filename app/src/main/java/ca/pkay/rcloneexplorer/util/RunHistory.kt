package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.util.AtomicFile
import ca.pkay.rcloneexplorer.Items.Task
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** At most forty compact records per task. Credentials and raw argv never enter history. */
object RunHistory {
    private val lock = Any()
    private fun file(context: Context, task: Task): AtomicFile? {
        // Legacy tasks without a persisted UUID cannot safely own history.
        val identity = task.bisyncUUID.takeIf {
            runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)
        } ?: return null
        val root = File(context.filesDir, "run-history")
        require(root.isDirectory || root.mkdirs()) { "Cannot create private run history" }
        return AtomicFile(File(root, "task-$identity.json"))
    }
    fun read(context: Context, task: Task): JSONArray = synchronized(lock) {
        try {
            val target = file(context, task) ?: return@synchronized JSONArray()
            target.openRead().use { input ->
                require(input.channel.size() <= 256 * 1024) { "History exceeds retention limit" }
                JSONArray(input.bufferedReader().readText())
            }
        } catch (_: Exception) { JSONArray() }
    }
    fun record(context: Context, task: Task, runId: String, operation: String, status: String,
               started: Long, exit: Int?, message: String) = synchronized(lock) {
        val target = file(context, task) ?: return@synchronized
        val old = read(context, task)
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val entry = old.getJSONObject(i)
            if (entry.optString("runId") != runId) next.put(entry)
        }
        while (next.length() >= 40) next.remove(0)
        next.put(JSONObject().put("runId", runId).put("operation", operation).put("status", status)
            .put("started", started).put("finished", if (status == "IN_PROGRESS") JSONObject.NULL else System.currentTimeMillis())
            .put("exitCode", exit ?: JSONObject.NULL)
            .put("message", DiagnosticRedactor.redact(message).take(2048)))
        var bytes = next.toString().toByteArray(Charsets.UTF_8)
        while (bytes.size > 256 * 1024 && next.length() > 1) {
            next.remove(0)
            bytes = next.toString().toByteArray(Charsets.UTF_8)
        }
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (e: Exception) { target.failWrite(stream); throw e }
    }
}
