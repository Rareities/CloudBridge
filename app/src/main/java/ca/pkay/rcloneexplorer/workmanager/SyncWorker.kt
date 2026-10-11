package ca.pkay.rcloneexplorer.workmanager

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import androidx.annotation.StringRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.FilterEntry
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.GenericSyncNotification
import ca.pkay.rcloneexplorer.notifications.ReportNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications
import ca.pkay.rcloneexplorer.notifications.SyncServiceNotifications.Companion.GROUP_ID
import ca.pkay.rcloneexplorer.notifications.support.StatusObject
import ca.pkay.rcloneexplorer.util.BisyncPolicy
import ca.pkay.rcloneexplorer.util.BisyncStateStore
import ca.pkay.rcloneexplorer.util.DiagnosticRedactor
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.ProcessRunner
import ca.pkay.rcloneexplorer.util.RunHistory
import ca.pkay.rcloneexplorer.util.ScheduleCalculator
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.TransferLocks
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class SyncWorker(private val mContext: Context, params: WorkerParameters) : Worker(mContext, params) {
    companion object {
        const val TASK_ID = "TASK_ID"
        const val TASK_EPHEMERAL = "TASK_EPHEMERAL"
        const val BISYNC_OPERATION = "BISYNC_OPERATION"
        const val BISYNC_APPROVAL = "BISYNC_APPROVAL"
        const val TASK_SYNC_ACTION = "START_TASK"
        const val TASK_CANCEL_ACTION = "CANCEL_TASK"
        const val EXTRA_TASK_ID = "task"
        const val EXTRA_TASK_SILENT = "notification"
        private const val TAG = "SyncWorker"
    }

    private val mRclone = Rclone(mContext)
    private val mDatabase = DatabaseHandler(mContext)
    private val mNotificationManager = SyncServiceNotifications(mContext)
    private val statusObject = StatusObject(mContext)
    private val terminal = AtomicBoolean()
    private val ongoingNotificationID = Random().nextInt()
    private var mTitle = "Transfer"
    @Volatile private var currentRunner = ProcessRunner()
    @Volatile private var currentProcess: Process? = null
    @Volatile private var cancelled = false
    private val diagnostics = StringBuilder()
    private var lastNotify = 0L
    private var exitCode: Int? = null
    private var completed = false
    private var detail = ""
    private var startedAt = 0L

    override fun doWork(): Result {
        val initialTask = try {
            if (inputData.keyValueMap.containsKey(TASK_EPHEMERAL)) {
                Json.decodeFromString<Task>(inputData.getString(TASK_EPHEMERAL) ?: "")
            } else mDatabase.getTask(inputData.getLong(TASK_ID, -1L))
        } catch (_: Exception) { null }
        if (initialTask == null) return Result.failure(Data.Builder().putString("error", "Task is missing or invalid").build())
        var task: Task = initialTask
        val operation = inputData.getString(BISYNC_OPERATION) ?: BisyncStateStore.RUN
        mTitle = task.title.ifBlank { "Task ${task.id}" }
        startedAt = System.currentTimeMillis()
        if (isStopped) return failBeforeStart(task, operation, "Cancelled before the task started", true)
        val automatic = inputData.getBoolean(SyncManager.AUTOMATIC, false)
        if (automatic && ScheduleCalculator.afterQuietHours(System.currentTimeMillis(), task.quietHoursStart,
                task.quietHoursEnd, TimeZone.getDefault()) > System.currentTimeMillis()) return Result.retry()
        if (!networkAvailable(task)) return if (runAttemptCount < 6) Result.retry()
            else failBeforeStart(task, operation, "Required network remained unavailable. Review the task constraints before retrying.")
        val admission = try { BisyncPolicy.Admission.acquire(mContext) } catch (_: Exception) { null }
        if (admission == null) return if (runAttemptCount < 8) Result.retry()
            else failBeforeStart(task, operation, "Another transfer remained active. This task did not start.")
        var transferLocks: TransferLocks? = null
        try {
            if (!inputData.keyValueMap.containsKey(TASK_EPHEMERAL)) {
                task = requireNotNull(mDatabase.getTask(task.id)) { "The task was deleted while waiting" }
                val expected = inputData.getString("TASK_UUID")
                require(expected == null || expected == task.bisyncUUID) { "This queued request belongs to an older task configuration" }
            }
            require(task.validationError == null) { task.validationError ?: "Invalid task" }
            require(task.direction in 1..8) { "Unsupported task mode; edit this task before running it" }
            require(BisyncPolicy.isBisync(task) || operation == BisyncStateStore.RUN) { "This task does not support bisync initialisation" }
            RunHistory.record(mContext, task, id.toString(), operation, "IN_PROGRESS", startedAt, null,
                "Task started; a terminal result has not yet been confirmed.")
            prepareNotifications()
            mNotificationManager.setCancelId(id)
            mNotificationManager.updateSyncNotification(mTitle, "Preparing task", ArrayList(), 0, ongoingNotificationID)?.let {
                setForegroundAsync(ForegroundInfo(ongoingNotificationID, it, FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get()
            }
            if (isStopped) throw InterruptedException("Task was cancelled")
            transferLocks = TransferLocks.acquire(mContext, "task")
            val filters = task.filterId?.takeIf { it >= 0 }?.let { filterId ->
                requireNotNull(mDatabase.getFilter(filterId)) { "The selected filter no longer exists" }.getFilters()
            } ?: arrayListOf()
            if (BisyncPolicy.isBisync(task)) handleBisync(task, operation, filters) else handleOneWay(task, filters)
        } catch (e: InterruptedException) {
            cancelled = true
            currentRunner.requestStop()
            detail = "Cancelled. Any interrupted bisync baseline requires review."
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            detail = DiagnosticRedactor.redact(e.message ?: e.javaClass.simpleName)
            completed = false
        } finally {
            cancelled = cancelled || isStopped
            if (cancelled) currentRunner.requestStop()
            try { transferLocks?.release() } finally { admission.closeAfterExit(currentProcess) }
            try { deliverResult(task, operation) } catch (e: Exception) {
                FLog.e(TAG, "Could not deliver task result: ${e.javaClass.simpleName}")
            }
            mNotificationManager.cancelSyncNotification(ongoingNotificationID)
        }
        return if (completed && !cancelled) Result.success() else
            Result.failure(Data.Builder().putString("error", detail.take(1800)).build())
    }

    private fun failBeforeStart(task: Task, operation: String, message: String, stopped: Boolean = false): Result {
        detail = message
        cancelled = stopped || isStopped
        completed = false
        try {
            prepareNotifications()
            deliverResult(task, operation)
        } catch (e: Exception) {
            FLog.e(TAG, "Could not deliver task start failure: ${e.javaClass.simpleName}")
        }
        return Result.failure(Data.Builder().putString("error", message).build())
    }

    private fun handleOneWay(task: Task, filters: ArrayList<FilterEntry>) {
        val remote = RemoteItem(task.remoteId, task.remoteType, "")
        val cloudToCloud = task.direction == 7 || task.direction == 8
        val child = if (cloudToCloud) mRclone.sync(remote, task.remotePath,
            RemoteItem(task.remoteId2, task.remoteType2, ""), task.remotePath2, task.direction,
            task.md5sum, filters, task.deleteExcluded, task.transfers?.toString())
        else mRclone.sync(remote, task.localPath, task.remotePath, task.direction,
            task.md5sum, filters, task.deleteExcluded, task.transfers?.toString())
        val outcome = execute(child, true)
        completed = outcome.success()
        if (completed) sendUploadFinishedBroadcast(if (cloudToCloud) task.remoteId2 else task.remoteId,
            if (cloudToCloud) task.remotePath2 else task.remotePath)
    }

    private fun handleBisync(task: Task, operation: String, filters: ArrayList<FilterEntry>) {
        BisyncPolicy.validate(mContext, task)
        val directory = BisyncPolicy.workdir(mContext, task)
        val state = BisyncStateStore(directory)
        val fingerprint = BisyncPolicy.fingerprint(task, filters, mRclone.configurationIdentity { isStopped || cancelled })
        val childDirectory = when (operation) {
            BisyncStateStore.PREVIEW -> File(directory, "previews/$id")
            BisyncStateStore.INITIALISE -> File(directory, "baselines/$id")
            BisyncStateStore.RUN -> {
                val baseline = state.read().optString("baselineId")
                require(java.util.UUID.fromString(baseline).toString() == baseline) { "The bisync baseline identity is invalid. Review initialisation again." }
                File(directory, "baselines/$baseline").also {
                    require(it.isDirectory) { "The saved bisync baseline is missing. Review recovery before continuing." }
                }
            }
            else -> throw IllegalArgumentException("Unsupported bisync operation")
        }
        require(childDirectory.isDirectory || childDirectory.mkdirs()) { "Cannot create bisync working folder" }
        // Parse every custom flag against the exact bundled engine before authentication or mutation.
        val check = execute(mRclone.bisync(task, childDirectory, operation, filters, true), false)
        require(check.success()) { detail.ifBlank { "The selected engine rejected a task option" } }
        diagnostics.setLength(0)
        exitCode = null
        state.begin(operation, fingerprint, inputData.getString(BISYNC_APPROVAL), id.toString())
        var success = false
        try {
            if (isStopped || cancelled) throw InterruptedException("Cancelled before bisync started")
            val outcome = execute(mRclone.bisync(task, childDirectory, operation, filters, false), true)
            success = outcome.success() && !isStopped && !cancelled
            completed = success
            if (success && operation != BisyncStateStore.PREVIEW) sendUploadFinishedBroadcast(task.remoteId, task.remotePath)
        } finally {
            state.finish(operation, fingerprint, id.toString(), success && !isStopped && !cancelled,
                exitCode, detail, diagnostics.toString())
        }
    }

    private fun execute(child: Process?, progress: Boolean): ProcessRunner.Outcome {
        currentProcess = child
        currentRunner = ProcessRunner()
        val outcome = currentRunner.run(child, { isStopped || cancelled },
            { line -> if (!progress) appendDiagnostic(line) },
            { line ->
                val safe = DiagnosticRedactor.redact(line)
                val json = try { JSONObject(safe) } catch (_: Exception) { null }
                appendDiagnostic(json?.optString("msg", safe) ?: safe)
                if (json != null && progress) {
                    try {
                        statusObject.parseLoglineToStatusObject(json)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastNotify >= 750L && statusObject.notificationContent.isNotEmpty()) {
                            lastNotify = now
                            updateForegroundNotification(mNotificationManager.updateSyncNotification(
                                mTitle, statusObject.notificationContent, statusObject.notificationBigText,
                                statusObject.notificationPercent, ongoingNotificationID))
                        }
                    } catch (_: Exception) { /* Progress is advisory; the exit result remains authoritative. */ }
                }
            })
        exitCode = outcome.exitCode
        cancelled = cancelled || outcome.cancelled
        detail = when {
            cancelled -> "Cancelled. Bisync state may need recovery."
            !outcome.exited -> "The process did not confirm exit; another transfer remains blocked until it stops."
            outcome.streamFailure -> "Could not completely read the process output. Review the task before retrying."
            outcome.exitCode == null -> "The rclone process could not be started."
            outcome.exitCode != 0 -> "rclone exited with status ${outcome.exitCode}.\n${diagnostics.toString().takeLast(1400)}"
            else -> "Completed successfully"
        }
        return outcome
    }

    @Synchronized private fun appendDiagnostic(line: String) {
        diagnostics.append(DiagnosticRedactor.redact(line)).append('\n')
        if (diagnostics.length > 20000) diagnostics.delete(0, diagnostics.length - 20000)
    }

    override fun onStopped() {
        cancelled = true
        currentRunner.requestStop()
        super.onStopped()
    }

    private fun deliverResult(task: Task, operation: String) {
        if (!terminal.compareAndSet(false, true)) return
        val status = if (cancelled) "CANCELLED" else if (completed) "SUCCESS" else "FAILED"
        RunHistory.record(mContext, task, id.toString(), operation, status, startedAt, exitCode, detail)
        val notificationId = System.currentTimeMillis().toInt()
        when {
            cancelled -> mNotificationManager.showCancelledNotificationOrReport(mTitle, notificationId, task.id)
            completed -> mNotificationManager.showSuccessNotificationOrReport(mTitle,
                if (operation == BisyncStateStore.PREVIEW) "Preview ready. Open the task to review and approve initialisation."
                else "Transfer completed successfully", notificationId)
            else -> mNotificationManager.showFailedNotificationOrReport(mTitle, detail, notificationId, task.id)
        }
        SyncLog.info(mContext, mTitle, "$status: $detail")
        if (operation == BisyncStateStore.RUN && !cancelled) {
            val followup = if (completed) task.onSuccessFollowup else task.onFailFollowup
            if (followup != null && followup >= 0) {
                val chain = inputData.getLongArray(SyncManager.CHAIN_TASKS) ?: longArrayOf(task.id)
                if (!SyncManager(mContext).queueFollowup(followup, chain))
                    SyncLog.error(mContext, mTitle, "Follow-up stopped: repeated task or chain limit reached")
            }
        }
    }

    private fun networkAvailable(task: Task): Boolean {
        val connection = WifiConnectivitiyUtil.dataConnection(mContext)
        return connection != WifiConnectivitiyUtil.Connection.DISCONNECTED &&
            connection != WifiConnectivitiyUtil.Connection.NOT_AVAILABLE &&
            (!task.wifionly || connection != WifiConnectivitiyUtil.Connection.METERED)
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


    private fun getString(@StringRes id: Int): String = mContext.getString(id)
}
