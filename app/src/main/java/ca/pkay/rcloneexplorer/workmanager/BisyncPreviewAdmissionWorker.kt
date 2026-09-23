package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.BisyncComparisonMode
import ca.pkay.rcloneexplorer.Database.BisyncNativeState
import ca.pkay.rcloneexplorer.Database.BisyncPreflightCoordinator
import ca.pkay.rcloneexplorer.Database.BisyncPreviewCommandBuilder
import ca.pkay.rcloneexplorer.Database.BisyncPreviewIdentity
import ca.pkay.rcloneexplorer.Database.BisyncPreviewRepository
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperationState
import ca.pkay.rcloneexplorer.Database.BisyncPreviewResyncMode
import ca.pkay.rcloneexplorer.Database.ProfileMode
import ca.pkay.rcloneexplorer.Database.ProfileReadiness
import ca.pkay.rcloneexplorer.Database.ProfileRepository
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications.Companion.GROUP_ID
import java.util.concurrent.TimeUnit

/** Runs read-only preflight as a durable owner before creating or dispatching the preview owner. */
class BisyncPreviewAdmissionWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    companion object {
        const val PROFILE_ID = "BISYNC_ADMISSION_PROFILE_ID"
        const val PROFILE_REVISION = "BISYNC_ADMISSION_PROFILE_REVISION"
        const val PROFILE_FINGERPRINT = "BISYNC_ADMISSION_PROFILE_FINGERPRINT"
        const val COMPARISON_MODE = "BISYNC_ADMISSION_COMPARISON_MODE"
        const val MAX_DELETE_PERCENT = "BISYNC_ADMISSION_MAX_DELETE_PERCENT"
        const val MAX_DELETE_COUNT = "BISYNC_ADMISSION_MAX_DELETE_COUNT"
        const val ABSENT_STATE_MODE = "BISYNC_ADMISSION_ABSENT_STATE_MODE"
        const val LEGACY_REVIEW_CONFIRMED = "BISYNC_ADMISSION_LEGACY_REVIEW_CONFIRMED"
        const val PREVIEW_ID = "BISYNC_ADMISSION_PREVIEW_ID"
        const val OUTCOME = "BISYNC_ADMISSION_OUTCOME"
    }

    private val appContext = context.applicationContext
    private val cancellation = CancellationSignal()
    private val profiles = ProfileRepository(appContext)

    override fun doWork(): Result {
        val profileId = inputData.getString(PROFILE_ID) ?: return failure("REQUEST_INVALID")
        val revision = inputData.getLong(PROFILE_REVISION, -1L)
        val fingerprint = inputData.getString(PROFILE_FINGERPRINT) ?: return failure("REQUEST_INVALID")
        val comparison = BisyncComparisonMode.values().firstOrNull {
            it.wireValue == inputData.getString(COMPARISON_MODE)
        } ?: return failure("REQUEST_INVALID")
        val maxDeletePercent = inputData.getInt(MAX_DELETE_PERCENT, -1)
        val maxDeleteCount = inputData.getInt(MAX_DELETE_COUNT, -1)
        val absentStateMode = BisyncPreviewResyncMode.fromWireValue(inputData.getString(ABSENT_STATE_MODE))
            ?: return failure("REQUEST_INVALID")
        if (!inputData.getBoolean(LEGACY_REVIEW_CONFIRMED, false) || revision <= 0L ||
            !Regex("^[0-9a-f]{64}$").matches(fingerprint) || maxDeletePercent !in 1..100 || maxDeleteCount <= 0) {
            return failure("REQUEST_INVALID")
        }

        return try {
            prepareForeground(profileId)
            if (isStopped || cancellation.isCanceled) return failure("CANCELLED")
            val before = profiles.get(profileId) ?: return failure("PROFILE_CHANGED")
            if (before.mode != ProfileMode.BISYNC || before.revision != revision || before.fingerprint != fingerprint) {
                return failure("PROFILE_CHANGED")
            }

            val existing = BisyncPreviewRepository(appContext).active(profileId)
            if (existing != null) {
                val identity = existing.identity
                val sameRequest = existing.state == BisyncPreviewOperationState.QUEUED &&
                    identity.profileRevision == revision && identity.profileFingerprint == fingerprint &&
                    identity.engineRef == before.engineRef && identity.comparisonMode == comparison &&
                    identity.maxDeletePercent == maxDeletePercent && identity.maxDeleteCount == maxDeleteCount &&
                    (identity.nativeState != BisyncNativeState.ABSENT || identity.initializationMode == absentStateMode)
                if (!sameRequest) return failure("PREVIEW_REVIEW_REQUIRED")
                val resumed = BisyncPreviewWorkScheduler(appContext).enqueue(identity)
                return Result.success(Data.Builder()
                    .putString(PREVIEW_ID, resumed.previewId)
                    .putString(OUTCOME, "PREVIEW_DISPATCHED")
                    .build())
            }

            val run = BisyncPreflightCoordinator(appContext, Rclone(appContext)).run(
                profileId = profileId,
                expectedRevision = revision,
                expectedProfileFingerprint = fingerprint,
                comparisonMode = comparison,
                maxDeletePercent = maxDeletePercent,
                maxDeleteCount = maxDeleteCount,
                legacyMigrationConfirmed = true,
                cancellationSignal = cancellation
            )
            if (isStopped || cancellation.isCanceled) return failure("CANCELLED")

            val result = run.policyResult
            val input = run.input
            if (result.reason != null || input == null || run.executionSnapshot == null ||
                result.readiness !in setOf(ProfileReadiness.READY, ProfileReadiness.INITIALIZATION_REQUIRED)) {
                return failure(result.reason?.wireValue ?: "PREFLIGHT_BLOCKED")
            }

            val current = profiles.get(profileId) ?: return failure("PROFILE_CHANGED")
            if (current.revision != revision || current.fingerprint != fingerprint || current.mode != ProfileMode.BISYNC) {
                return failure("PROFILE_CHANGED")
            }
            val initializationMode = if (input.nativeState == BisyncNativeState.ABSENT) absentStateMode else null
            val identity = BisyncPreviewIdentity.fromPreflight(current, input, result, initializationMode)
            if (!BisyncPreviewCommandBuilder.supportsPreview(identity.engineRef, identity.nativeState)) {
                return failure("ENGINE_UNSUPPORTED")
            }
            if (isStopped || cancellation.isCanceled) return failure("CANCELLED")

            val queued = BisyncPreviewWorkScheduler(appContext).enqueue(identity)
            Result.success(Data.Builder()
                .putString(PREVIEW_ID, queued.previewId)
                .putString(OUTCOME, "PREVIEW_DISPATCHED")
                .build())
        } catch (_: Exception) {
            failure("PREFLIGHT_FAILED")
        }
    }

    override fun onStopped() {
        cancellation.cancel()
        super.onStopped()
    }

    private fun failure(reason: String): Result = Result.failure(
        Data.Builder()
            .putString(OUTCOME, reason.takeIf { Regex("^[A-Z0-9_]{1,64}$").matches(it) } ?: "PREVIEW_FAILED")
            .build()
    )

    private fun prepareForeground(profileId: String) {
        GenericSyncNotification(appContext).setNotificationChannel(
            SyncServiceNotifications.CHANNEL_ID,
            appContext.getString(R.string.sync_service_notification_channel_title),
            appContext.getString(R.string.sync_service_notification_channel_description),
            GROUP_ID,
            appContext.getString(R.string.sync_service_notification_group)
        )
        val notification: Notification = NotificationCompat.Builder(appContext, SyncServiceNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_twotone_rounded_cloud_sync_24)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(appContext.getString(R.string.sync_service_notification_channel_description))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val notificationId = (profileId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        val foreground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
        setForegroundAsync(foreground).get(10, TimeUnit.SECONDS)
    }
}
