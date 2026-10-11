package io.github.rareities.protonbridge.notifications.implementations

import android.content.Context
import io.github.rareities.protonbridge.Items.FileItem
import io.github.rareities.protonbridge.R
import io.github.rareities.protonbridge.notifications.GenericSyncNotification
import io.github.rareities.protonbridge.notifications.prototypes.WorkerNotification
import io.github.rareities.protonbridge.notifications.support.StatusObject

class MoveWorkerNotification(var context: Context) : WorkerNotification(context) {

    override val CHANNEL_ID = "io.github.rareities.protonbridge.move_service"

    override val initialTitle = string(R.string.worker_move_initialtitle)
    override val serviceOngoingTitle = initialTitle
    override val serviceFailed = string(R.string.worker_move_failed)
    override val serviceCancelled = string(R.string.worker_move_cancelled)
    override val serviceSuccess = string(R.string.worker_move_success)

    // use only a single channel
    override val CHANNEL_SUCCESS_ID = CHANNEL_ID
    override val CHANNEL_FAIL_ID = CHANNEL_ID

    override val channel_ongoing_title = string(R.string.move_service_notification_title)
    override val channel_ongoing_description =string(R.string.move_service_notification_description)
    override val channel_success_title = channel_ongoing_title
    override val channel_success_description = channel_ongoing_description
    override val channel_failed_title = channel_ongoing_title
    override val channel_failed_description = channel_ongoing_description

    override val PERSISTENT_NOTIFICATION_ID = 43

    override val SUMMARY_ID = 44

    override fun generateSuccessMessage(statusObject: StatusObject, fileItem: FileItem): String {
        return mContext.resources.getQuantityString(
                R.plurals.worker_move_success_message,
                statusObject.getDeletions(),
                fileItem.name
        )
    }

}