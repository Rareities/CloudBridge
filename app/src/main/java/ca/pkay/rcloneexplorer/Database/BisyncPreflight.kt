package ca.pkay.rcloneexplorer.Database

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale

/** Comparison supported by the native Bisync implementation, not reimplemented in Kotlin. */
enum class BisyncComparisonMode(val wireValue: String, val nativeCompareOptions: String) {
    SIZE_AND_MODTIME("SIZE_AND_MODTIME", "size,modtime"),
    SIZE_ONLY("SIZE_ONLY", "size")
}

enum class BisyncNativeState {
    ABSENT,
    COMPATIBLE,
    INCOMPATIBLE,
    INTERRUPTED,
    UNKNOWN;

    companion object {
        fun fromStoredValue(value: String?): BisyncNativeState =
            values().firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class BisyncNativeStateEvidence(
    val state: BisyncNativeState,
    val reason: String,
    val recoveryListingsValid: Boolean
) {
    companion object {
        @JvmStatic
        fun fromWire(status: String?, reason: String?, recoveryListingsValid: Boolean): BisyncNativeStateEvidence {
            val state = when (status) {
                "ABSENT" -> BisyncNativeState.ABSENT
                "COMPATIBLE" -> BisyncNativeState.COMPATIBLE
                "INCOMPATIBLE" -> BisyncNativeState.INCOMPATIBLE
                "INTERRUPTED" -> BisyncNativeState.INTERRUPTED
                else -> BisyncNativeState.UNKNOWN
            }
            val safeReason = reason?.takeIf { Regex("^[A-Z0-9_]{1,64}$").matches(it) } ?: "INVALID_NATIVE_RESULT"
            return BisyncNativeStateEvidence(state, safeReason, recoveryListingsValid && state == BisyncNativeState.INTERRUPTED)
        }
    }
}

enum class BisyncPreflightReason(val wireValue: String) {
    ENGINE_PIN_INVALID("ENGINE_PIN_INVALID"),
    ENGINE_CHANGED("ENGINE_CHANGED"),
    STATE_VERSION_UNSUPPORTED("STATE_VERSION_UNSUPPORTED"),
    FILTER_MISSING("FILTER_MISSING"),
    LEGACY_MIGRATION_CONFIRMATION_REQUIRED("LEGACY_MIGRATION_CONFIRMATION_REQUIRED"),
    FILTER_CHANGED("FILTER_CHANGED"),
    PROFILE_CHANGED("PROFILE_CHANGED"),
    ACCOUNT_IDENTITY_UNKNOWN("ACCOUNT_IDENTITY_UNKNOWN"),
    ENDPOINT_IDENTITY_CHANGED("ENDPOINT_IDENTITY_CHANGED"),
    CONFIG_SNAPSHOT_UNAVAILABLE("CONFIG_SNAPSHOT_UNAVAILABLE"),
    CONFIG_CHANGED_DURING_PREFLIGHT("CONFIG_CHANGED_DURING_PREFLIGHT"),
    ROOT_OVERLAP("ROOT_OVERLAP"),
    LISTING_INCOMPLETE("LISTING_INCOMPLETE"),
    PROBE_CANCELLED("PROBE_CANCELLED"),
    ROOT_NOT_DIRECTORY("ROOT_NOT_DIRECTORY"),
    COMPARISON_UNSUPPORTED("COMPARISON_UNSUPPORTED"),
    DELETE_LIMIT_INVALID("DELETE_LIMIT_INVALID"),
    NATIVE_STATE_UNKNOWN("NATIVE_STATE_UNKNOWN"),
    NATIVE_STATE_INTERRUPTED("NATIVE_STATE_INTERRUPTED"),
    NATIVE_STATE_INCOMPATIBLE("NATIVE_STATE_INCOMPATIBLE"),
    NATIVE_STATE_UNVERIFIED("NATIVE_STATE_UNVERIFIED")
}

/** Hash-only path scope. It deliberately normalizes more broadly than some backends to fail closed. */
data class BisyncEndpointScope private constructor(
    val storageFingerprint: String?,
    val segmentFingerprints: List<String>?
) {
    val isResolved: Boolean
        get() = validDigest(storageFingerprint) && segmentFingerprints != null &&
            segmentFingerprints.all(::validDigest)

    fun overlaps(other: BisyncEndpointScope): Boolean {
        if (!isResolved || !other.isResolved) return true
        if (storageFingerprint != other.storageFingerprint) return false
        val left = requireNotNull(segmentFingerprints)
        val right = requireNotNull(other.segmentFingerprints)
        val shared = minOf(left.size, right.size)
        for (index in 0 until shared) {
            if (left[index] != right[index]) return false
        }
        return true
    }

    fun fingerprint(): String? {
        if (!isResolved) return null
        return digest(canonical(listOf("scope-v1", requireNotNull(storageFingerprint)) + requireNotNull(segmentFingerprints)))
    }

    companion object {
        fun unknown() = BisyncEndpointScope(null, null)

        /** Path is hashed segment-by-segment; its original value is never returned or persisted. */
        fun from(storageFingerprint: String?, path: String?): BisyncEndpointScope {
            // The empty remote path is the provider root; null is an unresolved endpoint.
            if (!validDigest(storageFingerprint) || path == null || path.contains('\u0000')) {
                return unknown()
            }
            val segments = ArrayList<String>()
            val portable = path.replace('\\', '/')
            for (part in portable.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isEmpty()) return unknown() else segments.removeAt(segments.lastIndex)
                    else -> {
                        val normalized = Normalizer.normalize(part, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                        segments.add(digest(normalized))
                    }
                }
            }
            return BisyncEndpointScope(requireNotNull(storageFingerprint).lowercase(Locale.ROOT), segments)
        }
    }
}

data class BisyncEndpointEvidence(
    val accountFingerprint: String?,
    val scope: BisyncEndpointScope,
    val supportsModTimeComparison: Boolean?
)

data class BisyncListingEvidence(
    val complete: Boolean,
    val rootIsDirectory: Boolean,
    val itemCount: Int,
    val failure: BisyncPreflightReason? = null
)

data class BisyncPreflightBaseline(
    val profileRevision: Long,
    val profileFingerprint: String,
    val engineRef: String,
    val leftAccountFingerprint: String,
    val leftScopeFingerprint: String,
    val rightAccountFingerprint: String,
    val rightScopeFingerprint: String,
    val filterFingerprint: String,
    val comparisonMode: BisyncComparisonMode,
    val stateVersion: Int,
    val preflightFingerprint: String
)

data class BisyncPreflightInput @JvmOverloads constructor(
    val profileRevision: Long,
    val profileFingerprint: String,
    val engineRef: String,
    val stateVersion: Int,
    val left: BisyncEndpointEvidence,
    val right: BisyncEndpointEvidence,
    val leftListing: BisyncListingEvidence,
    val rightListing: BisyncListingEvidence,
    val filterFingerprint: String?,
    val filterResolved: Boolean,
    val comparisonMode: BisyncComparisonMode,
    val nativeState: BisyncNativeState,
    val previous: BisyncPreflightBaseline? = null,
    val maxDeletePercent: Int = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT,
    val maxDeleteCount: Int = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT,
    val nativeStateReason: String = "STATE_NOT_RECORDED",
    /** Evidence that retained -old listings passed format/consistency checks; not recovery authorization. */
    val nativeRecoveryListingsValid: Boolean = false
)

data class BisyncPreflightResult(
    val readiness: ProfileReadiness,
    val reason: BisyncPreflightReason?,
    val identityFingerprint: String?,
    val candidateBaseline: BisyncPreflightBaseline?,
    val comparisonDisclosure: String?
)

/** Pure safety policy; actual traversal and all comparison/deletion work remain native rclone. */
object BisyncPreflightPolicy {
    const val CURRENT_STATE_VERSION = 1
    const val DEFAULT_MAX_DELETE_PERCENT = 10
    const val DEFAULT_MAX_DELETE_COUNT = 25
    const val MAX_LISTING_ITEMS = 200_000
    const val MAX_LISTING_JSON_CHARS = 16 * 1024 * 1024
    const val MAX_EVIDENCE_AGE_MILLIS: Long = 15L * 60L * 1000L

    const val SIZE_ONLY_DISCLOSURE =
        "Size-only comparison cannot detect changed contents when file sizes remain equal."

    fun evaluate(input: BisyncPreflightInput): BisyncPreflightResult {
        fun blocked(reason: BisyncPreflightReason) = BisyncPreflightResult(
            ProfileReadiness.BLOCKED, reason, null, null,
            if (input.comparisonMode == BisyncComparisonMode.SIZE_ONLY) SIZE_ONLY_DISCLOSURE else null
        )

        if (!isPinnedEngineRef(input.engineRef)) return blocked(BisyncPreflightReason.ENGINE_PIN_INVALID)
        if (input.stateVersion != CURRENT_STATE_VERSION) return blocked(BisyncPreflightReason.STATE_VERSION_UNSUPPORTED)
        if (!input.filterResolved || !validDigest(input.filterFingerprint)) return blocked(BisyncPreflightReason.FILTER_MISSING)
        if (!validDigest(input.profileFingerprint)) return blocked(BisyncPreflightReason.PROFILE_CHANGED)
        if (!validDigest(input.left.accountFingerprint) || !validDigest(input.right.accountFingerprint) ||
            !input.left.scope.isResolved || !input.right.scope.isResolved) {
            return blocked(BisyncPreflightReason.ACCOUNT_IDENTITY_UNKNOWN)
        }
        if (input.left.scope.overlaps(input.right.scope)) return blocked(BisyncPreflightReason.ROOT_OVERLAP)
        if (input.maxDeletePercent !in 1..100 || input.maxDeleteCount <= 0) {
            return blocked(BisyncPreflightReason.DELETE_LIMIT_INVALID)
        }
        if (!input.leftListing.complete || !input.rightListing.complete) {
            return blocked(input.leftListing.failure ?: input.rightListing.failure ?: BisyncPreflightReason.LISTING_INCOMPLETE)
        }
        if (!input.leftListing.rootIsDirectory || !input.rightListing.rootIsDirectory) {
            return blocked(BisyncPreflightReason.ROOT_NOT_DIRECTORY)
        }
        if (input.leftListing.itemCount !in 0..MAX_LISTING_ITEMS ||
            input.rightListing.itemCount !in 0..MAX_LISTING_ITEMS) {
            return blocked(BisyncPreflightReason.LISTING_INCOMPLETE)
        }
        if (input.comparisonMode == BisyncComparisonMode.SIZE_AND_MODTIME &&
            (input.left.supportsModTimeComparison != true || input.right.supportsModTimeComparison != true)) {
            return blocked(BisyncPreflightReason.COMPARISON_UNSUPPORTED)
        }
        val leftScope = input.left.scope.fingerprint() ?: return blocked(BisyncPreflightReason.ACCOUNT_IDENTITY_UNKNOWN)
        val rightScope = input.right.scope.fingerprint() ?: return blocked(BisyncPreflightReason.ACCOUNT_IDENTITY_UNKNOWN)
        val identity = identityFingerprint(
            requireNotNull(input.profileFingerprint), input.engineRef,
            requireNotNull(input.left.accountFingerprint), leftScope,
            requireNotNull(input.right.accountFingerprint), rightScope,
            requireNotNull(input.filterFingerprint), input.comparisonMode,
            input.maxDeletePercent, input.maxDeleteCount, input.stateVersion
        )
        val candidate = BisyncPreflightBaseline(
            input.profileRevision,
            input.profileFingerprint,
            input.engineRef,
            requireNotNull(input.left.accountFingerprint),
            leftScope,
            requireNotNull(input.right.accountFingerprint),
            rightScope,
            requireNotNull(input.filterFingerprint),
            input.comparisonMode,
            input.stateVersion,
            identity
        )
        val previous = input.previous
        if (previous != null) {
            val changedReason = when {
                previous.engineRef != candidate.engineRef -> BisyncPreflightReason.ENGINE_CHANGED
                previous.stateVersion != candidate.stateVersion -> BisyncPreflightReason.STATE_VERSION_UNSUPPORTED
                previous.leftAccountFingerprint != candidate.leftAccountFingerprint ||
                    previous.rightAccountFingerprint != candidate.rightAccountFingerprint ||
                    previous.leftScopeFingerprint != candidate.leftScopeFingerprint ||
                    previous.rightScopeFingerprint != candidate.rightScopeFingerprint ->
                    BisyncPreflightReason.ENDPOINT_IDENTITY_CHANGED
                previous.filterFingerprint != candidate.filterFingerprint -> BisyncPreflightReason.FILTER_CHANGED
                previous.profileFingerprint != candidate.profileFingerprint ||
                    previous.comparisonMode != candidate.comparisonMode ||
                    previous.preflightFingerprint != candidate.preflightFingerprint -> BisyncPreflightReason.PROFILE_CHANGED
                else -> null
            }
            if (changedReason != null) return blocked(changedReason)
        } else if (input.nativeState == BisyncNativeState.COMPATIBLE) {
            return blocked(BisyncPreflightReason.NATIVE_STATE_UNVERIFIED)
        }

        val readiness = when (input.nativeState) {
            BisyncNativeState.ABSENT -> {
                // Once an accepted native state existed, disappearance is recovery, not a fresh
                // initialization opportunity. Never let missing baseline files look like empty.
                if (previous != null) return blocked(BisyncPreflightReason.NATIVE_STATE_UNKNOWN)
                ProfileReadiness.INITIALIZATION_REQUIRED
            }
            BisyncNativeState.COMPATIBLE -> ProfileReadiness.READY
            BisyncNativeState.INTERRUPTED -> return blocked(BisyncPreflightReason.NATIVE_STATE_INTERRUPTED)
            BisyncNativeState.INCOMPATIBLE -> return blocked(BisyncPreflightReason.NATIVE_STATE_INCOMPATIBLE)
            BisyncNativeState.UNKNOWN -> return blocked(BisyncPreflightReason.NATIVE_STATE_UNKNOWN)
        }
        return BisyncPreflightResult(
            readiness,
            null,
            identity,
            candidate,
            if (input.comparisonMode == BisyncComparisonMode.SIZE_ONLY) SIZE_ONLY_DISCLOSURE else null
        )
    }

    private fun isPinnedEngineRef(value: String): Boolean =
        Regex("^rclone:[^@\\s]+@[0-9a-fA-F]{40}$").matches(value)

    /** Recomputes the path-free identity from persisted preview fields for stale-evidence checks. */
    @JvmStatic
    fun identityFingerprint(
        profileFingerprint: String,
        engineRef: String,
        leftAccountFingerprint: String,
        leftScopeFingerprint: String,
        rightAccountFingerprint: String,
        rightScopeFingerprint: String,
        filterFingerprint: String,
        comparisonMode: BisyncComparisonMode,
        maxDeletePercent: Int,
        maxDeleteCount: Int,
        stateVersion: Int
    ): String = digest(canonical(listOf(
        "bisync-preflight-v1", profileFingerprint, engineRef,
        leftAccountFingerprint, leftScopeFingerprint,
        rightAccountFingerprint, rightScopeFingerprint,
        filterFingerprint, comparisonMode.wireValue,
        maxDeletePercent.toString(), maxDeleteCount.toString(), stateVersion.toString()
    )))

    /** Hashes safety-relevant observations separately from the stable profile/endpoint identity. */
    @JvmStatic
    fun observationFingerprint(input: BisyncPreflightInput, identityFingerprint: String?): String? {
        if (!validDigest(identityFingerprint)) return null
        fun listingFields(listing: BisyncListingEvidence): List<String> = listOf(
            listing.complete.toString(),
            listing.rootIsDirectory.toString(),
            listing.itemCount.toString(),
            listing.failure?.wireValue ?: "NONE"
        )

        return digest(canonical(listOf(
            "bisync-preflight-observation-v1",
            requireNotNull(identityFingerprint),
            input.left.supportsModTimeComparison?.toString() ?: "UNKNOWN",
            input.right.supportsModTimeComparison?.toString() ?: "UNKNOWN",
            input.filterResolved.toString(),
            input.nativeState.name,
            input.nativeRecoveryListingsValid.toString()
        ) + listingFields(input.leftListing) + listingFields(input.rightListing)))
    }
}

/** Native lsjson output is accepted only after confirmed exit, full drain, bounded size and strict parse. */
object BisyncListingValidator {
    private val NATIVE_MOD_TIME_PATTERN = Regex(
        "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{0,8}[1-9]))?(Z|([+-])([0-9]{2}):([0-9]{2}))$"
    )

    @JvmStatic
    fun parseRootStat(
        json: String,
        exitSucceeded: Boolean,
        outputTruncated: Boolean
    ): BisyncListingEvidence {
        if (!exitSucceeded || outputTruncated || json.length > BisyncPreflightPolicy.MAX_LISTING_JSON_CHARS) {
            return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
        }
        return try {
            val root = JSONObject(json)
            if (!root.has("IsDir") || root.isNull("IsDir") || root.opt("IsDir") !is Boolean) {
                BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
            } else {
                val isDirectory = root.getBoolean("IsDir")
                BisyncListingEvidence(isDirectory, isDirectory, 0,
                    if (isDirectory) null else BisyncPreflightReason.ROOT_NOT_DIRECTORY)
            }
        } catch (_: JSONException) {
            BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun parseRecursiveList(
        json: String,
        exitSucceeded: Boolean,
        outputTruncated: Boolean,
        maxItems: Int = BisyncPreflightPolicy.MAX_LISTING_ITEMS
    ): BisyncListingEvidence {
        if (!exitSucceeded || outputTruncated || json.length > BisyncPreflightPolicy.MAX_LISTING_JSON_CHARS || maxItems <= 0) {
            return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
        }
        return try {
            val entries = JSONArray(json)
            if (entries.length() > maxItems) return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
            val seenPaths = HashSet<String>(entries.length())
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: return BisyncListingEvidence(
                    false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE
                )
                if (!entry.has("Path") || !entry.has("Name") || !entry.has("IsDir") ||
                    !entry.has("Size") || !entry.has("ModTime") ||
                    entry.isNull("Path") || entry.isNull("Name") || entry.isNull("IsDir") ||
                    entry.isNull("Size") || entry.isNull("ModTime")) {
                    return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
                }
                if (entry.opt("Path") !is String || entry.opt("Name") !is String ||
                    entry.opt("IsDir") !is Boolean || entry.opt("Size") !is Number ||
                    entry.opt("ModTime") !is String) {
                    return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
                }
                val path = entry.getString("Path")
                val name = entry.getString("Name")
                val isDirectory = entry.getBoolean("IsDir")
                val size = (entry.opt("Size") as Number).toString().toLongOrNull()
                if (size == null || (size < 0L && !(isDirectory && size == -1L)) ||
                    !isNativeModTime(entry.getString("ModTime"))) {
                    return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
                }
                val normalizedPath = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                if (path.isBlank() || path.contains('\u0000') || name.isBlank() || name.contains('\u0000') ||
                    name == "." || name == ".." ||
                    path.startsWith('/') || path.startsWith('\\') || !safeRelativePath(path) ||
                    path.split('/', '\\').lastOrNull() != name || !seenPaths.add(normalizedPath)) {
                    return BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
                }
                // Directory/file semantics are deliberately left to native rclone Bisync.
            }
            BisyncListingEvidence(true, true, entries.length())
        } catch (_: JSONException) {
            BisyncListingEvidence(false, false, 0, BisyncPreflightReason.LISTING_INCOMPLETE)
        }
    }

    /** Validates Go time.Time JSON output (RFC3339Nano), including its canonical spelling. */
    private fun isNativeModTime(value: String): Boolean {
        val match = NATIVE_MOD_TIME_PATTERN.matchEntire(value) ?: return false
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        if (month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return false
        val leapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val daysInMonth = when (month) {
            2 -> if (leapYear) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        if (day !in 1..daysInMonth) return false

        // Go's `Z07:00` layout emits Z for a zero offset. RFC3339Nano also trims
        // trailing zeroes from fractional seconds, as enforced by the pattern.
        if (match.groupValues[8] != "Z") {
            val offsetHour = match.groupValues[10].toInt()
            val offsetMinute = match.groupValues[11].toInt()
            if (offsetHour !in 0..23 || offsetMinute !in 0..59 ||
                (offsetHour == 0 && offsetMinute == 0)) return false
        }
        return true
    }

    private fun safeRelativePath(path: String): Boolean = path.split('/', '\\').none { it == ".." || it == "." || it.isEmpty() }
}

private fun validDigest(value: String?): Boolean = value != null && Regex("^[0-9a-f]{64}$").matches(value)

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun canonical(values: List<String>): String = values.joinToString("|") { value ->
    "${value.toByteArray(StandardCharsets.UTF_8).size}:$value"
}
