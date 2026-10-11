package ca.pkay.rcloneexplorer.util

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** App state is separate from rclone's listing files. All transitions are persisted atomically. */
class BisyncStateStore(private val directory: File) {
    companion object {
        const val RUN = "run"
        const val PREVIEW = "preview"
        const val INITIALISE = "initialise"
        const val NEEDS_INITIALISATION = "NEEDS_INITIALISATION"
        const val NEEDS_RECOVERY = "NEEDS_RECOVERY"
        const val PREVIEW_READY = "PREVIEW_READY"
        const val READY = "READY"
        internal const val PREVIEW_LIFETIME_MS = 30 * 60_000L
        private val ioLock = Any()

        @JvmStatic fun isReady(state: JSONObject, fingerprint: String): Boolean =
            state.optString("status") == READY && state.optString("fingerprint") == fingerprint

        @JvmStatic fun isPreviewApproved(state: JSONObject, fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
            val age = now - state.optLong("previewAt")
            return state.optString("status") == PREVIEW_READY &&
                state.optString("previewFingerprint") == fingerprint &&
                age in 0..PREVIEW_LIFETIME_MS
        }
    }

    private val file = AtomicFile(File(directory, "cloudbridge.json"))

    fun read(): JSONObject = synchronized(ioLock) {
        try {
            val bytes = file.openRead().use { stream ->
                val data = ByteArray(65537)
                var size = 0
                while (size < data.size) {
                    val n = stream.read(data, size, data.size - size)
                    if (n < 0) break
                    size += n
                }
                require(size <= 65536) { "State file is too large" }
                data.copyOf(size)
            }
            JSONObject(String(bytes, Charsets.UTF_8)).also {
                require(it.getInt("version") == 1) { "Unknown state version" }
            }
        } catch (_: FileNotFoundException) {
            JSONObject().put("version", 1).put("status", NEEDS_INITIALISATION)
        } catch (_: Exception) {
            JSONObject().put("version", 1).put("status", NEEDS_RECOVERY)
                .put("message", "Saved bisync state could not be read. Review recovery before running.")
        }
    }

    fun begin(operation: String, fingerprint: String, approval: String?, runId: String) = synchronized(ioLock) {
        val state = read()
        when (operation) {
            RUN -> require(isReady(state, fingerprint)) {
                "Bisync needs initialisation or recovery, or its settings have changed. Open the task and preview a new baseline."
            }
            INITIALISE -> {
                require(approval == fingerprint && isPreviewApproved(state, fingerprint)) {
                    "Initialisation approval is missing, expired or no longer matches the task, configuration or engine. Run a new preview."
                }
            }
            PREVIEW -> Unit
            else -> throw IllegalArgumentException("Unknown bisync operation")
        }
        state.put("status", when (operation) {
            PREVIEW -> "PREVIEWING"
            INITIALISE -> "INITIALISING"
            else -> "RUNNING"
        })
        state.put("runId", runId).put("operation", operation)
            .put("startedAt", System.currentTimeMillis()).put("message", "")
        state.remove("previewFingerprint")
        write(state)
    }

    fun finish(operation: String, fingerprint: String, runId: String, success: Boolean,
               exitCode: Int?, message: String, preview: String = "") = synchronized(ioLock) {
        val state = read()
        if (state.optString("runId") != runId) return@synchronized
        state.put("finishedAt", System.currentTimeMillis())
            .put("exitCode", exitCode ?: JSONObject.NULL)
            .put("message", message.take(2048))
        when {
            operation == PREVIEW && success -> {
                state.put("status", PREVIEW_READY)
                    .put("previewAt", System.currentTimeMillis())
                    .put("previewFingerprint", fingerprint)
                    .put("preview", if (preview.length > 16000)
                        "[Earlier preview entries omitted; this is a bounded diagnostic summary.]\n" + preview.takeLast(16000)
                        else preview)
            }
            success -> {
                state.put("status", READY).put("fingerprint", fingerprint)
                    .put("lastSuccessfulRun", System.currentTimeMillis())
                if (operation == INITIALISE) state.put("baselineId", runId)
            }
            else -> state.put("status", NEEDS_RECOVERY)
        }
        write(state)
    }

    private fun write(value: JSONObject) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create private bisync state" }
        var bytes = value.toString().toByteArray(Charsets.UTF_8)
        while (bytes.size > 65536 && value.optString("preview").length > 1000) {
            val preview = value.optString("preview")
            value.put("preview", "[Earlier preview entries omitted; this is a bounded diagnostic summary.]\n" +
                preview.takeLast(preview.length / 2))
            bytes = value.toString().toByteArray(Charsets.UTF_8)
        }
        require(bytes.size <= 65536) { "Bisync state exceeds the private storage limit" }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
