package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ca.pkay.rcloneexplorer.scheduling.SchedulePolicy
import ca.pkay.rcloneexplorer.Database.DatabaseHandler
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import java.util.Random

class SyncManager(private var mContext: Context) {

    companion object {
        private val SYNC_WORK_TAG = "sync_work"
    }

    fun queue(trigger: Trigger) {
        queue(trigger.triggerTarget)
    }

    fun queue(task: Task) {
        queue(task.id)
    }

    fun queue(taskID: Long) = queueFollowup(taskID, longArrayOf())

    fun queueAtEpoch(taskID: Long, epoch: Long) = queueFollowup(taskID, longArrayOf(), epoch)

    fun queueScheduled(trigger: Trigger, epoch: Long) = queueFollowup(trigger.triggerTarget, longArrayOf(), epoch, trigger.schedulePolicy)

    internal fun queueFollowup(taskID: Long, visited: LongArray, epoch: Long = ca.pkay.rcloneexplorer.util.ConfigEpoch.current(mContext), policy: SchedulePolicy? = null) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putLong(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, epoch)
        data.putLong(SyncWorker.TASK_ID, taskID)
        data.putLongArray(SyncWorker.FOLLOWUP_CHAIN, visited)
        val task = DatabaseHandler(mContext).getTask(taskID)
        val constraints = Constraints.Builder().setRequiredNetworkType(
            if(task?.wifionly == true || policy?.unmeteredOnly == true) NetworkType.UNMETERED else NetworkType.CONNECTED)
        if(policy != null) {
            policy.validate()
            data.putString("schedule_policy",Json.encodeToString(policy))
            constraints.setRequiresBatteryNotLow(policy.batteryNotLow).setRequiresCharging(policy.chargingOnly)
        }
        uploadWorkRequest.setConstraints(constraints.build())
        uploadWorkRequest.setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30L + Random().nextInt(31),TimeUnit.SECONDS)


        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(taskID.toString())
        uploadWorkRequest.addTag(SYNC_WORK_TAG)
        WorkManager.getInstance(mContext).enqueueUniqueWork(
            "sync-task-$taskID", ExistingWorkPolicy.KEEP, uploadWorkRequest.build())
    }

    fun queueEphemeral(task: Task) {

        task.id = Random().nextLong()
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putLong(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, ca.pkay.rcloneexplorer.util.ConfigEpoch.current(mContext))
        data.putString(SyncWorker.TASK_EPHEMERAL, task.asJSON().toString())

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(task.id.toString())
        uploadWorkRequest.addTag(SYNC_WORK_TAG)
        work(uploadWorkRequest.build())
    }

    private fun work(request: WorkRequest) {
        WorkManager.getInstance(mContext)
            .enqueue(request)
    }

    fun cancel() {
        WorkManager.getInstance(mContext)
            .cancelAllWorkByTag(SYNC_WORK_TAG)
    }
    fun cancel(tag: String) {
        WorkManager
            .getInstance(mContext)
            .cancelAllWorkByTag(tag)
    }
}