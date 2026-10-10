package ca.pkay.rcloneexplorer.bisync

import android.content.Context
import ca.pkay.rcloneexplorer.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BisyncCapabilitySnapshot(val schemaVersion: Int = 1, val engineSha: String,
    val flags: List<String>, val observedAt: Long)

object BisyncCapabilities {
    private val required = setOf("workdir", "compare", "check-sync", "max-delete", "conflict-resolve",
        "conflict-loser", "conflict-suffix", "resync", "resync-mode", "check-access", "check-filename",
        "backup-dir1", "backup-dir2")

    fun parseFlags(help: String): Set<String> =
        Regex("(?m)^\\s+(?:-[A-Za-z],\\s*)?--([a-z][a-z0-9-]*)\\b").findAll(help)
            .map { it.groupValues[1] }.toSet()

    @JvmStatic fun verify(context: Context, help: String) {
        val flags = parseFlags(help)
        check(flags.containsAll(required)) { "Selected rclone lacks required bisync safeguards: ${required - flags}" }
        val snapshot = BisyncCapabilitySnapshot(engineSha = BuildConfig.RCLONE_SOURCE_SHA,
            flags = flags.sorted(), observedAt = System.currentTimeMillis())
        val dir = File(context.filesDir, "bisync").apply { mkdirs() }
        BisyncMetadata.write(File(dir, "capabilities.json"), Json.encodeToString(snapshot))
    }

    fun describe(context: Context): String {
        val file = File(context.filesDir, "bisync/capabilities.json")
        if (!file.exists()) return "Capabilities have not yet been verified by a preview."
        check(file.length() <= 256 * 1024) { "Capability snapshot exceeds safe size" }
        val snapshot = Json.decodeFromString<BisyncCapabilitySnapshot>(file.readText())
        if (snapshot.engineSha != BuildConfig.RCLONE_SOURCE_SHA) return "Engine changed; run a new preview."
        return "Verified engine ${snapshot.engineSha.take(12)}; ${snapshot.flags.size} supported bisync flags."
    }

    fun validateCustom(context: Context, names: Set<String>) {
        if (names.isEmpty()) return
        val file = File(context.filesDir, "bisync/capabilities.json")
        check(file.exists() && file.length() <= 256 * 1024) { "Run an engine capability check before custom options" }
        val snapshot = Json.decodeFromString<BisyncCapabilitySnapshot>(file.readText())
        check(snapshot.engineSha == BuildConfig.RCLONE_SOURCE_SHA && snapshot.flags.containsAll(names)) {
            "Custom flags are unsupported by the selected engine: ${names - snapshot.flags.toSet()}"
        }
    }
}
