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
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
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

        const val MOVE_FILE = "MOVE_FILE"
        const val MOVE_TARGETPATH = "MOVE_TARGETPATH"

        const val DELETE_MANIFEST = "DELETE_MANIFEST"
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

    @Volatile private var sRcloneProcess: Process? = null

    // A batch delete runs several rclone processes one after another; spawn thunks keep later
    // processes from starting (and piling up on the remote) until the previous one is done.
    private val sPendingProcesses = ArrayDeque<() -> Process?>()
    private val sCleanupFiles = ArrayList<File>()
    private var sDeleteEntries: List<DeleteEntry> = emptyList()
    private val statusObject = StatusObject(mContext)
    @Volatile private var failureReason = FAILURE_REASON.NO_FAILURE
    @Volatile private var endNotificationAlreadyPosted = false
    private val receiverRegistered = AtomicBoolean(false)
    private val terminalLock = Any()
    private var silentRun = false
    @Volatile private var deadline: ProcessDeadline? = null
    private val ongoingNotificationID = Random.nextInt()


    private var mTitle: String = mNotificationManager?.initialTitle ?: ""

    override fun doWork(): Result {
        return try {
            runOperation()
        } catch (e: InterruptedException) {
            failureReason = FAILURE_REASON.CANCELLED
            Thread.currentThread().interrupt()
            Result.failure()
        } catch (e: Exception) {
            if (failureReason == FAILURE_REASON.NO_FAILURE) failureReason = FAILURE_REASON.RCLONE_ERROR
            log("Operation failed: ${e.javaClass.simpleName}")
            Result.failure()
        } finally {
            try {
                cleanup()
            } finally {
                postSync()
            }
        }
    }

    private fun runOperation(): Result {
        failureReason = FAILURE_REASON.NO_TASK
        registerBroadcastReceivers()

        if (inputData.keyValueMap.containsKey(EPHEMERAL_TYPE)){
            val type = Type.valueOf(inputData.getString(EPHEMERAL_TYPE) ?: "")
            mNotificationManager = prepareNotificationManager(type)

            val remoteItem = getRemoteitemFromParcel(REMOTE)
            if(remoteItem == null){
                log("$REMOTE: No valid remote was passed!")
                return Result.failure()
            }

            // The title and the first foreground promotion need the notification manager, which
            // is still null earlier. Promoting here keeps short-lived jobs (deletes!) in the
            // foreground instead of losing their slot to the background job time limit.
            mTitle = mNotificationManager?.initialTitle ?: ""
            updateForegroundNotification(mNotificationManager?.updateNotification(
                mTitle,
                mTitle,
                ArrayList(),
                0,
                ongoingNotificationID
            ))

            mNotificationManager?.setCancelId(id)
            failureReason = FAILURE_REASON.NO_FAILURE
            if (isStopped) {
                failureReason = FAILURE_REASON.CANCELLED
                return Result.failure()
            }
            if(preconditionsMet()) {
                // do not instantiate rclone when you dont want it to run.
                // It will immediately run!
                val transferLocks = TransferLocks.acquire(mContext, "ephemeral")
                var mutationLease: java.io.Closeable? = null
                try {
                    Rclone(mContext).ensureConfigEncrypted()
                    mutationLease = ca.pkay.rcloneexplorer.bisync.BisyncSession.previewLease(mContext)
                    check(inputData.getLong(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, -1) ==
                        ca.pkay.rcloneexplorer.util.ConfigEpoch.current(mContext)) {
                        "Configuration changed after this operation was queued"
                    }
                    when(type){
                    Type.DOWNLOAD -> {
                        val target = inputData.getString(DOWNLOAD_TARGETPATH)
                        val fileItem = getFileitemFromParcel(DOWNLOAD_SOURCE)

                        if(fileItem == null){
                            log("$DOWNLOAD_SOURCE: No valid target was passed!")
                            failureReason = FAILURE_REASON.NO_TASK
                            return Result.failure()
                        }

                        sPendingProcesses.add { Rclone(mContext).downloadFile(
                            remoteItem,
                            fileItem,
                            target
                        ) }
                    }
                    Type.UPLOAD -> {
                        val target = inputData.getString(UPLOAD_TARGETPATH)
                        val file = inputData.getString(UPLOAD_FILE)

                        sPendingProcesses.add { Rclone(mContext).uploadFile(
                            remoteItem,
                            target,
                            file
                        ) }
                    }
                    Type.MOVE -> {
                        val target = inputData.getString(MOVE_TARGETPATH)
                        val fileItem = getFileitemFromParcel(MOVE_FILE)

                        if(fileItem == null){
                            log("$MOVE_FILE: No valid target was passed!")
                            failureReason = FAILURE_REASON.NO_TASK
                            return Result.failure()
                        }

                        sPendingProcesses.add { Rclone(mContext).moveTo(
                            remoteItem,
                            fileItem,
                            target
                        ) }
                    }
                    Type.DELETE -> {
                        val manifestFile = File(inputData.getString(DELETE_MANIFEST) ?: "")
                        if (!manifestFile.exists()) {
                            log("$DELETE_MANIFEST: the delete manifest is missing!")
                            failureReason = FAILURE_REASON.NO_TASK
                            return Result.failure()
                        }
                        val entries = DeleteManifest.decode(manifestFile.readText())
                        // Keep the manifest until the batch finishes: if the process dies
                        // mid-batch, WorkManager re-runs the worker and needs it again.
                        sCleanupFiles.add(manifestFile)
                        if (entries.isEmpty()) {
                            log("$DELETE_MANIFEST: no deletable items were passed!")
                            failureReason = FAILURE_REASON.NO_TASK
                            return Result.failure()
                        }
                        sDeleteEntries = entries

                        // Plain files go into one `rclone delete --files-from` run; directories
                        // and names unusable in a line-based list get individual processes.
                        val partition = DeleteManifest.partition(entries)
                        val rclone = Rclone(mContext)
                        if (partition.batchableFiles.isNotEmpty()) {
                            val filesFrom = File(mContext.cacheDir, "delete-files-from-$id.txt")
                            filesFrom.writeText(DeleteManifest.filesFromLines(partition.batchableFiles).joinToString("\n"))
                            sCleanupFiles.add(filesFrom)
                            sPendingProcesses.add { rclone.deleteFilesFrom(remoteItem, filesFrom) }
                        }
                        for (dir in partition.dirs) {
                            sPendingProcesses.add { rclone.purgeItem(remoteItem, dir.path) }
                        }
                        for (file in partition.fallbackFiles) {
                            sPendingProcesses.add { rclone.deleteItems(remoteItem, file.path) }
                        }
                        if (sPendingProcesses.isEmpty()) {
                            failureReason = FAILURE_REASON.RCLONE_ERROR
                            log("Delete: no rclone process could be started")
                        }
                    }
                }
                handleSync(mTitle)
                } finally {
                    try { mutationLease?.close() } finally { transferLocks?.releaseAfterExit(sRcloneProcess) }
                    sCleanupFiles.forEach { it.delete() }
                }
            } else {
                log("Preconditions are not met!")
                return Result.failure()
            }

            return if (failureReason == FAILURE_REASON.NO_FAILURE) Result.success() else Result.failure()
        }
        log("Critical: No valid ephemeral type passed!")
        return Result.failure()
    }

    override fun onStopped() {
        super.onStopped()
        synchronized(terminalLock) {
            if (!endNotificationAlreadyPosted) failureReason = FAILURE_REASON.CANCELLED
        }
        deadline?.stop() ?: sRcloneProcess?.destroy()
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
        mNotificationManager?.cancelSyncNotification(ongoingNotificationID)
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
        if (sPendingProcesses.isEmpty()) {
            failureReason = FAILURE_REASON.RCLONE_ERROR
            log("Sync: No Rclone Process!")
            mNotificationManager?.cancelSyncNotification(ongoingNotificationID)
            return
        }
        while (!isStopped && sPendingProcesses.isNotEmpty()) {
            // Fold the finished process's deletions into the running total; its stats are
            // replaced as soon as the next process starts reporting.
            statusObject.accumulateDeletions()
            sRcloneProcess = sPendingProcesses.removeFirst().invoke()
            if (sRcloneProcess == null) {
                log("Sync: Rclone process could not be started")
                failureReason = FAILURE_REASON.RCLONE_ERROR
                break
            }
            if (isStopped) {
                failureReason = FAILURE_REASON.CANCELLED
                sRcloneProcess?.destroy()
                break
            }
            deadline = ProcessDeadline(sRcloneProcess!!,300*60_000L, expired = {
                synchronized(terminalLock) { if(!endNotificationAlreadyPosted) failureReason=FAILURE_REASON.RCLONE_ERROR }
                log("Runtime limit reached")
            })
            try { readProcess(sRcloneProcess!!, title) } finally { deadline?.close(); deadline=null }
            if (failureReason != FAILURE_REASON.NO_FAILURE) break
        }
        mNotificationManager?.cancelSyncNotification(ongoingNotificationID)
    }

    private fun readProcess(process: Process, title: String) {
        var lastNotifyMs = 0L
        val exitCode = ProcessMonitor.await(process) { line ->
            try {
                val logline = JSONObject(line)
                        for (key in listOf("msg", "error")) {
                            if (logline.has(key)) logline.put(key,
                                ca.pkay.rcloneexplorer.util.SecretRedactor.redact(logline.optString(key)))
                        }
                statusObject.parseLoglineToStatusObject(logline)
                val now = android.os.SystemClock.elapsedRealtime()
                if (statusObject.notificationContent.isNotEmpty() && now - lastNotifyMs >= 1000L) {
                    lastNotifyMs = now
                    updateForegroundNotification(mNotificationManager?.updateNotification(
                        title, statusObject.notificationContent, statusObject.notificationBigText,
                        statusObject.notificationPercent, ongoingNotificationID))
                }
            } catch (e: JSONException) {
                log("Ignoring non-JSON progress output")
            }
        }
        if (exitCode != 0 && failureReason == FAILURE_REASON.NO_FAILURE) {
            failureReason = FAILURE_REASON.RCLONE_ERROR
        }
    }

    private fun postSync() {
        synchronized(terminalLock) {
            if (endNotificationAlreadyPosted) return
            if (isStopped) failureReason = FAILURE_REASON.CANCELLED
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
            FAILURE_REASON.NO_CONNECTION -> {
                content = mContext.getString(R.string.operation_failed_no_connection, mTitle)
            }
            FAILURE_REASON.RCLONE_ERROR -> {
                content = mContext.getString(R.string.operation_failed_unknown_rclone_error, mTitle)
            }
        }
        showFailNotification(notificationId, content)
        endNotificationAlreadyPosted = true
    }

    private fun showCancelledNotification(notificationId: Int) {
        SyncLog.info(mContext, mTitle, mContext.getString(R.string.operation_failed_cancelled))
        // The title inside the notification is the type-specific serviceCancelled string
        // ("Delete cancelled" etc.); the content names the operation instead of the misleading
        // generic "Sync was cancelled" that was shown for every worker type.
        mNotificationManager?.showCancelledNotification(
            mTitle,
            mTitle,
            notificationId,
            0
        )
    }

    private fun showSuccessNotification(notificationId: Int) {
        //Todo: Show sync-errors in notification. Also see line 169
        //Todo: This should be context dependend on the type. It is currently not!


        var message = mNotificationManager?.generateSuccessMessage(statusObject, getCurrentFile())?: "error"

        mNotificationManager?.showSuccessNotification(
            mTitle,
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
        mNotificationManager?.showFailedNotification(
            mTitle,
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notification?.let {
                setForegroundAsync(ForegroundInfo(ongoingNotificationID, it, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            }
        } else {
            notification?.let {
                setForegroundAsync(ForegroundInfo(ongoingNotificationID, it))
            }
        }
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
        mContext.registerReceiver(connectivityChangeBroadcastReceiver, intentFilter)
        receiverRegistered.set(true)
    }

    private val connectivityChangeBroadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(endNotificationAlreadyPosted){
                    return
                }
                // Do not fail a live process merely because Wi-Fi emitted a supplicant event.
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
                val first = sDeleteEntries.firstOrNull()
                    ?: throw NullPointerException("No delete entry was passed. We cannot continue!")
                FileItem(
                        RemoteItem("", ""),
                        first.path,
                        first.name,
                        0,
                        "",
                        "",
                        first.isDir,
                        false)
            }
        }
    }
}
