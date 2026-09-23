package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Database.BisyncPreviewFailureCode
import ca.pkay.rcloneexplorer.Database.BisyncPreviewIdentity
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperation
import ca.pkay.rcloneexplorer.Database.BisyncPreviewOperationState
import ca.pkay.rcloneexplorer.Database.BisyncPreviewRepository
import java.util.concurrent.Executor

/** Queues a durable preview owner before handing it to WorkManager. */
class BisyncPreviewWorkScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val previews = BisyncPreviewRepository(appContext)

    fun enqueue(identity: BisyncPreviewIdentity): BisyncPreviewOperation {
        val queued = previews.queue(identity)
        val request = OneTimeWorkRequestBuilder<BisyncPreviewWorker>()
            .setInputData(Data.Builder()
                .putString(BisyncPreviewWorker.PREVIEW_ID, queued.previewId)
                .putString(BisyncPreviewWorker.OWNER_TOKEN, queued.ownerToken)
                .build())
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .addTag(TAG)
            .addTag(workName(queued.previewId))
            .build()

        try {
            val operation = WorkManager.getInstance(appContext).enqueueUniqueWork(
                workName(queued.previewId), ExistingWorkPolicy.KEEP, request
            )
            operation.result.addListener({
                try {
                    operation.result.get()
                } catch (_: Exception) {
                    completeDispatchFailure(queued)
                }
            }, DIRECT_EXECUTOR)
        } catch (_: RuntimeException) {
            completeDispatchFailure(queued)
        }
        return queued
    }

    /** A queued cancellation is terminal immediately; a running worker owns native cancellation. */
    fun cancel(previewId: String, ownerToken: String): Boolean {
        val current = previews.get(previewId) ?: return false
        if (current.ownerToken != ownerToken || current.state.terminal) return false
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(previewId))
        if (current.state == BisyncPreviewOperationState.QUEUED) {
            return previews.finishQueued(
                previewId,
                ownerToken,
                BisyncPreviewOperationState.CANCELLED,
                BisyncPreviewFailureCode.CANCELLED_BEFORE_START
            )
        }
        return current.state == BisyncPreviewOperationState.RUNNING
    }

    private fun completeDispatchFailure(queued: BisyncPreviewOperation) {
        previews.finishQueued(
            queued.previewId,
            queued.ownerToken,
            BisyncPreviewOperationState.UNAVAILABLE,
            BisyncPreviewFailureCode.PROCESS_FAILED
        )
    }

    private fun workName(previewId: String) = "bisync-preview-$previewId"

    private companion object {
        const val TAG = "bisync-preview"
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
