package ca.pkay.rcloneexplorer.bisync

/** Versioned templates are copied into the job. Running jobs never resolve a live preset. */
object BisyncPresets {
    enum class Id { BALANCED, LOW_MEMORY, LIMITED_BANDWIDTH }
    const val VERSION = 1

    fun apply(id: Id, current: BisyncOptions): BisyncOptions {
        require(current.version == BisyncOptions.CURRENT_VERSION) { "Unsupported preset/options version" }
        val tuning = when (id) {
            Id.BALANCED -> BisyncOptions(checkers = 4, tpsLimit = 10,
                customOptions = mapOf("transfers" to "2", "buffer-size" to "4M", "max-connections" to "7"))
            Id.LOW_MEMORY -> BisyncOptions(checkers = 2, tpsLimit = 4,
                customOptions = mapOf("transfers" to "1", "buffer-size" to "0M", "max-connections" to "4"))
            Id.LIMITED_BANDWIDTH -> BisyncOptions(checkers = 2, bandwidthMiB = 2, tpsLimit = 2,
                customOptions = mapOf("transfers" to "1", "buffer-size" to "1M", "max-connections" to "4"))
        }
        return tuning.copy(conflictSuffix = current.conflictSuffix,
            backupLocalPath = current.backupLocalPath, backupRemotePath = current.backupRemotePath,
            requireAccess = current.requireAccess, customOptions = tuning.customOptions.toMap()).also { it.validate() }
    }
}
