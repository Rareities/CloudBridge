package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.os.CancellationSignal
import android.os.Build
import android.app.Notification
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.BisyncNativeState
import ca.pkay.rcloneexplorer.Database.BisyncPreflightCoordinator
import ca.pkay.rcloneexplorer.Database.BisyncPreviewFailureCode
import ca.pkay.rcloneexplorer.Database.BisyncPreviewIdentity
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperation
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperationState
import ca.pkay.rcloneexplorer.Database.BisyncPreviewParseResult
import ca.pkay.rcloneexplorer.Database.BisyncPreviewRepository
import ca.pkay.rcloneexplorer.Database.BisyncPreviewUnavailableReason
import ca.pkay.rcloneexplorer.Database.BisyncPreflightExecutionSnapshot
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.ProfileMode
import ca.pkay.rcloneexplorer.Database.ProfileReadiness
import ca.pkay.rcloneexplorer.Database.ProfileRecord
import ca.pkay.rcloneexplorer.Database.ProfileRepository
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications.Companion.GROUP_ID
import java.util.concurrent.TimeUnit

/**
 * Revalidates a queued preview from current read-only evidence, claims the exact identity, then
 * executes only the owned dry-run adapter. A preview result is never a sync authorization.
 */
class BisyncPreviewWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    companion object {
        const val PREVIEW_ID = "BISYNC_PREVIEW_ID"
        const val OWNER_TOKEN = "BISYNC_PREVIEW_OWNER_TOKEN"
    }

    private val appContext = context.applicationContext
    private val previews = BisyncPreviewRepository(appContext)
    private val profiles = ProfileRepository(appContext)
    private val cancellation = CancellationSignal()
    @Volatile private var claimed: BisyncPreviewOperation? = null
    @Volatile private var nativeLaunchAttempted = false

    override fun doWork(): Result {
        var previewId: String? = null
        var ownerToken: String? = null
        try {
            val id = inputData.getString(PREVIEW_ID) ?: return Result.failure()
            val token = inputData.getString(OWNER_TOKEN) ?: return Result.failure()
            previewId = id
            ownerToken = token
            prepareForeground(id)
            BisyncPreviewProcessReconciler.ensure(appContext)

            val queued = previews.get(id)
            if (queued == null || queued.ownerToken != token ||
                queued.state != BisyncPreviewOperationState.QUEUED) return Result.failure()
            if (isStopped || cancellation.isCanceled) {
                cancelQueued(id, token)
                return Result.failure()
            }

            val initial = loadCurrentProfileAndTask(queued.identity.profileId)
            if (initial == null || !matchesIdentity(initial.first, queued.identity)) {
                finishQueuedUnavailable(id, token, BisyncPreviewFailureCode.IDENTITY_CHANGED)
                return Result.failure()
            }

            val preflight = BisyncPreflightCoordinator(appContext, Rclone(appContext)).run(
                profileId = queued.identity.profileId,
                expectedRevision = queued.identity.profileRevision,
                expectedProfileFingerprint = queued.identity.profileFingerprint,
                comparisonMode = queued.identity.comparisonMode,
                maxDeletePercent = queued.identity.maxDeletePercent,
                maxDeleteCount = queued.identity.maxDeleteCount,
                // Queue admission requires recent successful preflight evidence; replay its
                // explicit migration confirmation only for this exact queued operation.
                legacyMigrationConfirmed = true,
                cancellationSignal = cancellation
            )
            if (isStopped || cancellation.isCanceled) {
                cancelQueued(id, token)
                return Result.failure()
            }
            val policy = preflight.policyResult
            val input = preflight.input
            val execution = preflight.executionSnapshot
            if (policy.reason != null || input == null || execution == null) {
                val failure = if (policy.reason?.name == "PROFILE_CHANGED" || policy.reason?.name == "ENGINE_CHANGED")
                    BisyncPreviewFailureCode.IDENTITY_CHANGED else BisyncPreviewFailureCode.NATIVE_STATE_UNRESOLVED
                finishQueuedUnavailable(id, token, failure)
                return Result.failure()
            }

            val current = loadCurrentProfileAndTask(queued.identity.profileId)
            if (current == null || !matchesIdentity(current.first, queued.identity)) {
                finishQueuedUnavailable(id, token, BisyncPreviewFailureCode.IDENTITY_CHANGED)
                return Result.failure()
            }
            val freshIdentity = try {
                BisyncPreviewIdentity.fromPreflight(
                    current.first, input, policy, queued.identity.initializationMode
                )
            } catch (_: IllegalArgumentException) {
                finishQueuedUnavailable(id, token, BisyncPreviewFailureCode.IDENTITY_CHANGED)
                return Result.failure()
            }
            val claimedOperation = try {
                previews.claim(id, token, freshIdentity)
            } catch (_: Exception) {
                finishQueuedUnavailable(id, token, BisyncPreviewFailureCode.IDENTITY_CHANGED)
                return Result.failure()
            }
            claimed = claimedOperation

            if (isStopped || cancellation.isCanceled) {
                finishClaimed(claimedOperation, freshIdentity,
                    unavailable(BisyncPreviewUnavailableReason.CANCELLED_BEFORE_START), true)
                return Result.failure()
            }
            val immediatelyCurrent = loadCurrentProfileAndTask(freshIdentity.profileId)
            if (immediatelyCurrent == null || !matchesIdentity(immediatelyCurrent.first, freshIdentity)) {
                finishClaimed(claimedOperation, null,
                    unavailable(BisyncPreviewUnavailableReason.REQUEST_REJECTED), true)
                return Result.failure()
            }

            nativeLaunchAttempted = true
            val nativeResult = Rclone(appContext).runBisyncPreview(
                claimedOperation.previewId,
                claimedOperation.ownerToken,
                claimedOperation.ownerGeneration,
                freshIdentity,
                execution.localPath,
                RemoteItem(execution.remoteId, execution.remoteType, ""),
                execution.remotePath,
                execution.filters,
                execution.deleteExcluded,
                execution.checksumRequested,
                cancellation
            )
            val latest = loadCurrentProfileAndTask(freshIdentity.profileId)
            val currentIdentity = if (latest != null && matchesIdentity(latest.first, freshIdentity))
                freshIdentity else null
            finishClaimed(claimedOperation, currentIdentity, nativeResult.parsed,
                nativeResult.processStoppedConfirmed)
            return if (nativeResult.parsed is BisyncPreviewParseResult.Available &&
                nativeResult.processStoppedConfirmed && nativeResult.scratchCleaned) Result.success()
            else Result.failure()
        } catch (_: Exception) {
            val id = previewId
            val token = ownerToken
            val running = claimed
            if (running != null) {
                finishClaimed(
                    running,
                    null,
                    unavailable(BisyncPreviewUnavailableReason.PROCESS_FAILED),
                    processStoppedConfirmed = !nativeLaunchAttempted
                )
            } else if (id != null && token != null) {
                finishQueuedUnavailable(id, token, BisyncPreviewFailureCode.PROCESS_FAILED)
            }
            return Result.failure()
        }
    }

    override fun onStopped() {
        cancellation.cancel()
        super.onStopped()
    }

    private fun loadCurrentProfileAndTask(profileId: String): Pair<ProfileRecord, Task>? {
        val selected = profiles.get(profileId) ?: return null
        if (selected.mode != ProfileMode.BISYNC) return null
        val taskId = selected.legacyTaskId ?: return null
        val handler = DatabaseHandler(appContext)
        val task = try {
            handler.getTask(taskId)
        } finally {
            handler.close()
        } ?: return null
        val current = profiles.ensureLegacyTask(task)
        return current to task
    }

    private fun matchesIdentity(profile: ProfileRecord, identity: BisyncPreviewIdentity): Boolean {
        val expectedReadiness = when (identity.nativeState) {
            BisyncNativeState.ABSENT -> ProfileReadiness.INITIALIZATION_REQUIRED
            BisyncNativeState.COMPATIBLE -> ProfileReadiness.READY
            else -> return false
        }
        return profile.mode == ProfileMode.BISYNC && profile.profileId == identity.profileId &&
            profile.revision == identity.profileRevision &&
            profile.fingerprint == identity.profileFingerprint && profile.engineRef == identity.engineRef &&
            profile.readiness == expectedReadiness
    }

    private fun finishQueuedUnavailable(id: String, token: String, code: BisyncPreviewFailureCode) {
        val status = if (code == BisyncPreviewFailureCode.IDENTITY_CHANGED)
            BisyncPreviewOperationState.STALE else BisyncPreviewOperationState.UNAVAILABLE
        try {
            previews.finishQueued(id, token, status, code)
        } catch (_: Exception) {
            // If storage is unavailable the durable QUEUED owner remains a conservative lock.
        }
    }

    private fun cancelQueued(id: String, token: String) {
        previews.finishQueued(
            id, token, BisyncPreviewOperationState.CANCELLED,
            BisyncPreviewFailureCode.CANCELLED_BEFORE_START
        )
    }

    private fun finishClaimed(
        operation: BisyncPreviewOperation,
        currentIdentity: BisyncPreviewIdentity?,
        parsed: BisyncPreviewParseResult,
        processStoppedConfirmed: Boolean
    ) {
        previews.finish(
            operation.previewId,
            operation.ownerToken,
            operation.ownerGeneration,
            currentIdentity,
            parsed,
            processStoppedConfirmed
        )
    }

    private fun prepareForeground(previewId: String) {
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
        val id = (previewId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
        setForegroundAsync(info).get(10, TimeUnit.SECONDS)
    }

    private fun unavailable(reason: BisyncPreviewUnavailableReason) =
        BisyncPreviewParseResult.Unavailable(reason)
}

/** Process-once recovery: only RUNNING rows from a previous app process are reconciled. */
private object BisyncPreviewProcessReconciler {
    @Volatile private var reconciled = false

    fun ensure(context: Context) {
        if (reconciled) return
        synchronized(this) {
            if (reconciled) return
            BisyncPreviewRepository(context).reconcileInterruptedRuns()
            reconciled = true
        }
    }
}
