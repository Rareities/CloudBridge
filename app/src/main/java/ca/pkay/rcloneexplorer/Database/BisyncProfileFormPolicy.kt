package ca.pkay.rcloneexplorer.Database

import java.text.Normalizer

/** Input collected by the bounded legacy-backed Bisync profile editor. */
data class BisyncProfileFormInput(
    val legacyTaskId: String,
    val verifiedLegacyTaskId: Long?,
    val legacyTaskIsBisync: Boolean,
    val title: String,
    val localPath: String,
    val remoteId: String,
    val remotePath: String,
    val filterId: String,
    val filterExistsAndIsValid: Boolean,
    val comparisonMode: BisyncComparisonMode?,
    val maxDeletePercent: String,
    val maxDeleteCount: String,
    val remoteExists: Boolean,
    val remoteIsLocal: Boolean,
    val formMatchesVerifiedTask: Boolean
)

data class NormalizedBisyncProfileForm(
    val legacyTaskId: Long,
    val title: String,
    val localPath: String,
    val remoteId: String,
    val remotePath: String,
    val filterId: Long?,
    val comparisonMode: BisyncComparisonMode,
    val maxDeletePercent: Int,
    val maxDeleteCount: Int
)

enum class BisyncProfileFormIssue {
    LEGACY_TASK_ID_REQUIRED,
    LEGACY_TASK_ID_INVALID,
    LEGACY_TASK_NOT_VERIFIED,
    TITLE_REQUIRED,
    LOCAL_PATH_REQUIRED,
    LOCAL_PATH_INVALID,
    REMOTE_ID_REQUIRED,
    REMOTE_PATH_REQUIRED,
    REMOTE_PATH_INVALID,
    REMOTE_UNAVAILABLE,
    FILTER_ID_INVALID,
    FILTER_UNAVAILABLE,
    ENDPOINTS_OVERLAP,
    COMPARISON_MODE_REQUIRED,
    DELETE_PERCENT_INVALID,
    DELETE_COUNT_INVALID,
    FORM_DIFFERS_FROM_SAVED_TASK
}

data class BisyncProfileFormResult(
    val form: NormalizedBisyncProfileForm?,
    val issues: Set<BisyncProfileFormIssue>,
    /** Preview requires a verified existing task and fields that describe its saved endpoints. */
    val canPreview: Boolean
)

/** Pure form validation; preflight evidence and profile readiness are deliberately not inferred. */
object BisyncProfileFormPolicy {
    private const val LOCAL_SCOPE_FINGERPRINT = "0000000000000000000000000000000000000000000000000000000000000000"

    fun evaluate(input: BisyncProfileFormInput): BisyncProfileFormResult {
        val issues = linkedSetOf<BisyncProfileFormIssue>()

        val taskId = input.legacyTaskId.trim().toLongOrNull()
        when {
            input.legacyTaskId.isBlank() -> issues += BisyncProfileFormIssue.LEGACY_TASK_ID_REQUIRED
            taskId == null || taskId <= 0L -> issues += BisyncProfileFormIssue.LEGACY_TASK_ID_INVALID
            taskId != input.verifiedLegacyTaskId || !input.legacyTaskIsBisync ->
                issues += BisyncProfileFormIssue.LEGACY_TASK_NOT_VERIFIED
        }

        val title = input.title.trim()
        if (title.isEmpty() || title.contains('\u0000')) issues += BisyncProfileFormIssue.TITLE_REQUIRED

        val localRaw = input.localPath.trim()
        val localPath = normalizePath(localRaw, requireAbsolute = true)
        when {
            localRaw.isEmpty() -> issues += BisyncProfileFormIssue.LOCAL_PATH_REQUIRED
            localPath == null -> issues += BisyncProfileFormIssue.LOCAL_PATH_INVALID
        }

        val remoteId = input.remoteId.trim()
        if (remoteId.isEmpty() || remoteId.contains('\u0000')) issues += BisyncProfileFormIssue.REMOTE_ID_REQUIRED
        if (!input.remoteExists) issues += BisyncProfileFormIssue.REMOTE_UNAVAILABLE

        val remoteRaw = input.remotePath.trim()
        val remotePath = normalizePath(remoteRaw, requireAbsolute = false)
        when {
            remoteRaw.isEmpty() -> issues += BisyncProfileFormIssue.REMOTE_PATH_REQUIRED
            remotePath == null -> issues += BisyncProfileFormIssue.REMOTE_PATH_INVALID
        }

        val filterText = input.filterId.trim()
        val filterId = when {
            filterText.isEmpty() -> null
            else -> filterText.toLongOrNull()?.takeIf { it > 0L }
        }
        if (filterText.isNotEmpty() && filterId == null) issues += BisyncProfileFormIssue.FILTER_ID_INVALID
        if (filterId != null && !input.filterExistsAndIsValid) {
            issues += BisyncProfileFormIssue.FILTER_UNAVAILABLE
        }

        if (input.comparisonMode == null) issues += BisyncProfileFormIssue.COMPARISON_MODE_REQUIRED

        val percent = input.maxDeletePercent.trim().toIntOrNull()
        if (percent == null || percent !in 1..100) {
            issues += BisyncProfileFormIssue.DELETE_PERCENT_INVALID
        }
        val count = input.maxDeleteCount.trim().toIntOrNull()
        if (count == null || count <= 0) issues += BisyncProfileFormIssue.DELETE_COUNT_INVALID

        if (localPath != null && remotePath != null && input.remoteIsLocal) {
            val localScope = BisyncEndpointScope.from(LOCAL_SCOPE_FINGERPRINT, localPath)
            val remoteScope = BisyncEndpointScope.from(LOCAL_SCOPE_FINGERPRINT, remotePath)
            if (!localScope.isResolved || !remoteScope.isResolved || localScope.overlaps(remoteScope)) {
                issues += BisyncProfileFormIssue.ENDPOINTS_OVERLAP
            }
        }

        if (taskId != null && taskId > 0L && input.verifiedLegacyTaskId == taskId &&
            input.legacyTaskIsBisync && !input.formMatchesVerifiedTask) {
            issues += BisyncProfileFormIssue.FORM_DIFFERS_FROM_SAVED_TASK
        }

        val fieldIssues = issues - setOf(
            BisyncProfileFormIssue.LEGACY_TASK_ID_REQUIRED,
            BisyncProfileFormIssue.LEGACY_TASK_ID_INVALID,
            BisyncProfileFormIssue.LEGACY_TASK_NOT_VERIFIED,
            BisyncProfileFormIssue.FILTER_UNAVAILABLE,
            BisyncProfileFormIssue.FORM_DIFFERS_FROM_SAVED_TASK
        )
        val normalized = if (fieldIssues.isEmpty() && taskId != null && taskId > 0L &&
            localPath != null && remotePath != null && input.comparisonMode != null &&
            percent != null && count != null) {
            NormalizedBisyncProfileForm(
                legacyTaskId = taskId,
                title = title,
                localPath = localPath,
                remoteId = remoteId,
                remotePath = remotePath,
                filterId = filterId,
                comparisonMode = input.comparisonMode,
                maxDeletePercent = percent,
                maxDeleteCount = count
            )
        } else {
            null
        }

        val taskVerified = taskId != null && taskId > 0L &&
            input.verifiedLegacyTaskId == taskId && input.legacyTaskIsBisync
        val canPreview = normalized != null && taskVerified && input.remoteExists &&
            (filterId == null || input.filterExistsAndIsValid) &&
            input.formMatchesVerifiedTask && issues.isEmpty()
        return BisyncProfileFormResult(normalized, issues, canPreview)
    }

    /** Returns a portable lexical path, collapsing separators and dot segments. */
    fun normalizePath(raw: String, requireAbsolute: Boolean): String? {
        if (raw.contains('\u0000') || raw.any { it.isISOControl() }) return null
        val portable = Normalizer.normalize(raw.trim().replace('\\', '/'), Normalizer.Form.NFC)
        if (portable.isEmpty()) return null
        val absolute = portable.startsWith('/')
        if (requireAbsolute && !absolute) return null

        val segments = ArrayList<String>()
        for (segment in portable.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments += segment
            }
        }
        if (segments.isEmpty() && !absolute) return null
        val body = segments.joinToString("/")
        return when {
            absolute && body.isEmpty() -> "/"
            absolute -> "/$body"
            else -> body
        }
    }
}
