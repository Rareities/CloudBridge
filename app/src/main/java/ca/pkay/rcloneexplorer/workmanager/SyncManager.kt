package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
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

    internal fun queueFollowup(taskID: Long, visited: LongArray, epoch: Long = ca.pkay.rcloneexplorer.util.ConfigEpoch.current(mContext)) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putLong(ca.pkay.rcloneexplorer.util.ConfigEpoch.WORK_KEY, epoch)
        data.putLong(SyncWorker.TASK_ID, taskID)
        data.putLongArray(SyncWorker.FOLLOWUP_CHAIN, visited)

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