package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Database.BisyncComparisonMode
import ca.pkay.rcloneexplorer.Database.BisyncPreviewResyncMode
import ca.pkay.rcloneexplorer.Database.ProfileMode
import ca.pkay.rcloneexplorer.Database.ProfileRecord

/** Keeps endpoint-bearing profile fields out of persisted WorkManager requests by construction. */
internal object BisyncPreviewAdmissionData {
    fun build(
        profileId: String,
        profileRevision: Long,
        profileFingerprint: String,
        comparisonMode: BisyncComparisonMode,
        maxDeletePercent: Int,
        maxDeleteCount: Int,
        absentStateMode: BisyncPreviewResyncMode,
        legacyMigrationConfirmed: Boolean
    ): Data = Data.Builder()
        .putString(BisyncPreviewAdmissionWorker.PROFILE_ID, profileId)
        .putLong(BisyncPreviewAdmissionWorker.PROFILE_REVISION, profileRevision)
        .putString(BisyncPreviewAdmissionWorker.PROFILE_FINGERPRINT, profileFingerprint)
        .putString(BisyncPreviewAdmissionWorker.COMPARISON_MODE, comparisonMode.wireValue)
        .putInt(BisyncPreviewAdmissionWorker.MAX_DELETE_PERCENT, maxDeletePercent)
        .putInt(BisyncPreviewAdmissionWorker.MAX_DELETE_COUNT, maxDeleteCount)
        .putString(BisyncPreviewAdmissionWorker.ABSENT_STATE_MODE, absentStateMode.wireValue)
        .putBoolean(BisyncPreviewAdmissionWorker.LEGACY_REVIEW_CONFIRMED, legacyMigrationConfirmed)
        .build()
}

/** Persists a path-free preflight request under a unique WorkManager owner. */
class BisyncPreviewAdmissionScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun enqueue(
        profile: ProfileRecord,
        comparisonMode: BisyncComparisonMode,
        maxDeletePercent: Int,
        maxDeleteCount: Int,
        absentStateMode: BisyncPreviewResyncMode,
        legacyMigrationConfirmed: Boolean
    ): OneTimeWorkRequest {
        require(profile.mode == ProfileMode.BISYNC) { "Preview admission requires a Bisync profile" }
        require(profile.revision > 0L && Regex("^[0-9a-f]{64}$").matches(profile.fingerprint)) {
            "Preview admission requires a valid profile snapshot"
        }
        require(maxDeletePercent in 1..100 && maxDeleteCount > 0) { "Invalid preview deletion limits" }
        require(legacyMigrationConfirmed) { "Explicit legacy-profile review is required" }

        val request = OneTimeWorkRequestBuilder<BisyncPreviewAdmissionWorker>()
            .setInputData(BisyncPreviewAdmissionData.build(
                profile.profileId,
                profile.revision,
                profile.fingerprint,
                comparisonMode,
                maxDeletePercent,
                maxDeleteCount,
                absentStateMode,
                legacyMigrationConfirmed
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .addTag(ADMISSION_TAG)
            .addTag(profileTag(profile.profileId))
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(profile.profileId), ExistingWorkPolicy.KEEP, request
        )
        return request
    }

    fun cancel(profileId: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(profileId))
    }

    companion object {
        const val ADMISSION_TAG = "bisync-preview-admission"
        private const val WORK_NAME_PREFIX = "bisync-preview-admission-"

        @JvmStatic
        fun workName(profileId: String) = "$WORK_NAME_PREFIX$profileId"

        @JvmStatic
        fun profileTag(profileId: String) = BisyncPreviewWorkScheduler.profileTag(profileId)
    }
}
