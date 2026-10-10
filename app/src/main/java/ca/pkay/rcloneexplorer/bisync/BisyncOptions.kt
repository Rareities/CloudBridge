package ca.pkay.rcloneexplorer.bisync

import kotlinx.serialization.Serializable

/** Versioned, typed overrides. Security and baseline ownership flags are intentionally absent. */
@Serializable
data class BisyncOptions(
    val version: Int = CURRENT_VERSION,
    val checkers: Int = 4,
    val retries: Int = 3,
    val lowLevelRetries: Int = 10,
    val bandwidthMiB: Int? = null,
    val timeoutSeconds: Int = 300,
    val connectTimeoutSeconds: Int = 60,
    val tpsLimit: Int? = null,
    val conflictSuffix: String = "conflict",
    val backupLocalPath: String = "",
    val backupRemotePath: String = "",
    val requireAccess: Boolean = false,
    val customOptions: Map<String, String> = emptyMap()
) {
    companion object { const val CURRENT_VERSION = 1 }

    fun validate() {
        require(version == CURRENT_VERSION) { "Unsupported bisync options version" }
        require(checkers in 1..16 && retries in 1..10 && lowLevelRetries in 1..20) { "Invalid concurrency/retry budget" }
        require(timeoutSeconds in 30..3600 && connectTimeoutSeconds in 5..300) { "Invalid timeout" }
        bandwidthMiB?.let { require(it in 1..10240) { "Invalid bandwidth limit" } }
        tpsLimit?.let { require(it in 1..100) { "Invalid API request rate" } }
        require(conflictSuffix.isNotBlank() && conflictSuffix.length <= 48 &&
            conflictSuffix.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid conflict suffix" }
        require(backupLocalPath.isBlank() == backupRemotePath.isBlank()) { "Configure backups for both sides or neither" }
        customOptions.forEach { (name, value) -> BisyncAdvancedOptions.validate(name, value) }
    }

    fun arguments(transfers: Int? = null): List<String> {
        validate()
        val flags = mutableListOf("--checkers", checkers.toString(), "--retries", retries.toString(),
            "--low-level-retries", lowLevelRetries.toString(), "--timeout", "${timeoutSeconds}s",
            "--contimeout", "${connectTimeoutSeconds}s", "--conflict-suffix", conflictSuffix)
        bandwidthMiB?.let { flags.addAll(listOf("--bwlimit", "${it}M")) }
        tpsLimit?.let { flags.addAll(listOf("--tpslimit", it.toString())) }
        require(transfers == null || transfers in 1..8) { "Invalid transfer count" }
        flags.addAll(listOf("--transfers", (transfers ?: 4).toString()))
        val effective = BisyncAdvancedOptions.merge(flags, customOptions)
        val values = effective.chunked(2).associate { it[0] to it[1] }
        values["--max-connections"]?.toInt()?.let { maximum ->
            val minimum = values.getValue("--transfers").toInt() + values.getValue("--checkers").toInt() + 1
            require(maximum >= minimum) { "max-connections must be at least transfers + checkers + 1 ($minimum)" }
        }
        return effective
    }
}
