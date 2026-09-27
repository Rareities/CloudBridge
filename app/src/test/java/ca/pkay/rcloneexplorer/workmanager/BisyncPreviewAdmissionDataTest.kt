package ca.pkay.rcloneexplorer.workmanager

import ca.pkay.rcloneexplorer.Database.BisyncComparisonMode
import ca.pkay.rcloneexplorer.Database.BisyncPreviewResyncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BisyncPreviewAdmissionDataTest {
    @Test
    fun workInputContainsOnlyStableIdentityAndExplicitPolicy() {
        val data = BisyncPreviewAdmissionData.build(
            profileId = "profile-uuid",
            profileRevision = 7L,
            profileFingerprint = "a".repeat(64),
            comparisonMode = BisyncComparisonMode.SIZE_ONLY,
            maxDeletePercent = 10,
            maxDeleteCount = 25,
            absentStateMode = BisyncPreviewResyncMode.PATH2,
            legacyMigrationConfirmed = true
        )

        assertEquals(
            setOf(
                BisyncPreviewAdmissionWorker.PROFILE_ID,
                BisyncPreviewAdmissionWorker.PROFILE_REVISION,
                BisyncPreviewAdmissionWorker.PROFILE_FINGERPRINT,
                BisyncPreviewAdmissionWorker.COMPARISON_MODE,
                BisyncPreviewAdmissionWorker.MAX_DELETE_PERCENT,
                BisyncPreviewAdmissionWorker.MAX_DELETE_COUNT,
                BisyncPreviewAdmissionWorker.ABSENT_STATE_MODE,
                BisyncPreviewAdmissionWorker.LEGACY_REVIEW_CONFIRMED
            ),
            data.keyValueMap.keys
        )
        assertEquals("profile-uuid", data.getString(BisyncPreviewAdmissionWorker.PROFILE_ID))
        assertEquals(7L, data.getLong(BisyncPreviewAdmissionWorker.PROFILE_REVISION, -1L))
        assertEquals("a".repeat(64), data.getString(BisyncPreviewAdmissionWorker.PROFILE_FINGERPRINT))
        assertEquals("SIZE_ONLY", data.getString(BisyncPreviewAdmissionWorker.COMPARISON_MODE))
        assertEquals(
            BisyncPreviewResyncMode.PATH2.wireValue,
            data.getString(BisyncPreviewAdmissionWorker.ABSENT_STATE_MODE)
        )
        assertTrue(data.getBoolean(BisyncPreviewAdmissionWorker.LEGACY_REVIEW_CONFIRMED, false))
        assertFalse(data.keyValueMap.values.any { value ->
            value is String && (value.contains("/storage/") || value.contains("remote:"))
        })
    }
}
