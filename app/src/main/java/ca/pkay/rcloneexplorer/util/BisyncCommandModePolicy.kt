package ca.pkay.rcloneexplorer.util

import java.io.File

/**
 * Keeps the native Bisync launch surface read-only until preservation and recovery are accepted.
 * The preview builder remains responsible for profile identity, path ownership, engine capability,
 * filters, and freshness; this final boundary rejects any Bisync argv outside inspection/preview.
 */
object BisyncCommandModePolicy {
    private val inspectionFlags = setOf("--inspect-state")
    private val previewFlags = setOf("--dry-run", "--preview-json", "--resync", "--delete-excluded")
    private val valueOptions = setOf(
        "--workdir", "--compare", "--max-delete", "--max-delete-count",
        "--resync-mode", "--preview-state-from", "--filter"
    )
    private val resyncModes = setOf("path1", "path2", "newer", "older", "larger", "smaller")

    /**
     * [arguments] starts at the `bisync` verb, excluding rclone's global flags. Non-Bisync
     * commands are untouched so this policy does not change regular CloudBridge operations.
     */
    @JvmStatic
    fun allowsReadOnlyInvocation(
        arguments: List<String>,
        profileStateRoot: String,
        previewWorkRoot: String
    ): Boolean {
        if (arguments.firstOrNull() != "bisync") return true
        if (arguments.size < 3) return false
        val path1 = arguments[1]
        val path2 = arguments[2]
        if (!isSafeArgument(path1) || path1.startsWith("-") ||
            !isSafeArgument(path2) || path2.startsWith("-")) return false

        val flags = linkedSetOf<String>()
        val values = linkedMapOf<String, MutableList<String>>()
        var index = 3
        while (index < arguments.size) {
            val option = arguments[index]
            if (option == "--" || option.contains('=')) return false
            when {
                option in inspectionFlags || option in previewFlags -> {
                    if (!flags.add(option)) return false
                    index++
                }
                option in valueOptions -> {
                    val value = arguments.getOrNull(index + 1) ?: return false
                    if (!isSafeArgument(value) || value.startsWith("--")) return false
                    val existing = values[option]
                    if (option != "--filter" && existing != null) return false
                    values.getOrPut(option) { mutableListOf() }.add(value)
                    index += 2
                }
                else -> return false
            }
        }

        val inspection = flags.contains("--inspect-state")
        if (inspection) {
            return flags == inspectionFlags && values.keys == setOf("--workdir", "--compare") &&
                isWithinRoot(values["--workdir"]?.singleOrNull(), profileStateRoot) &&
                values["--compare"]?.singleOrNull() in setOf("size", "size,modtime")
        }

        if (!flags.containsAll(setOf("--dry-run", "--preview-json")) ||
            flags.contains("--inspect-state")) return false
        if (!values.keys.containsAll(setOf("--workdir", "--compare", "--max-delete", "--max-delete-count"))) {
            return false
        }
        if (!isWithinRoot(values["--workdir"]?.singleOrNull(), previewWorkRoot) ||
            values["--compare"]?.singleOrNull() !in setOf("size", "size,modtime")) return false
        val maxDelete = values["--max-delete"]?.singleOrNull()?.toIntOrNull() ?: return false
        val maxDeleteCount = values["--max-delete-count"]?.singleOrNull()?.toIntOrNull() ?: return false
        if (maxDelete !in 1..100 || maxDeleteCount <= 0) return false

        val resync = flags.contains("--resync")
        val resyncMode = values["--resync-mode"]?.singleOrNull()
        val previewState = values["--preview-state-from"]?.singleOrNull()
        if (resync != (resyncMode != null) || (resync && previewState != null) ||
            (!resync && !isWithinRoot(previewState, profileStateRoot)) ||
            (resyncMode != null && resyncMode !in resyncModes)) return false
        return true
    }

    private fun isSafeArgument(value: String?): Boolean =
        !value.isNullOrBlank() && !value.contains('\u0000')

    private fun isWithinRoot(value: String?, root: String): Boolean {
        if (!isSafeArgument(value) || !File(requireNotNull(value)).isAbsolute) return false
        return try {
            val canonicalRoot = File(root).canonicalFile.path.trimEnd(File.separatorChar)
            val canonicalCandidate = File(requireNotNull(value)).canonicalFile.path
            canonicalCandidate.startsWith(canonicalRoot + File.separator)
        } catch (_: Exception) {
            false
        }
    }
}
