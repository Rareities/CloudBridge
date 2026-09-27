package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Parcel
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Log2File
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.prototypes.WorkerNotification
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NativeExecutionHandle
import ca.pkay.rcloneexplorer.util.NotificationSinkPolicy
import ca.pkay.rcloneexplorer.util.StagedUploadSourceCleanup
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.TransferLocks
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import de.schuelken.cloudbridge.extensions.tag
import de.schuelken.cloudbridge.notifications.implementations.DownloadWorkerNotification
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import android.webkit.MimeTypeMap
import de.schuelken.cloudbridge.notifications.implementations.DeleteWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.MoveWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.UploadWorkerNotification


class EphemeralWorker (private var mContext: Context, workerParams: WorkerParameters): Worker(mContext, workerParams) {


    companion object {
        const val EPHEMERAL_TYPE = "TASK_EPHEMERAL_TYPE"
        const val REMOTE = "REMOTE"

        const val DOWNLOAD_TARGETPATH = "DOWNLOAD_TARGETPATH"
        const val DOWNLOAD_SOURCE = "DOWNLOAD_SOURCE"

        const val UPLOAD_FILE = "UPLOAD_FILE"
        const val UPLOAD_TARGETPATH = "UPLOAD_TARGETPATH"
        const val UPLOAD_STAGED_SOURCE = "UPLOAD_STAGED_SOURCE"

        const val MOVE_FILE = "MOVE_FILE"
        const val MOVE_TARGETPATH = "MOVE_TARGETPATH"

        const val DELETE_FILE = "DELETE_FILE"
        const val DELETE_CONFIG_REVISION = "DELETE_CONFIG_REVISION"
    }

    internal enum class FAILURE_REASON {
        NO_FAILURE, NO_UNMETERED, NO_CONNECTION, RCLONE_ERROR, CONNECTIVITY_CHANGED, CANCELLED, NO_TASK
    }

    // Objects
    private var mNotificationManager: WorkerNotification? = null
    private val mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)


    private var log2File: Log2File? = null


    // States
    private val sIsLoggingEnabled = mPreferences.getBoolean(getString(R.string.pref_key_logs), false)
    private var sConnectivityChanged = false

    private var sRcloneProcess: NativeExecutionHandle? = null
    @Volatile private var stagedUploadSourceCleanup: StagedUploadSourceCleanup? = null
    private val nativeLaunchLock = Any()
    private val receiverLock = Any()
    private var receiverRegistered = false
    @Volatile private var stopRequested = false
    private val statusObject = StatusObject(mContext)
    private var failureReason = FAILURE_REASON.NO_FAILURE
    private val terminalNotificationPolicy = EphemeralTerminalNotificationPolicy()
    private var silentRun = false
    private val ongoingNotificationID = Random.nextInt()
    private var lastNotificationUpdateMs = 0L


    private var mTitle: String = mNotificationManager?.initialTitle ?: ""

    override fun doWork(): Result {
        stagedUploadSourceCleanup = createStagedUploadSourceCleanup()
        return try {
            val result = try {
                doWorkInternal()
            } catch (e: Exception) {
                terminalNotificationPolicy.updateIfPending {
                    if (failureReason == FAILURE_REASON.NO_FAILURE) {
                        failureReason = FAILURE_REASON.RCLONE_ERROR
                    }
                }
                log("Unexpected worker failure: ${e.message ?: e.javaClass.simpleName}")
                try {
                    finishWork()
                } catch (finishError: Exception) {
                    log("Unable to complete worker cleanup: ${finishError.message ?: finishError.javaClass.simpleName}")
                }
                Result.failure()
            }
            // Also handles validation returns that occur before native execution starts.
            try {
                postSync()
            } catch (notificationError: Exception) {
                log("Unable to publish terminal notification: ${notificationError.message ?: notificationError.javaClass.simpleName}")
            }
            if (failureReason == FAILURE_REASON.NO_FAILURE) result else Result.failure()
        } finally {
            stagedUploadSourceCleanup?.cleanupAfter(sRcloneProcess)
        }
    }

    private fun createStagedUploadSourceCleanup(): StagedUploadSourceCleanup? {
        if (!inputData.getBoolean(UPLOAD_STAGED_SOURCE, false)) return null
        val stagedPath = inputData.getString(UPLOAD_FILE) ?: return null
        return StagedUploadSourceCleanup(mContext.cacheDir, File(stagedPath))
    }

    private fun doWorkInternal(): Result {

        registerBroadcastReceivers()

        if (inputData.keyValueMap.containsKey(EPHEMERAL_TYPE)){
            val type = Type.valueOf(inputData.getString(EPHEMERAL_TYPE) ?: "")
            mNotificationManager = prepareNotificationManager(type)
            mTitle = mNotificationManager?.initialTitle ?: ""
            updateForegroundNotification(mNotificationManager?.updateNotification(
                mTitle,
                mTitle,
                ArrayList(),
                0,
                ongoingNotificationID
            ))
            val deleteConfigRevision = inputData.getString(DELETE_CONFIG_REVISION)
            if (type == Type.DELETE && !SnackbarDeletePolicy.hasConfigRevision(deleteConfigRevision)) {
                log("Refusing queued delete without a valid remote config revision")
                return Result.failure()
            }

            val remoteItem = getRemoteitemFromParcel(REMOTE)
            if(remoteItem == null){
                log("$REMOTE: No valid remote was passed!")
                return Result.failure()
            }

            mNotificationManager?.setCancelId(id)
            if(preconditionsMet()) {
                // do not instantiate rclone when you dont want it to run.
                // It will immediately run!
                val transferLocks = TransferLocks.acquire(mContext, "ephemeral")
                var locksAttachedToExecution = false
                try {
                    when(type){
                    Type.DOWNLOAD -> {
                        val target = inputData.getString(DOWNLOAD_TARGETPATH)
                        val fileItem = getFileitemFromParcel(DOWNLOAD_SOURCE)

                        if(fileItem == null){
                            log("$DOWNLOAD_SOURCE: No valid target was passed!")
                            return Result.failure()
                        }

                        launchOwnedIfRunning { Rclone(mContext).downloadFileOwned(
                            remoteItem,
                            fileItem,
                            target
                        ) }
                    }
                    Type.UPLOAD -> {
                        val target = inputData.getString(UPLOAD_TARGETPATH)
                        val file = inputData.getString(UPLOAD_FILE)

                        launchOwnedIfRunning { Rclone(mContext).uploadFileOwned(
                            remoteItem,
                            target,
                            file,
                            stagedUploadSourceCleanup
                        ) }
                    }
                    Type.MOVE -> {
                        val target = inputData.getString(MOVE_TARGETPATH)
                        val fileItem = getFileitemFromParcel(MOVE_FILE)

                        if(fileItem == null){
                            log("$MOVE_FILE: No valid target was passed!")
                            return Result.failure()
                        }

                        launchOwnedIfRunning { Rclone(mContext).moveToOwned(
                            remoteItem,
                            fileItem,
                            target
                        ) }
                    }
                    Type.DELETE -> {
                        val fileItem = getFileitemFromParcel(DELETE_FILE)

                        if(fileItem == null){
                            log("$DELETE_FILE: No valid target was passed!")
                            return Result.failure()
                        }

                        launchOwnedIfRunning { Rclone(mContext).deleteItemsOwned(
                            remoteItem,
                            fileItem,
                            deleteConfigRevision
                        ) }
                    }
                    }
                    if (sRcloneProcess != null && transferLocks != null) {
                        locksAttachedToExecution = sRcloneProcess!!.attachResource(transferLocks)
                    }
                    if (stopRequested || isStopped) {
                        sRcloneProcess?.cancelAndAwait(null, null)
                        failureReason = FAILURE_REASON.CANCELLED
                    } else {
                        handleSync(mTitle)
                    }
                } finally {
                    if (!locksAttachedToExecution) {
                        transferLocks?.release()
                    } else if (sRcloneProcess?.getOutcome() == null) {
                        sRcloneProcess?.close()
                    }
                }
            } else {
                log("Preconditions are not met!")
                postSync()
                return Result.failure()
            }

            postSync()
            // Indicate whether the work finished successfully with the Result
            return if (failureReason == FAILURE_REASON.NO_FAILURE) Result.success() else Result.failure()
        }
        failureReason = FAILURE_REASON.NO_TASK
        log("Critical: No valid ephemeral type passed!")
        return Result.failure()
    }

    override fun onStopped() {
        synchronized(nativeLaunchLock) {
            stopRequested = true
            terminalNotificationPolicy.updateIfPending {
                terminalNotificationPolicy.requestCancellation()
                failureReason = FAILURE_REASON.CANCELLED
            }
            sRcloneProcess?.cancel()
        }
        super.onStopped()
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_sync_cancelled))
        SyncLog.info(mContext, mTitle, statusObject.toString())
        finishWork()
    }

    private fun finishWork() {
        synchronized(nativeLaunchLock) {
            stopRequested = true
            sRcloneProcess?.cancel()
        }
        sRcloneProcess?.cancelAndAwait(null, null)
        synchronized(receiverLock) {
            if (receiverRegistered) {
                mContext.unregisterReceiver(connectivityChangeBroadcastReceiver)
                receiverRegistered = false
            }
        }
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

    fun prepareNotificationManager(type: Type): WorkerNotification {
        return when(type){
            Type.DOWNLOAD -> DownloadWorkerNotification(mContext)
            Type.UPLOAD -> UploadWorkerNotification(mContext)
            Type.DELETE -> DeleteWorkerNotification(mContext)
            Type.MOVE -> MoveWorkerNotification(mContext)
        }
    }

    private fun handleSync(title: String) {
        val execution = sRcloneProcess
        if (execution != null) {
            execution.await(
                NativeExecutionHandle.NO_TIMEOUT,
                null,
                NativeExecutionHandle.LineSink { line -> handleNativeLogLine(title, line) }
            ).also { outcome ->
                if (!outcome.isSuccess()) {
                    failureReason = when (outcome.state) {
                        NativeExecutionHandle.TerminalState.CANCELLED,
                        NativeExecutionHandle.TerminalState.INTERRUPTED -> FAILURE_REASON.CANCELLED
                        else -> FAILURE_REASON.RCLONE_ERROR
                    }
                }
            }
        } else {
            log("Sync: No Rclone Process!")
            failureReason = FAILURE_REASON.RCLONE_ERROR
        }
        mNotificationManager?.cancelSyncNotification(ongoingNotificationID)
    }

    private fun handleNativeLogLine(title: String, line: String) {
        try {
            val logline = JSONObject(line)
            if (logline.optString("level") == "error" && sIsLoggingEnabled) {
                log2File?.log(line)
            }

            statusObject.parseLoglineToStatusObject(logline)

            // Rebuild at most twice per second while the execution handle drains the pipe.
            if (statusObject.notificationContent.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdateMs >= 500L) {
                    lastNotificationUpdateMs = now
                    updateForegroundNotification(mNotificationManager?.updateNotification(
                        title,
                        statusObject.notificationContent,
                        statusObject.notificationBigText,
                        statusObject.notificationPercent,
                        ongoingNotificationID
                    ))
                }
            }
        } catch (e: JSONException) {
            FLog.e(tag(), "Error: the offending line: $line")
        }
    }

    private fun postSync() {
        if (silentRun) {
            return
        }

        val (reasonAtClaim, terminalOutcome) = synchronized(terminalNotificationPolicy) {
            val reason = failureReason
            val nativeOutcome = sRcloneProcess?.getOutcome()
            val outcome = terminalNotificationPolicy.claim(
                reason != FAILURE_REASON.NO_FAILURE && reason != FAILURE_REASON.CANCELLED,
                reason == FAILURE_REASON.CANCELLED,
                nativeOutcome?.isConfirmed() == true,
                nativeOutcome?.isSuccess() == true
            )
            if (outcome == EphemeralTerminalNotificationPolicy.Outcome.FAILURE
                && reason == FAILURE_REASON.NO_FAILURE) {
                failureReason = FAILURE_REASON.RCLONE_ERROR
            }
            reason to outcome
        }
        when (terminalOutcome) {
            EphemeralTerminalNotificationPolicy.Outcome.SUCCESS -> {
                showSuccessNotification(System.currentTimeMillis().toInt())
                return
            }
            EphemeralTerminalNotificationPolicy.Outcome.CANCELLED -> {
                showCancelledNotification(System.currentTimeMillis().toInt())
                return
            }
            EphemeralTerminalNotificationPolicy.Outcome.ALREADY_CLAIMED -> return
            EphemeralTerminalNotificationPolicy.Outcome.FAILURE -> Unit
        }

        val notificationId = System.currentTimeMillis().toInt()
        val notificationFailureReason = if (reasonAtClaim == FAILURE_REASON.NO_FAILURE) {
            FAILURE_REASON.RCLONE_ERROR
        } else {
            reasonAtClaim
        }
        val content = when (notificationFailureReason) {
            FAILURE_REASON.NO_FAILURE,
            FAILURE_REASON.CANCELLED,
            FAILURE_REASON.RCLONE_ERROR -> mContext.getString(R.string.operation_failed_unknown_rclone_error, mTitle)
            FAILURE_REASON.NO_TASK -> getString(R.string.operation_failed_notask)
            FAILURE_REASON.CONNECTIVITY_CHANGED -> mContext.getString(R.string.operation_failed_data_change, mTitle)
            FAILURE_REASON.NO_UNMETERED -> mContext.getString(R.string.operation_failed_no_unmetered, mTitle)
            FAILURE_REASON.NO_CONNECTION -> mContext.getString(R.string.operation_failed_no_connection, mTitle)
        }
        showFailNotification(notificationId, content)
        finishWork()
    }

    private fun showCancelledNotification(notificationId: Int) {
        val safeTitle = NotificationSinkPolicy.sanitizeTitle(mTitle)
        val content = NotificationSinkPolicy.sanitizeContent(
            mContext.getString(R.string.operation_failed_cancelled)
        )
        SyncLog.info(mContext, safeTitle, content)
        mNotificationManager?.showCancelledNotification(
            safeTitle,
            content,
            notificationId,
            0
        )
    }

    private fun showSuccessNotification(notificationId: Int) {
        //Todo: Show sync-errors in notification. Also see line 169
        //Todo: This should be context dependend on the type. It is currently not!


        val safeTitle = NotificationSinkPolicy.sanitizeTitle(mTitle)
        var message = NotificationSinkPolicy.sanitizeContent(
            mNotificationManager?.generateSuccessMessage(statusObject, getCurrentFile()) ?: "error"
        )

        mNotificationManager?.showSuccessNotification(
            safeTitle,
            message,
            notificationId
        )

        message += """
                        
        Est. Speed: ${statusObject.getEstimatedAverageSpeed()}
        Avg. Speed: ${statusObject.getLastItemAverageSpeed()}
                        """.trimIndent()
        //SyncLog.info(mContext, mContext.getString(R.string.operation_success, mTitle), message)
    }

    private fun showFailNotification(notificationId: Int, content: String, wasCancelled: Boolean = false) {
        //Todo: check if we should also add errors on success
        val errors = statusObject.getAllErrorMessages()
        val safeTitle = NotificationSinkPolicy.sanitizeTitle(mTitle)
        val text = NotificationSinkPolicy.sanitizeContent(
            if (errors.isEmpty()) content else "$content\n\n$errors"
        )

        var notifyTitle = mContext.getString(R.string.operation_failed)
        if (wasCancelled) {
            notifyTitle = mContext.getString(R.string.operation_failed_cancelled)
        }
        SyncLog.error(mContext, notifyTitle, "$safeTitle: $text")
        mNotificationManager?.showFailedNotification(
            safeTitle,
            text,
            notificationId,
           0
        )
    }

    private fun preconditionsMet(): Boolean {
        val wifiOnly = mPreferences.getBoolean(mContext.getString(R.string.pref_key_wifi_only_transfers), false)
        val connection = WifiConnectivitiyUtil.dataConnection(this.applicationContext)
        if (wifiOnly && connection === WifiConnectivitiyUtil.Connection.METERED) {
            failureReason = FAILURE_REASON.NO_UNMETERED
            return false
        } else if (connection === WifiConnectivitiyUtil.Connection.DISCONNECTED || connection === WifiConnectivitiyUtil.Connection.NOT_AVAILABLE) {
            failureReason = FAILURE_REASON.NO_CONNECTION
            return false
        }

        return true
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
        notification ?: return
        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ongoingNotificationID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ongoingNotificationID, notification)
        }
        // A synchronous Worker must not start native work until WorkManager confirms that
        // foreground promotion succeeded; otherwise Android can stop the transfer shortly
        // after it starts (or reject it outright on newer platform versions).
        requireForegroundPromotion(setForegroundAsync(foregroundInfo))
    }


    private fun log(message: String) {
        FLog.e(tag(), "EphemeralWorker: $message")
    }

    private fun getString(@StringRes resId: Int?): String {
        return if (resId == null) {
            "Error"
        } else {
            mContext.getString(resId)
        }
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

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                terminalNotificationPolicy.updateIfPending {
                    sConnectivityChanged = true
                    failureReason = FAILURE_REASON.CONNECTIVITY_CHANGED
                }
            }
        }

    private fun getFileitemFromParcel(key: String): FileItem {

        val sourceParcelByteArray = inputData.getByteArray(key)
        if(sourceParcelByteArray == null){
            log("No valid target was passed!")
            throw NullPointerException("The passed FileItem was null. We cannot continue!")
        }

        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(sourceParcelByteArray, 0, sourceParcelByteArray.size)
            parcel.setDataPosition(0)
            return FileItem.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun getRemoteitemFromParcel(key: String): RemoteItem? {

        val sourceParcelByteArray = inputData.getByteArray(key)
        if(sourceParcelByteArray == null){
            log("No valid target was passed!")
            return null
        }

        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(sourceParcelByteArray, 0, sourceParcelByteArray.size)
            parcel.setDataPosition(0)
            return RemoteItem.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun getCurrentFile(): FileItem {
        return when(Type.valueOf(inputData.getString(EPHEMERAL_TYPE)?:Type.DOWNLOAD.name)){
            Type.DOWNLOAD -> {
                getFileitemFromParcel(DOWNLOAD_SOURCE)
            }
            Type.UPLOAD -> {
                val pathAndName = inputData.getString(UPLOAD_FILE) ?: ""
                val name = pathAndName.substring(pathAndName.lastIndexOf("/")+1, pathAndName.length)
                val path = pathAndName.substring(0, pathAndName.lastIndexOf("/")+1)
                val localFile = File(pathAndName)
                val size = localFile.length()
                val rfc3339 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                rfc3339.timeZone = TimeZone.getTimeZone("UTC")
                val modTime = rfc3339.format(Date(localFile.lastModified()))
                val dotIndex = name.lastIndexOf('.')
                val mimeType = if (dotIndex >= 0 && dotIndex < name.length - 1) {
                    val extension = name.substring(dotIndex + 1).lowercase()
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                } else {
                    "application/octet-stream"
                }
                FileItem(
                        RemoteItem("", ""),
                        path,
                        name,
                        size,
                        modTime,
                        mimeType,
                        false,
                        false)
            }
            Type.MOVE -> {
                getFileitemFromParcel(MOVE_FILE)
            }
            Type.DELETE -> {
                getFileitemFromParcel(DELETE_FILE)
            }
        }
    }
}
