package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import ca.pkay.rcloneexplorer.Database.RunRejectedException
import ca.pkay.rcloneexplorer.Database.RunRepository
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import ca.pkay.rcloneexplorer.util.FLog
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

    fun queue(taskID: Long) {
        val run = try {
            RunRepository(mContext).queueLegacyTask(taskID)
        } catch (e: RunRejectedException) {
            FLog.w("SyncManager", "Sync request was blocked: %s", e.message ?: "unknown reason")
            return
        } catch (e: Exception) {
            FLog.e("SyncManager", "Unable to create durable sync run", e)
            return
        }

        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        val data = Data.Builder()
        data.putLong(SyncWorker.TASK_ID, taskID)
        data.putString(SyncWorker.RUN_ID, run.runId)
        data.putString(SyncWorker.RUN_OWNER_TOKEN, run.ownerToken)

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(taskID.toString())
        uploadWorkRequest.addTag(run.runId)
        uploadWorkRequest.addTag(SYNC_WORK_TAG)
        try {
            work(uploadWorkRequest.build())
        } catch (e: Exception) {
            RunRepository(mContext).finish(
                run.runId,
                run.ownerToken,
                ca.pkay.rcloneexplorer.Database.RunState.RECOVERY_REQUIRED,
                "WorkManager rejected durable run dispatch"
            )
            FLog.e("SyncManager", "Unable to dispatch durable sync run", e)
        }
    }

    fun queueEphemeral(task: Task) {

        task.id = Random().nextLong()
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
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
