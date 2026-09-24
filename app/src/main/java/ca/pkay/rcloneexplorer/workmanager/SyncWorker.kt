package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Database.RunRejectedException
import ca.pkay.rcloneexplorer.Database.RunRepository
import ca.pkay.rcloneexplorer.Database.RunState
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Log2File
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.ReportNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications.Companion.GROUP_ID
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.TransferLocks
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import kotlinx.serialization.json.Json
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.Random
import java.util.concurrent.TimeUnit

class SyncWorker (private var mContext: Context, workerParams: WorkerParameters): Worker(mContext, workerParams) {

    companion object {
        const val TASK_ID = "TASK_ID"
        const val TASK_EPHEMERAL = "TASK_EPHEMERAL"
        const val RUN_ID = "RUN_ID"
        const val RUN_OWNER_TOKEN = "RUN_OWNER_TOKEN"
        private const val TAG = "SyncWorker"

        //those Extras do not follow the above schema, because they are exposed to external applications
        //That means shorter values make it easier to use. There is no other technical reason
        const val TASK_SYNC_ACTION = "START_TASK"
        const val TASK_CANCEL_ACTION = "CANCEL_TASK"
        const val EXTRA_TASK_ID = "task"

        // Todo: Allow SyncWorker to run in silent mode, or remove this!
        const val EXTRA_TASK_SILENT = "notification"
    }



    internal enum class FAILURE_REASON {
        NO_FAILURE, NO_UNMETERED, NO_CONNECTION, RCLONE_ERROR, CONNECTIVITY_CHANGED,
        CANCELLED, NO_TASK, UNSUPPORTED_DIRECTION, RUN_NOT_ADMITTED, FOREGROUND_UNAVAILABLE
    }

    // Objects
    private var mRclone = Rclone(mContext)
    private var mDatabase = DatabaseHandler(mContext)
    private val mRunRepository = RunRepository(mContext)
    private var mNotificationManager = SyncServiceNotifications(mContext)
    private val mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)


    private var log2File: Log2File? = null



    // States
    private val sIsLoggingEnabled = mPreferences.getBoolean(getString(R.string.pref_key_logs), false)
    private var sConnectivityChanged = false

    private var sRcloneProcess: NativeExecutionHandle? = null
    private val nativeLaunchLock = Any()
    private val receiverLock = Any()
    private var receiverRegistered = false
    @Volatile private var stopRequested = false
    private val statusObject = StatusObject(mContext)
    private var failureReason = FAILURE_REASON.NO_FAILURE
    private var endNotificationAlreadyPosted = false
    private var silentRun = false
    private val ongoingNotificationID = Random().nextInt()
    private var durableRunId: String? = null
    private var durableRunOwnerToken: String? = null
    private var durableRunClaimed = false
    private var durableRunFinished = false
    private var nativeExitCode: Int? = null
    private var nativeCompletionUnconfirmed = false


    // Task
    private lateinit var mTask: Task
    private var mTitle: String = mContext.getString(R.string.sync_service_notification_startingsync)



    override fun doWork(): Result {

        prepareNotifications()
        return try {
            var task: Task? = null
            durableRunId = inputData.getString(RUN_ID)
            durableRunOwnerToken = inputData.getString(RUN_OWNER_TOKEN)

            if (inputData.keyValueMap.containsKey(TASK_ID)) {
                val id = inputData.getLong(TASK_ID, -1)
                task = mDatabase.getTask(id)
                if (task != null) {
                    if (durableRunId == null || durableRunOwnerToken == null) {
                        try {
                            val compatibilityRun = mRunRepository.queueLegacyTask(id)
                            durableRunId = compatibilityRun.runId
                            durableRunOwnerToken = compatibilityRun.ownerToken
                        } catch (e: RunRejectedException) {
                            failureReason = FAILURE_REASON.RUN_NOT_ADMITTED
                            log("Legacy task was not admitted: " + e.message)
                        }
                    }
                }
            }

            if (inputData.keyValueMap.containsKey(TASK_EPHEMERAL)) {
                val taskString = inputData.getString(TASK_EPHEMERAL) ?: ""
                if (taskString.isNotEmpty()) {
                    try {
                        task = Json.decodeFromString<Task>(taskString)
                    } catch (e: Exception) {
                        log("Could not deserialize ephemeral sync task")
                    }
                }
                if (task != null) {
                    if (durableRunId == null || durableRunOwnerToken == null) {
                        try {
                            val compatibilityRun = mRunRepository.queueEphemeralTask(task)
                            durableRunId = compatibilityRun.runId
                            durableRunOwnerToken = compatibilityRun.ownerToken
                        } catch (e: RunRejectedException) {
                            failureReason = FAILURE_REASON.RUN_NOT_ADMITTED
                            log("Ephemeral sync task was not admitted: " + e.message)
                        }
                    }
                }
            }

            if (task == null) {
                failureReason = FAILURE_REASON.NO_TASK
                finishUnstartableQueuedRun("Sync request data was missing or invalid")
                postSync()
                return Result.failure()
            }

            mTask = task
            if (failureReason == FAILURE_REASON.NO_FAILURE) {
                if (durableRunId == null || durableRunOwnerToken == null) {
                    failureReason = FAILURE_REASON.RUN_NOT_ADMITTED
                    log("Sync request has no durable run owner")
                } else {
                    try {
                        mRunRepository.claim(durableRunId!!, durableRunOwnerToken!!)
                        durableRunClaimed = true
                    } catch (e: RunRejectedException) {
                        failureReason = FAILURE_REASON.RUN_NOT_ADMITTED
                        log("Durable run claim was rejected")
                    }
                }
            }

            if (failureReason == FAILURE_REASON.NO_FAILURE) {
                registerBroadcastReceivers()
                val notification = mNotificationManager.updateSyncNotification(
                    mTitle,
                    mTitle,
                    ArrayList(),
                    0,
                    ongoingNotificationID
                )
                val promotionFailure = if (notification == null) {
                    IllegalStateException("Sync foreground notification was unavailable")
                } else {
                    promoteForeground(notification)
                }
                if (promotionFailure != null) {
                    failureReason = FAILURE_REASON.FOREGROUND_UNAVAILABLE
                    log("Foreground promotion failed before native sync: " + promotionFailure.message)
                }
            }

            if (failureReason == FAILURE_REASON.NO_FAILURE) {
                handleTask()
            }
            postSync()

            // Indicate whether the work finished successfully with the Result.
            if (failureReason == FAILURE_REASON.NO_FAILURE) Result.success() else Result.failure()
        } finally {
            unregisterBroadcastReceiver()
        }
    }

    override fun onStopped() {
        synchronized(nativeLaunchLock) {
            stopRequested = true
            sRcloneProcess?.cancel()
        }
        super.onStopped()
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_sync_cancelled))
        SyncLog.info(mContext, mTitle, statusObject.toString())
        failureReason = FAILURE_REASON.CANCELLED
        finishWork()
    }

    private fun finishWork() {
        synchronized(nativeLaunchLock) {
            stopRequested = true
            sRcloneProcess?.cancel()
        }
        sRcloneProcess?.cancelAndAwait(null, null)?.let {
            if (!it.isConfirmed) nativeCompletionUnconfirmed = true
        }
        unregisterBroadcastReceiver()
        postSync()
    }

    private fun launchOwnedIfRunning(launch: () -> NativeExecutionHandle?): NativeExecutionHandle? {
        synchronized(nativeLaunchLock) {
            if (stopRequested || isStopped) {
                failureReason = FAILURE_REASON.CANCELLED
                return null
            }
            val started = launch()
            sRcloneProcess = started
            if (stopRequested || isStopped) {
                started?.cancel()
                failureReason = FAILURE_REASON.CANCELLED
            }
            return started
        }
    }

    private fun handleTask() {
        mTitle = mTask.title
        mNotificationManager.setCancelId(id)
        val remoteItem = RemoteItem(mTask.remoteId, mTask.remoteType, "")

        if (mTask.title == "") {
            mTitle = mTask.remotePath
        }
        statusObject.syncDirection = mTask.direction
        if (!SyncDirectionObject.isRegularSyncWorkerDirectionSupported(mTask.direction)) {
            failureReason = FAILURE_REASON.UNSUPPORTED_DIRECTION
            return
        }
        if(arePreconditionsMet()) {
            val transferLocks = TransferLocks.acquire(mContext, "sync")
            var locksAttachedToExecution = false
            try {
                val taskFilter = if(mTask.filterId != null ) mDatabase.getFilter(mTask.filterId!!) else null;
                val taskFilterList = taskFilter?.getFilters() ?: ArrayList()
                val isCloudToCloud = mTask.direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                        || mTask.direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE
                if (stopRequested || isStopped) {
                    failureReason = FAILURE_REASON.CANCELLED
                    return
                }
                if (durableRunId != null && durableRunOwnerToken != null &&
                    !mRunRepository.markRunning(durableRunId!!, durableRunOwnerToken!!)) {
                    failureReason = FAILURE_REASON.RCLONE_ERROR
                    log("Sync: durable run was no longer owned")
                    return
                }
                launchOwnedIfRunning { if (isCloudToCloud) {
                    val remoteItem2 = RemoteItem(mTask.remoteId2, mTask.remoteType2, "")
                    mRclone.syncOwned(
                        remoteItem,
                        mTask.remotePath,
                        remoteItem2,
                        mTask.remotePath2,
                        mTask.direction,
                        mTask.md5sum,
                        taskFilterList,
                        mTask.deleteExcluded,
                        mTask.transfers?.toString()
                    )
                } else {
                    mRclone.syncOwned(
                        remoteItem,
                        mTask.localPath,
                        mTask.remotePath,
                        mTask.direction,
                        mTask.md5sum,
                        taskFilterList,
                        mTask.deleteExcluded,
                        mTask.transfers?.toString()
                    )
                } }
                if (sRcloneProcess == null) {
                    if (failureReason != FAILURE_REASON.CANCELLED) {
                        failureReason = FAILURE_REASON.RCLONE_ERROR
                    }
                    log("Sync: Rclone process could not be started for direction ${mTask.direction}")
                    return
                }
                if (transferLocks != null) {
                    locksAttachedToExecution = sRcloneProcess!!.attachResource(transferLocks)
                }
                if (stopRequested || isStopped) {
                    val outcome = sRcloneProcess?.cancelAndAwait(null, null)
                    nativeCompletionUnconfirmed = outcome == null || !outcome.isConfirmed
                    failureReason = FAILURE_REASON.CANCELLED
                    return
                }
                handleSync(mTitle)
                if (failureReason == FAILURE_REASON.NO_FAILURE && isCloudToCloud) {
                    // Refresh any open FileExplorer on the destination remote so copied content appears.
                    sendUploadFinishedBroadcast(mTask.remoteId2, mTask.remotePath2)
                } else if (failureReason == FAILURE_REASON.NO_FAILURE) {
                    sendUploadFinishedBroadcast(remoteItem.name, mTask.remotePath)
                }
            } finally {
                if (!locksAttachedToExecution) {
                    transferLocks?.release()
                } else if (sRcloneProcess?.getOutcome() == null) {
                    // An unexpected worker exception must still give the owner a chance to reap
                    // before the surrounding scope exits and releases no native resources.
                    sRcloneProcess?.close()
                }
            }
        }
    }

    private fun handleSync(title: String) {
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_start_sync))
        val execution = sRcloneProcess
        if (execution != null) {
            val outcome = execution.await(
                NativeExecutionHandle.NO_TIMEOUT,
                null,
                NativeExecutionHandle.LineSink { line -> handleNativeLogLine(title, line) }
            )
            nativeExitCode = outcome.exitCode
            nativeCompletionUnconfirmed = !outcome.isConfirmed
            if (!outcome.isSuccess()) {
                failureReason = when (outcome.state) {
                    NativeExecutionHandle.TerminalState.CANCELLED,
                    NativeExecutionHandle.TerminalState.INTERRUPTED -> FAILURE_REASON.CANCELLED
                    else -> FAILURE_REASON.RCLONE_ERROR
                }
            }
        } else {
            log("Sync: No Rclone Process!")
        }
        mNotificationManager.cancelSyncNotification(ongoingNotificationID)
    }

    private fun handleNativeLogLine(title: String, line: String) {
        try {
            val logline = JSONObject(line)
            if (logline.optString("level") == "error" && sIsLoggingEnabled) {
                log2File?.log(line)
            }

            // Process all log lines for stats/progress updates, not just error/warning.
            statusObject.parseLoglineToStatusObject(logline)

            if (statusObject.notificationContent.isNotEmpty()) {
                updateForegroundNotification(mNotificationManager.updateSyncNotification(
                    title,
                    statusObject.notificationContent,
                    statusObject.notificationBigText,
                    statusObject.notificationPercent,
                    ongoingNotificationID
                ))
            }
        } catch (e: JSONException) {
            FLog.e(TAG, "SyncService-Error: the offending line: $line")
        }
    }

    private fun postSync() {
        if (endNotificationAlreadyPosted) {
            return
        }
        recordDurableRunOutcome()
        if (durableRunClaimed && durableRunId != null && durableRunOwnerToken != null && !durableRunFinished) {
            failureReason = FAILURE_REASON.RCLONE_ERROR
        }
        if (!::mTask.isInitialized) {
            endNotificationAlreadyPosted = true
            return
        }
        if (silentRun) {
            return
        }

        val notificationId = System.currentTimeMillis().toInt()

        var content = mContext.getString(R.string.operation_failed_unknown, mTitle)
        when (failureReason) {
            FAILURE_REASON.NO_FAILURE -> {
                showSuccessNotification(notificationId)
                followupTask(mTask.onSuccessFollowup)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.CANCELLED -> {
                showCancelledNotification(notificationId)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.NO_TASK -> {
                content = getString(R.string.operation_failed_notask)
            }
            FAILURE_REASON.CONNECTIVITY_CHANGED -> {
                content = mContext.getString(R.string.operation_failed_data_change, mTitle)
            }
            FAILURE_REASON.NO_UNMETERED -> {
                content = mContext.getString(R.string.operation_failed_no_unmetered, mTitle)
            }
            FAILURE_REASON.FOREGROUND_UNAVAILABLE,
            FAILURE_REASON.RUN_NOT_ADMITTED -> {
                content = mContext.getString(R.string.operation_failed_unknown, mTitle)
            }
            FAILURE_REASON.NO_CONNECTION -> {
                content = mContext.getString(R.string.operation_failed_no_connection, mTitle)
            }
            FAILURE_REASON.RCLONE_ERROR -> {
                content = mContext.getString(R.string.operation_failed_unknown_rclone_error, mTitle)
            }
            FAILURE_REASON.UNSUPPORTED_DIRECTION -> {
                content = mContext.getString(R.string.operation_failed_unsupported_direction, mTitle)
            }
        }
        followupTask(mTask.onFailFollowup)
        showFailNotification(notificationId, content)
        endNotificationAlreadyPosted = true
        finishWork()
    }

    private fun recordDurableRunOutcome() {
        if (!durableRunClaimed || durableRunFinished || durableRunId == null || durableRunOwnerToken == null) {
            return
        }
        val state = if (nativeCompletionUnconfirmed) RunState.RECOVERY_REQUIRED else when (failureReason) {
            FAILURE_REASON.RCLONE_ERROR -> RunState.FAILED
            FAILURE_REASON.NO_FAILURE -> {
                if (nativeExitCode == 0) RunState.SUCCESS else RunState.FAILED
            }
            FAILURE_REASON.CANCELLED -> RunState.CANCELLED
            FAILURE_REASON.NO_UNMETERED,
            FAILURE_REASON.NO_CONNECTION,
            FAILURE_REASON.CONNECTIVITY_CHANGED,
            FAILURE_REASON.FOREGROUND_UNAVAILABLE -> RunState.DEFERRED
            FAILURE_REASON.UNSUPPORTED_DIRECTION,
            FAILURE_REASON.RUN_NOT_ADMITTED -> RunState.BLOCKED
            else -> RunState.FAILED
        }
        val reason = if (nativeCompletionUnconfirmed) "Native exit was not confirmed; profile requires recovery" else when (failureReason) {
            FAILURE_REASON.NO_FAILURE -> if (state == RunState.SUCCESS) null else "Native completion was not confirmed"
            FAILURE_REASON.CANCELLED -> "Cancellation requested"
            FAILURE_REASON.NO_UNMETERED -> "Unmetered network is required"
            FAILURE_REASON.NO_CONNECTION -> "No usable network connection"
            FAILURE_REASON.CONNECTIVITY_CHANGED -> "Connectivity changed during execution"
            FAILURE_REASON.FOREGROUND_UNAVAILABLE -> "Android did not confirm foreground execution; no native sync was started"
            FAILURE_REASON.RUN_NOT_ADMITTED -> "The sync request was rejected before native execution"
            FAILURE_REASON.UNSUPPORTED_DIRECTION -> "Legacy direction requires reviewed repair"
            else -> "Native sync failed"
        }
        durableRunFinished = mRunRepository.finish(
            durableRunId!!,
            durableRunOwnerToken!!,
            state,
            reason,
            successfulItems = if (state == RunState.SUCCESS) statusObject.getTotalTransfers().toLong() else null,
            failedItems = if (state == RunState.SUCCESS) 0L else null,
            conflictItems = null,
            unknownItems = null
        )
    }

    private fun finishUnstartableQueuedRun(reason: String) {
        val runId = durableRunId ?: return
        val ownerToken = durableRunOwnerToken ?: return
        try {
            val finished = mRunRepository.finishQueuedBeforeExecution(
                runId,
                ownerToken,
                RunState.BLOCKED,
                reason
            )
            if (!finished) {
                log("Unstartable request no longer owns a queued run; leaving its state unchanged")
            }
        } catch (failure: Exception) {
            FLog.e(TAG, "Unable to persist blocked sync request", failure)
        }
    }

    private fun showCancelledNotification(notificationId: Int) {
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_failed_cancelled))
        mNotificationManager.showCancelledNotificationOrReport(
            mTitle,
            notificationId,
            mTask.id
        )
    }

    private fun showSuccessNotification(notificationId: Int) {
        //Todo: Show sync-errors in notification. Also see line 169

        var message = generateSuccessMessage(statusObject)
        mNotificationManager.showSuccessNotificationOrReport(
            mTitle,
            message,
            notificationId
        )

        message += """
                        
        Est. Speed: ${statusObject.getEstimatedAverageSpeed()}
        Avg. Speed: ${statusObject.getLastItemAverageSpeed()}
                        """.trimIndent()
        SyncLog.info(mContext, mContext.getString(R.string.operation_success, mTitle), message)
    }

    // this is currently only a useless mapper. It is supposed to keep this worker in sync with the ephemeral one.
    // when they are merged eventually, this can be easily extracted.
    private fun generateSuccessMessage(statusObject: StatusObject): String {
        var message = mContext.resources.getQuantityString(
                R.plurals.operation_success_description,
                statusObject.getTotalTransfers(),
                mTitle,
                statusObject.getTotalSize(),
                statusObject.getTotalTransfers()
        )
        if (statusObject.getTotalTransfers() == 0) {
            message = mContext.resources.getString(R.string.operation_success_description_zero)
        }
        if (statusObject.getDeletions() > 0) {
            message += """
                        
                        ${
                mContext.getString(
                        R.string.operation_success_description_deletions_prefix,
                        statusObject.getDeletions()
                )
            }
                        """.trimIndent()
        }
        return message
    }

    private fun showFailNotification(notificationId: Int, content: String, wasCancelled: Boolean = false) {
        var text = content
        //Todo: check if we should also add errors on success
        statusObject.printErrors()
        val errors = statusObject.getAllErrorMessages()
        if (errors.isNotEmpty()) {
            text += """
                        
                        
                        
                        ${statusObject.getAllErrorMessages()}
                        """.trimIndent()
        }

        var notifyTitle = mContext.getString(R.string.operation_failed)
        if (wasCancelled) {
            notifyTitle = mContext.getString(R.string.operation_failed_cancelled)
        }
        SyncLog.error(mContext, notifyTitle, "$mTitle: $text")
        mNotificationManager.showFailedNotificationOrReport(
            mTitle,
            text,
            notificationId,
            mTask.id
        )
    }

    private fun arePreconditionsMet(): Boolean {
        val connection = WifiConnectivitiyUtil.dataConnection(this.applicationContext)
        if (mTask.wifionly && connection === WifiConnectivitiyUtil.Connection.METERED) {
            failureReason = FAILURE_REASON.NO_UNMETERED
            return false
        } else if (connection === WifiConnectivitiyUtil.Connection.DISCONNECTED || connection === WifiConnectivitiyUtil.Connection.NOT_AVAILABLE) {
            failureReason = FAILURE_REASON.NO_CONNECTION
            return false
        }

        return true
    }

    private fun prepareNotifications() {

        GenericSyncNotification(mContext).setNotificationChannel(
                SyncServiceNotifications.CHANNEL_ID,
                getString(R.string.sync_service_notification_channel_title),
                getString(R.string.sync_service_notification_channel_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            SyncServiceNotifications.CHANNEL_SUCCESS_ID,
            getString(R.string.sync_service_notification_channel_success_title),
            getString(R.string.sync_service_notification_channel_success_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            SyncServiceNotifications.CHANNEL_FAIL_ID,
            getString(R.string.sync_service_notification_channel_fail_title),
            getString(R.string.sync_service_notification_channel_fail_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )
        GenericSyncNotification(mContext).setNotificationChannel(
            ReportNotifications.CHANNEL_REPORT_ID,
            getString(R.string.sync_service_notification_channel_report_title),
            getString(R.string.sync_service_notification_channel_report_description),
                GROUP_ID,
                getString(R.string.sync_service_notification_group)
        )

    }

    private fun sendUploadFinishedBroadcast(remote: String, path: String?) {
        val intent = Intent()
        intent.action = getString(R.string.background_service_broadcast)
        intent.putExtra(getString(R.string.background_service_broadcast_data_remote), remote)
        intent.putExtra(getString(R.string.background_service_broadcast_data_path), path)
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent)
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun updateForegroundNotification(notification: Notification?) {
        notification?.let {
            setForegroundAsync(foregroundInfo(it))
        }
    }

    private fun promoteForeground(notification: Notification): Throwable? = try {
        awaitForegroundPromotion(
            setForegroundAsync(foregroundInfo(notification)),
            timeout = 10,
            unit = TimeUnit.SECONDS
        )
    } catch (failure: Exception) {
        failure
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ongoingNotificationID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ongoingNotificationID, notification)
        }


    private fun log(message: String) {
        FLog.e(TAG, "SyncWorker: $message")
    }

    private fun getString(@StringRes resId: Int): String {
        return mContext.getString(resId)
    }

    private fun registerBroadcastReceivers() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
        synchronized(receiverLock) {
            if (!stopRequested && !isStopped && !receiverRegistered) {
                mContext.registerReceiver(connectivityChangeBroadcastReceiver, intentFilter)
                receiverRegistered = true
            }
        }
    }

    private fun unregisterBroadcastReceiver() {
        synchronized(receiverLock) {
            if (receiverRegistered) {
                mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
                receiverRegistered = false
            }
        }
    }

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(endNotificationAlreadyPosted){
                    return
                }
                sConnectivityChanged = true
                failureReason = FAILURE_REASON.CONNECTIVITY_CHANGED
            }
        }

    private fun followupTask(followUpTaskID: Long?) {
        if (followUpTaskID == null || followUpTaskID == -1L) {
            return
        }
        Thread.sleep(1000)
        SyncManager(mContext).queue(followUpTaskID)
    }
}
