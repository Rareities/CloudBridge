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

        try {
            val data = Data.Builder()
                .putLong(SyncWorker.TASK_ID, taskID)
                .putString(SyncWorker.RUN_ID, run.runId)
                .putString(SyncWorker.RUN_OWNER_TOKEN, run.ownerToken)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .addTag(taskID.toString())
                .addTag(run.runId)
                .addTag(SYNC_WORK_TAG)
                .build()
            work(request)
        } catch (e: Exception) {
            try {
                val deferred = RunRepository(mContext).deferQueuedDispatch(
                    run.runId,
                    run.ownerToken,
                    "WorkManager rejected durable run dispatch"
                )
                if (!deferred) {
                    FLog.w("SyncManager", "Run had already left QUEUED state; preserving its current owner state")
                }
            } catch (stateError: Exception) {
                FLog.e("SyncManager", "Unable to persist durable run dispatch deferral", stateError)
            }
            FLog.e("SyncManager", "Unable to dispatch durable sync run", e)
        }
    }

    fun queueEphemeral(task: Task) {
        val run = try {
            RunRepository(mContext).queueEphemeralTask(task)
        } catch (e: RunRejectedException) {
            FLog.w("SyncManager", "Ephemeral sync request was blocked: %s", e.message ?: "unknown reason")
            return
        } catch (e: Exception) {
            FLog.e("SyncManager", "Unable to create durable ephemeral sync run", e)
            return
        }
        try {
            val data = Data.Builder()
                .putString(SyncWorker.TASK_EPHEMERAL, task.asJSON().toString())
                .putString(SyncWorker.RUN_ID, run.runId)
                .putString(SyncWorker.RUN_OWNER_TOKEN, run.ownerToken)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .addTag(run.runId)
                .addTag(SYNC_WORK_TAG)
                .build()
            work(request)
        } catch (e: Exception) {
            try {
                val deferred = RunRepository(mContext).deferQueuedDispatch(
                    run.runId,
                    run.ownerToken,
                    "WorkManager rejected durable ephemeral sync dispatch"
                )
                if (!deferred) {
                    FLog.w("SyncManager", "Ephemeral run had already left QUEUED state; preserving its current owner state")
                }
            } catch (stateError: Exception) {
                FLog.e("SyncManager", "Unable to persist ephemeral run dispatch deferral", stateError)
            }
            FLog.e("SyncManager", "Unable to dispatch durable ephemeral sync run", e)
        }
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
