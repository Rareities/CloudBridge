package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import ca.pkay.rcloneexplorer.util.BisyncStateStore
import ca.pkay.rcloneexplorer.util.ScheduleCalculator
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SyncManager(private val mContext: Context) {
    companion object {
        const val SYNC_WORK_TAG = "sync_work"
        const val CHAIN_TASKS = "CHAIN_TASKS"
        const val AUTOMATIC = "AUTOMATIC"
        const val MAX_CHAIN_LENGTH = 16
    }

    fun queue(trigger: Trigger) { if (trigger.isEnabled) queueTask(trigger.triggerTarget, automatic = true) }
    fun queue(task: Task) { queue(task.id) }
    fun queue(taskID: Long) { queueTask(taskID) }
    fun previewBisync(taskID: Long) { queueTask(taskID, operation = BisyncStateStore.PREVIEW) }
    fun initialiseBisync(taskID: Long, approval: String) {
        require(approval.matches(Regex("[0-9a-f]{64}"))) { "A successful matching preview is required" }
        queueTask(taskID, operation = BisyncStateStore.INITIALISE, approval = approval)
    }

    fun queueFollowup(taskID: Long, previous: LongArray): Boolean {
        if (taskID in previous || previous.size >= MAX_CHAIN_LENGTH) return false
        queueTask(taskID, automatic = true, chain = previous)
        return true
    }

    private fun queueTask(taskID: Long, operation: String = BisyncStateStore.RUN,
                          approval: String? = null, automatic: Boolean = false,
                          chain: LongArray = longArrayOf()) {
        val task = DatabaseHandler(mContext).getTask(taskID) ?: return
        val data = Data.Builder().putLong(SyncWorker.TASK_ID, taskID)
            .putString("TASK_UUID", task.bisyncUUID)
            .putString(SyncWorker.BISYNC_OPERATION, operation)
            .putString(SyncWorker.BISYNC_APPROVAL, approval)
            .putBoolean(AUTOMATIC, automatic)
            .putLongArray(CHAIN_TASKS, chain + taskID)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data.build())
            .setConstraints(constraints(task))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(taskID.toString()).addTag(SYNC_WORK_TAG)
        if (automatic) {
            val now = System.currentTimeMillis()
            val due = ScheduleCalculator.afterQuietHours(now, task.quietHoursStart, task.quietHoursEnd, TimeZone.getDefault())
            if (due > now) request.setInitialDelay(due - now, TimeUnit.MILLISECONDS)
        }
        WorkManager.getInstance(mContext)
            .enqueueUniqueWork("task:$taskID", ExistingWorkPolicy.KEEP, request.build())
    }

    private fun constraints(task: Task): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (task.wifionly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(task.chargingOnly)
        .setRequiresBatteryNotLow(task.batteryNotLow)
        .setRequiresStorageNotLow(true).build()

    fun queueEphemeral(task: Task) {
        task.id = Random().nextLong()
        val data = Data.Builder().putString(SyncWorker.TASK_EPHEMERAL, task.asJSON().toString()).build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data)
            .setConstraints(constraints(task)).addTag(task.id.toString()).addTag(SYNC_WORK_TAG).build()
        WorkManager.getInstance(mContext).enqueue(request)
    }
    fun cancel() { WorkManager.getInstance(mContext).cancelAllWorkByTag(SYNC_WORK_TAG) }
    fun cancel(tag: String) { WorkManager.getInstance(mContext).cancelAllWorkByTag(tag) }
}
