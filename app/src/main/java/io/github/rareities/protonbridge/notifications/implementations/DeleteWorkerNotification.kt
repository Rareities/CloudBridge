package io.github.rareities.protonbridge.notifications.implementations

import android.content.Context
import android.util.Log
import io.github.rareities.protonbridge.Items.FileItem
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.notifications.prototypes.WorkerNotification
import io.github.rareities.protonbridge.notifications.support.StatusObject
import io.github.rareities.protonbridge.extensions.tag

class DeleteWorkerNotification(var context: Context) : WorkerNotification(context) {

    override val CHANNEL_ID = "io.github.rareities.protonbridge.delete_service"

    override val initialTitle = string(R.string.worker_deleting_initialtitle)
    override val serviceOngoingTitle = initialTitle
    override val serviceFailed = string(R.string.worker_deleting_failed)
    override val serviceCancelled = string(R.string.worker_deleting_cancelled)
    override val serviceSuccess = string(R.string.worker_deleting_success)

    // use only a single channel
    override val CHANNEL_SUCCESS_ID = CHANNEL_ID
    override val CHANNEL_FAIL_ID = CHANNEL_ID

    override val channel_ongoing_title = string(R.string.delete_service_notification_title)
    override val channel_ongoing_description = string(R.string.delete_service_notification_description)
    override val channel_success_title = channel_ongoing_title
    override val channel_success_description = channel_ongoing_description
    override val channel_failed_title = channel_ongoing_title
    override val channel_failed_description = channel_ongoing_description

    override val PERSISTENT_NOTIFICATION_ID = 124

    override val SUMMARY_ID = 125


    override fun generateSuccessMessage(statusObject: StatusObject, fileItem: FileItem): String {
        val deletions = statusObject.getDeletions()
        if (deletions > 1) {
            // Batch deletes span several files; naming one of them would be misleading.
            return mContext.resources.getQuantityString(
                    R.plurals.worker_deleting_success_batch,
                    deletions,
                    deletions
            )
        }
        return mContext.resources.getQuantityString(
                R.plurals.worker_deleting_success_message,
                deletions,
                fileItem.name
        )
    }

}