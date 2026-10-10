package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.wifi.WifiManager
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
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
import java.util.concurrent.atomic.AtomicBoolean

class SyncWorker (private var mContext: Context, workerParams: WorkerParameters): Worker(mContext, workerParams) {

    companion object {
        const val TASK_ID = "TASK_ID"
        const val TASK_EPHEMERAL = "TASK_EPHEMERAL"
        const val FOLLOWUP_CHAIN = "FOLLOWUP_CHAIN"
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
        NO_FAILURE, NO_UNMETERED, NO_CONNECTION, RCLONE_ERROR, CONNECTIVITY_CHANGED, CANCELLED, NO_TASK, UNSUPPORTED_DIRECTION, DEADLINE
    }

    // Objects
    private var mRclone = Rclone(mContext)
    private var mDatabase = DatabaseHandler(mContext)
    private var mNotificationManager = SyncServiceNotifications(mContext)
    private val mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)


    private var log2File: Log2File? = null



    // States
    private val sIsLoggingEnabled = mPreferences.getBoolean(getString(R.string.pref_key_logs), false)
    private var sConnectivityChanged = false

    @Volatile private var sRcloneProcess: Process? = null
    private val statusObject = StatusObject(mContext)
    @Volatile private var failureReason = FAILURE_REASON.NO_FAILURE
    @Volatile private var endNotificationAlreadyPosted = false
    private val receiverRegistered = AtomicBoolean(false)
    private val terminalLock = Any()
    private var silentRun = false
    @Volatile private var deadline: ProcessDeadline? = null
    private var activeBisyncRun: ca.pkay.rcloneexplorer.bisync.BisyncSession.Run? = null
    private var nativeExitCode: Int? = null
    private var deferred = false
    private var temporaryExit = false
    private val automaticPolicy by lazy {
        inputData.getString("schedule_policy")?.let {
            Json.decodeFromString<ca.pkay.rcloneexplorer.scheduling.SchedulePolicy>(it).also { policy -> policy.validate() }
        }
    }
    private val ongoingNotificationID = Random().nextInt()


    // Task
    private lateinit var mTask: Task
    private var mTitle: String = mContext.getString(R.string.sync_service_notification_startingsync)



    override fun doWork(): Result {
        // Delay before acquiring foreground quota or transfer locks. Manual work ignores quiet hours.
        try { if ((automaticPolicy?.quietDelay(System.currentTimeMillis()) ?: 0) > 0) return Result.retry() }
        catch (invalid: Exception) { return Result.failure() }
        try {
            prepareNotifications()
            registerBroadcastReceivers()
            updateForegroundNotification(mNotificationManager.updateSyncNotification(
                mTitle, mTitle, ArrayList(), 0, ongoingNotificationID))
            val task = if (inputData.keyValueMap.containsKey(TASK_EPHEMERAL)) {
                inputData.getString(TASK_EPHEMERAL)?.let { Json.decodeFromString<Task>(it) }
            } else {
                mDatabase.getTask(inputData.getLong(TASK_ID, -1))
            }
            if (task == null) {
                failureReason = FAILURE_REASON.NO_TASK
            } else {
                mRclone.ensureConfigEncrypted()
                mTask = task
                if (!isStopped) handleTask() else failureReason = FAILURE_REASON.CANCELLED
            }
        } catch (e: InterruptedException) {
            failureReason = FAILURE_REASON.CANCELLED
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (failureReason == FAILURE_REASON.NO_FAILURE) failureReason = FAILURE_REASON.RCLONE_ERROR
            log("Sync failed: ${e.javaClass.simpleName}")
        } finally {
            deferred = !isStopped && ::mTask.isInitialized && mTask.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL &&
                runAttemptCount < (automaticPolicy?.maxRetries ?: 3) &&
                (temporaryExit || failureReason == FAILURE_REASON.NO_CONNECTION || failureReason == FAILURE_REASON.NO_UNMETERED)
            try {
                cleanup()
            } finally {
                postSync()
            }
        }
        return if (deferred) Result.retry() else if (failureReason == FAILURE_REASON.NO_FAILURE) Result.success() else Result.failure()
    }

    override fun onStopped() {
        super.onStopped()
        synchronized(terminalLock) {
            if (!endNotificationAlreadyPosted) failureReason = FAILURE_REASON.CANCELLED
        }
        deadline?.stop() ?: sRcloneProcess?.destroy()
        // doWork owns final notification and cleanup after its streams have stopped.
    }

    private fun cleanup() {
        deadline?.close()
        deadline = null
        sRcloneProcess?.destroy()
        sRcloneProcess = null
        if (receiverRegistered.compareAndSet(true, false)) {
            try {
                mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
            } catch (e: IllegalArgumentException) {
                log("Connectivity receiver was already unregistered")
            }
        }
        mNotificationManager.cancelSyncNotification(ongoingNotificationID)
    }

    private fun checkQueuedConfiguration() {
        check(inputData.getLong(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, -1) ==
            ca.pkay.rcloneexplorer.util.ConfigEpoch.current(mContext)) {
            "Configuration changed after this operation was queued"
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
        if (!isDirectionSupported(mTask.direction)) {
            failureReason = FAILURE_REASON.UNSUPPORTED_DIRECTION
            return
        }
        if(arePreconditionsMet()) {
            val transferLocks = TransferLocks.acquire(mContext, "sync")
            var bisyncRun: ca.pkay.rcloneexplorer.bisync.BisyncSession.Run? = null
            var bisyncSucceeded = false
            var mutationLease: java.io.Closeable? = null
            try {
                if (mTask.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL) {
                    mutationLease = ca.pkay.rcloneexplorer.bisync.BisyncSession.previewLease(mContext)
                }
                if (mTask.direction != SyncDirectionObject.SYNC_BIDIRECTIONAL) checkQueuedConfiguration()
                val taskFilter = if(mTask.filterId != null ) mDatabase.getFilter(mTask.filterId!!) else null;
                val taskFilterList = taskFilter?.getFilters() ?: ArrayList()
                val isCloudToCloud = mTask.direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE
                        || mTask.direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE
                sRcloneProcess = if (mTask.direction == SyncDirectionObject.SYNC_BIDIRECTIONAL) {
                    bisyncRun = ca.pkay.rcloneexplorer.bisync.BisyncSession.prepare(mContext, mTask)
                    activeBisyncRun = bisyncRun
                    checkQueuedConfiguration()
                    mRclone.bisync(mTask, taskFilterList)
                } else if (isCloudToCloud) {
                    val remoteItem2 = RemoteItem(mTask.remoteId2, mTask.remoteType2, "")
                    mRclone.sync(
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
                    mRclone.sync(
                        remoteItem,
                        mTask.localPath,
                        mTask.remotePath,
                        mTask.direction,
                        mTask.md5sum,
                        taskFilterList,
                        mTask.deleteExcluded,
                        mTask.transfers?.toString()
                    )
                }
                if (sRcloneProcess == null) {
                    failureReason = FAILURE_REASON.RCLONE_ERROR
                    log("Sync: Rclone process could not be started for direction ${mTask.direction}")
                    return
                }
                if (isStopped) {
                    failureReason = FAILURE_REASON.CANCELLED
                    sRcloneProcess?.destroy()
                }
                deadline = ProcessDeadline(sRcloneProcess!!,(automaticPolicy?.maxRuntimeMinutes ?: 300)*60_000L, expired = {
                    synchronized(terminalLock) { if(!endNotificationAlreadyPosted) failureReason=FAILURE_REASON.DEADLINE }
                })
                handleSync(mTitle)
                deadline?.close()
                deadline = null
                if (failureReason != FAILURE_REASON.NO_FAILURE) return
                bisyncSucceeded = !isStopped
                if (isCloudToCloud) {
                    // Refresh any open FileExplorer on the destination remote so copied content appears.
                    sendUploadFinishedBroadcast(mTask.remoteId2, mTask.remotePath2)
                } else {
                    sendUploadFinishedBroadcast(remoteItem.name, mTask.remotePath)
                }
            } finally {
                try {
                    activeBisyncRun = null
                    bisyncRun?.complete(bisyncSucceeded && !isStopped, nativeExitCode)
                } finally {
                    try { mutationLease?.close() } finally { transferLocks?.releaseAfterExit(sRcloneProcess) }
                }
            }
        }
    }

    private fun isDirectionSupported(direction: Int): Boolean {
        return direction == SyncDirectionObject.SYNC_LOCAL_TO_REMOTE ||
               direction == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL ||
               direction == SyncDirectionObject.COPY_LOCAL_TO_REMOTE ||
               direction == SyncDirectionObject.COPY_REMOTE_TO_LOCAL ||
               direction == SyncDirectionObject.SYNC_REMOTE_TO_REMOTE ||
               direction == SyncDirectionObject.COPY_REMOTE_TO_REMOTE ||
               direction == SyncDirectionObject.SYNC_BIDIRECTIONAL
    }

    private fun handleSync(title: String) {
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_start_sync))
        if (sRcloneProcess != null) {
            val localProcessReference = sRcloneProcess!!
            try {
                var lastNotifyMs = 0L
                val exitCode = ProcessMonitor.await(localProcessReference) { line ->
                    try {
                        val logline = JSONObject(line)
                        for (key in listOf("msg", "error")) {
                            if (logline.has(key)) logline.put(key,
                                ca.pkay.rcloneexplorer.util.SecretRedactor.redact(logline.optString(key)))
                        }
                        logline.optJSONObject("stats")?.let { stats ->
                            activeBisyncRun?.progress(stats.optLong("bytes"),stats.optLong("transfers"),stats.optLong("errors"))
                        }
                        statusObject.parseLoglineToStatusObject(logline)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (statusObject.notificationContent.isNotEmpty() && now - lastNotifyMs >= 1000L) {
                            lastNotifyMs = now
                            updateForegroundNotification(mNotificationManager.updateSyncNotification(
                                title, statusObject.notificationContent, statusObject.notificationBigText,
                                statusObject.notificationPercent, ongoingNotificationID))
                        }
                    } catch (e: JSONException) {
                        log("Ignoring non-JSON progress output")
                    }
                }
                nativeExitCode = exitCode
                temporaryExit = exitCode == 5 && failureReason == FAILURE_REASON.NO_FAILURE
                if (exitCode != 0 && failureReason == FAILURE_REASON.NO_FAILURE) {
                    failureReason = FAILURE_REASON.RCLONE_ERROR
                }
            } catch (e: InterruptedException) {
                failureReason = FAILURE_REASON.CANCELLED
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                if (failureReason == FAILURE_REASON.NO_FAILURE) failureReason = FAILURE_REASON.RCLONE_ERROR
                log("Could not read process output")
            }
        } else {
            failureReason = FAILURE_REASON.RCLONE_ERROR
            log("Sync: No Rclone Process!")
        }
        mNotificationManager.cancelSyncNotification(ongoingNotificationID)
    }

    private fun postSync() {
        if (deferred) return
        synchronized(terminalLock) {
            if (endNotificationAlreadyPosted) return
            if (isStopped && failureReason != FAILURE_REASON.DEADLINE) failureReason = FAILURE_REASON.CANCELLED
            endNotificationAlreadyPosted = true
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
                return
            }
            FAILURE_REASON.CANCELLED -> {
                showCancelledNotification(notificationId)
                endNotificationAlreadyPosted = true
                return
            }
            FAILURE_REASON.DEADLINE -> {
                content = "Runtime limit reached; review the run before trying again"
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
        if (::mTask.isInitialized) followupTask(mTask.onFailFollowup)
        showFailNotification(notificationId, content)
        endNotificationAlreadyPosted = true
    }

    private fun showCancelledNotification(notificationId: Int) {
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_failed_cancelled))
        mNotificationManager.showCancelledNotificationOrReport(
            mTitle,
            notificationId,
            if (::mTask.isInitialized) mTask.id else -1L
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
            if (::mTask.isInitialized) mTask.id else -1L
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
            setForegroundAsync(ForegroundInfo(ongoingNotificationID, it, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }
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
        mContext.registerReceiver(connectivityChangeBroadcastReceiver, intentFilter)
        receiverRegistered.set(true)
    }

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(endNotificationAlreadyPosted){
                    return
                }
                // A supplicant event is not proof that the active transfer lost connectivity.
                // The process outcome remains authoritative.
            }
        }

    private fun followupTask(followUpTaskID: Long?) {
        if (followUpTaskID == null || followUpTaskID == -1L) {
            return
        }
        val visited = inputData.getLongArray(FOLLOWUP_CHAIN)?.toList().orEmpty() + mTask.id
        if (!FollowupPolicy.canQueue(visited, followUpTaskID)) {
            log("Follow-up stopped: cycle or chain budget exceeded")
            return
        }
        SyncManager(mContext).queueFollowup(followUpTaskID, visited.toLongArray(),
            inputData.getLong(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, -1))
    }
}
