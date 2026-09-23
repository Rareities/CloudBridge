package ca.pkay.rcloneexplorer.Database

import ca.pkay.rcloneexplorer.Items.FilterEntry
import java.io.File
import java.util.Locale
import java.util.UUID

/** A path-bearing command request that exists only in memory and is never persisted or logged. */
data class BisyncPreviewCommandRequest(
    val identity: BisyncPreviewIdentity,
    val previewId: String,
    val path1: String,
    val path2: String,
    val workDirectory: String,
    val filters: List<FilterEntry>,
    val deleteExcluded: Boolean,
    /** The accepted listing directory is supplied only for a compatible-baseline preview. */
    val acceptedStateDirectory: String? = null,
    /** This legacy task option is not modeled by the current Bisync comparison policy. */
    val checksumRequested: Boolean = false
)

/**
 * Builds the native argv vector for the exact engine pin only. The capability table is deliberately
 * small: the published app engine supports path-free preview JSON; the local prototype additionally
 * supports cloning compatible accepted listings. Unknown commits never inherit either capability.
 */
object BisyncPreviewCommandBuilder {
    const val PUBLISHED_PREVIEW_ENGINE = "fe775a8b58cf217fdf4bd34f0975af1e4c19c1a0"
    const val LOCAL_STATE_CLONE_ENGINE = "81ac481705944ac125e2f8eeab823d78f6b1cfdb"

    private data class Capabilities(val summary: Boolean, val stateClone: Boolean)
    private data class FilterRule(val type: Int, val pattern: String)

    @JvmStatic
    fun build(request: BisyncPreviewCommandRequest): List<String> {
        require(!request.checksumRequested) {
            "Legacy checksum comparison is not represented by the Bisync preflight policy"
        }
        require(isCanonicalUuid(request.previewId)) { "Preview ID must be a canonical UUID" }
        require(request.identity.engineRef == request.identity.engineRef.lowercase(Locale.ROOT)) {
            "Preview engine pin must be normalized"
        }
        val capabilities = capabilitiesFor(request.identity.engineRef)
        require(capabilities.summary) { "Pinned native engine does not support preview summaries" }
        requireArgument(request.path1, "path1")
        requireArgument(request.path2, "path2")
        requireAbsolute(request.workDirectory, "work directory")
        require(request.identity.maxDeletePercent in 1..100 && request.identity.maxDeleteCount > 0) {
            "Invalid delete safety limit"
        }
        require(request.filters.size <= BisyncFilterParser.MAX_RULES) { "Too many Bisync filters" }
        val filters = request.filters.map { candidate ->
            val filter = requireNotNull(candidate) { "Null Bisync filter" }
            val type = filter.filterType
            require(type == FilterEntry.FILTER_INCLUDE || type == FilterEntry.FILTER_EXCLUDE) {
                "Invalid Bisync filter type"
            }
            val pattern = requireNotNull(filter.filter) { "Null Bisync filter pattern" }
            require(!pattern.isNullOrBlank() && pattern.length <= BisyncFilterParser.MAX_PATTERN_CHARS &&
                !pattern.contains('\u0000') && !pattern.contains('\r') && !pattern.contains('\n')) {
                "Invalid Bisync filter pattern"
            }
            FilterRule(type, pattern)
        }

        val args = arrayListOf(
            "bisync", request.path1, request.path2,
            "--dry-run", "--preview-json",
            "--workdir", request.workDirectory,
            "--compare", request.identity.comparisonMode.nativeCompareOptions,
            "--max-delete", request.identity.maxDeletePercent.toString(),
            "--max-delete-count", request.identity.maxDeleteCount.toString()
        )

        when (request.identity.nativeState) {
            BisyncNativeState.ABSENT -> {
                val mode = request.identity.initializationMode
                    ?: throw IllegalArgumentException("Absent-state preview requires an explicit initialization preference")
                require(request.acceptedStateDirectory == null) {
                    "Absent-state preview cannot copy an accepted baseline"
                }
                args += listOf("--resync", "--resync-mode", mode.wireValue)
            }
            BisyncNativeState.COMPATIBLE -> {
                require(request.identity.initializationMode == null) {
                    "Compatible-state preview cannot request resync"
                }
                val source = request.acceptedStateDirectory
                    ?: throw IllegalArgumentException("Compatible-state preview requires its accepted baseline")
                require(capabilities.stateClone) {
                    "Pinned native engine cannot clone an accepted baseline into preview workdir"
                }
                requireAbsolute(source, "accepted baseline directory")
                args += listOf("--preview-state-from", source)
            }
            else -> throw IllegalArgumentException("Unresolved Bisync state cannot be previewed")
        }

        if (request.deleteExcluded) args += "--delete-excluded"
        for (filter in filters) {
            args += "--filter"
            args += (if (filter.type == FilterEntry.FILTER_INCLUDE) "+ " else "- ") + filter.pattern
        }
        return args.toList()
    }

    private fun capabilitiesFor(engineRef: String): Capabilities {
        val match = Regex("^rclone:[A-Za-z0-9._+-]+@([0-9a-f]{40})$").matchEntire(engineRef)
            ?: throw IllegalArgumentException("Preview requires an immutable native engine pin")
        return when (match.groupValues[1]) {
            PUBLISHED_PREVIEW_ENGINE -> Capabilities(summary = true, stateClone = false)
            LOCAL_STATE_CLONE_ENGINE -> Capabilities(summary = true, stateClone = true)
            else -> Capabilities(summary = false, stateClone = false)
        }
    }

    private fun requireArgument(value: String, label: String) {
        require(value.isNotBlank() && !value.contains('\u0000')) { "Invalid $label" }
    }

    private fun requireAbsolute(value: String, label: String) {
        requireArgument(value, label)
        require(File(value).isAbsolute) { "$label must be absolute" }
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }
}
