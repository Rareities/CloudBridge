package ca.pkay.rcloneexplorer.Database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BisyncProfileFormPolicyTest {
    @Test
    fun validExistingTaskFormNormalizesPathsAndAllowsPreview() {
        val result = BisyncProfileFormPolicy.evaluate(input(
            title = "  Notes  ",
            localPath = "/storage//emulated/0/notes/./",
            remotePath = "vault\\notes\\",
            formMatchesVerifiedTask = true
        ))

        assertTrue(result.issues.toString(), result.canPreview)
        assertEquals("Notes", result.form?.title)
        assertEquals("/storage/emulated/0/notes", result.form?.localPath)
        assertEquals("vault/notes", result.form?.remotePath)
        assertEquals(BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT, result.form?.maxDeletePercent)
        assertEquals(BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT, result.form?.maxDeleteCount)
    }

    @Test
    fun requiredFieldsAndAbsoluteNormalizedPathsAreValidated() {
        val missing = BisyncProfileFormPolicy.evaluate(input(
            title = " ", localPath = "relative/path", remoteId = " ", remotePath = "../outside"
        ))

        assertFalse(missing.canPreview)
        assertTrue(BisyncProfileFormIssue.TITLE_REQUIRED in missing.issues)
        assertTrue(BisyncProfileFormIssue.LOCAL_PATH_INVALID in missing.issues)
        assertTrue(BisyncProfileFormIssue.REMOTE_ID_REQUIRED in missing.issues)
        assertTrue(BisyncProfileFormIssue.REMOTE_PATH_INVALID in missing.issues)
        assertNull(BisyncProfileFormPolicy.normalizePath("/safe/../../outside", requireAbsolute = true))
        assertEquals("/safe/notes", BisyncProfileFormPolicy.normalizePath("/safe/tmp/../notes", true))
    }

    @Test
    fun localBackedEndpointsMustNotOverlap() {
        val overlap = BisyncProfileFormPolicy.evaluate(input(
            localPath = "/storage/data",
            remotePath = "/storage/data/archive",
            remoteIsLocal = true
        ))
        val separated = BisyncProfileFormPolicy.evaluate(input(
            localPath = "/storage/data",
            remotePath = "/storage/archive",
            remoteIsLocal = true
        ))

        assertTrue(BisyncProfileFormIssue.ENDPOINTS_OVERLAP in overlap.issues)
        assertFalse(overlap.canPreview)
        assertFalse(BisyncProfileFormIssue.ENDPOINTS_OVERLAP in separated.issues)
        assertTrue(separated.canPreview)
    }

    @Test
    fun optionalFilterMustBeNumericExistingAndValidBeforeSave() {
        val invalidId = BisyncProfileFormPolicy.evaluate(input(filterId = "-2"))
        val unavailable = BisyncProfileFormPolicy.evaluate(input(
            filterId = "14", filterExistsAndIsValid = false
        ))
        val noFilter = BisyncProfileFormPolicy.evaluate(input(filterId = ""))

        assertTrue(BisyncProfileFormIssue.FILTER_ID_INVALID in invalidId.issues)
        assertFalse(invalidId.canPreview)
        assertTrue(BisyncProfileFormIssue.FILTER_UNAVAILABLE in unavailable.issues)
        assertFalse(unavailable.canPreview)
        assertTrue(noFilter.canPreview)
    }

    @Test
    fun comparisonDeleteLimitsAndVerifiedTaskGatePreview() {
        val invalidLimits = BisyncProfileFormPolicy.evaluate(input(
            maxDeletePercent = "101", maxDeleteCount = "2147483648"
        ))
        val wrongTask = BisyncProfileFormPolicy.evaluate(input(
            legacyTaskId = "42", verifiedLegacyTaskId = 41L, formMatchesVerifiedTask = true
        ))
        val unsavedEndpoints = BisyncProfileFormPolicy.evaluate(input(formMatchesVerifiedTask = false))
        val unknownMode = BisyncProfileFormPolicy.evaluate(input(comparisonMode = null))

        assertTrue(BisyncProfileFormIssue.DELETE_PERCENT_INVALID in invalidLimits.issues)
        assertTrue(BisyncProfileFormIssue.DELETE_COUNT_INVALID in invalidLimits.issues)
        assertFalse(invalidLimits.canPreview)
        assertTrue(BisyncProfileFormIssue.LEGACY_TASK_NOT_VERIFIED in wrongTask.issues)
        assertFalse(wrongTask.canPreview)
        assertTrue(BisyncProfileFormIssue.FORM_DIFFERS_FROM_SAVED_TASK in unsavedEndpoints.issues)
        assertFalse(unsavedEndpoints.canPreview)
        assertTrue(BisyncProfileFormIssue.COMPARISON_MODE_REQUIRED in unknownMode.issues)
    }

    @Test
    fun legacyBisyncTaskAndConfiguredRemoteAreRequiredForPreview() {
        val noTask = BisyncProfileFormPolicy.evaluate(input(
            legacyTaskId = "", verifiedLegacyTaskId = null, legacyTaskIsBisync = false
        ))
        val missingRemote = BisyncProfileFormPolicy.evaluate(input(remoteExists = false))

        assertTrue(BisyncProfileFormIssue.LEGACY_TASK_ID_REQUIRED in noTask.issues)
        assertFalse(noTask.canPreview)
        assertTrue(BisyncProfileFormIssue.REMOTE_UNAVAILABLE in missingRemote.issues)
        assertFalse(missingRemote.canPreview)
    }

    private fun input(
        legacyTaskId: String = "41",
        verifiedLegacyTaskId: Long? = 41L,
        legacyTaskIsBisync: Boolean = true,
        title: String = "Notes",
        localPath: String = "/storage/emulated/0/notes",
        remoteId: String = "drive",
        remotePath: String = "notes",
        filterId: String = "",
        filterExistsAndIsValid: Boolean = true,
        comparisonMode: BisyncComparisonMode? = BisyncComparisonMode.SIZE_ONLY,
        maxDeletePercent: String = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_PERCENT.toString(),
        maxDeleteCount: String = BisyncPreflightPolicy.DEFAULT_MAX_DELETE_COUNT.toString(),
        remoteExists: Boolean = true,
        remoteIsLocal: Boolean = false,
        formMatchesVerifiedTask: Boolean = true
    ) = BisyncProfileFormInput(
        legacyTaskId,
        verifiedLegacyTaskId,
        legacyTaskIsBisync,
        title,
        localPath,
        remoteId,
        remotePath,
        filterId,
        filterExistsAndIsValid,
        comparisonMode,
        maxDeletePercent,
        maxDeleteCount,
        remoteExists,
        remoteIsLocal,
        formMatchesVerifiedTask
    )
}
