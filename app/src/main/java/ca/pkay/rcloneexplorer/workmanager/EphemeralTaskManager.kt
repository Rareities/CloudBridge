package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.os.Parcel
import androidx.work.Data
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.util.ShareStagingPolicy
import de.schuelken.cloudbridge.notifications.implementations.DeleteWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.DownloadWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.MoveWorkerNotification
import de.schuelken.cloudbridge.notifications.implementations.UploadWorkerNotification

class EphemeralTaskManager(private var mContext: Context) {

    companion object {

        private const val EPHEMERAL_WORK_TAG = "ephemeral_work"
        const val SHARE_UPLOAD_WORK_TAG = "share_upload_work"

        fun queueDownload(
            context: Context,
            remote: RemoteItem,
            downloadItem: FileItem,
            selectedPath: String) {

            DownloadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DOWNLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.DOWNLOAD_TARGETPATH, selectedPath)

            addFileItemToData(EphemeralWorker.DOWNLOAD_SOURCE, downloadItem, data)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        @JvmOverloads
        fun queueUpload(
            context: Context,
            remote: RemoteItem,
            file: String,
            targetpath: String,
            deleteStagedSourceOnFinish: Boolean = false) {

            UploadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
            data.putString(EphemeralWorker.UPLOAD_FILE, file)
            data.putBoolean(EphemeralWorker.UPLOAD_STAGED_SOURCE, deleteStagedSourceOnFinish)

            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        /** Submits all staged files in one WorkManager operation; remote outcomes stay independent. */
        fun queueShareUploads(
            context: Context,
            remote: RemoteItem,
            files: List<String>,
            targetpath: String,
            batchTag: String
        ): Operation {
            require(files.isNotEmpty()) { "A share upload batch must not be empty" }
            require(files.size <= ShareStagingPolicy.HARD_MAX_FILES) {
                "A share upload batch exceeds the file-count limit"
            }
            require(ShareUploadBatchTagPolicy.isValidTag(batchTag)) {
                "A share upload batch requires its invocation-owned work tag"
            }
            val stagingDirectory = java.io.File(files.first()).absoluteFile.parentFile
            require(stagingDirectory != null &&
                    ShareUploadBatchTagPolicy.isTagForStagingDirectory(batchTag, stagingDirectory)) {
                "A share upload work tag must match its staging directory"
            }
            require(files.all { java.io.File(it).absoluteFile.parentFile == stagingDirectory }) {
                "All files in a share batch must belong to its staging directory"
            }

            UploadWorkerNotification(context).generateChannels()
            val requests = files.map { file ->
                val data = Data.Builder()
                data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
                addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)
                data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
                data.putString(EphemeralWorker.UPLOAD_FILE, file)
                data.putBoolean(EphemeralWorker.UPLOAD_STAGED_SOURCE, true)

                OneTimeWorkRequestBuilder<EphemeralWorker>()
                    .setInputData(data.build())
                    .addTag(EPHEMERAL_WORK_TAG)
                    .addTag(SHARE_UPLOAD_WORK_TAG)
                    .addTag(batchTag)
                    .build()
            }
            return WorkManager.getInstance(context).enqueue(requests)
        }

        fun queueMove(
            context: Context,
            remote: RemoteItem,
            currentPath: String,
            file: FileItem,
            readablePath: String
        ) {

            MoveWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.MOVE.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            addFileItemToData(EphemeralWorker.MOVE_FILE, file, data)
            data.putString(EphemeralWorker.MOVE_TARGETPATH, currentPath)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
        }

        fun queueDelete(context: Context, target: PendingDeleteTarget): Boolean {
            if (!SnackbarDeletePolicy.hasConfigRevision(target.getConfigRevision())) return false

            DeleteWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DELETE.name)
            data.putString(EphemeralWorker.DELETE_CONFIG_REVISION, target.getConfigRevision())
            val remote = target.createRemoteItem()
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            addFileItemToData(EphemeralWorker.DELETE_FILE, target.createFileItem(remote), data)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
            return true
        }

        private fun addFileItemToData(key: String, fileItem: FileItem, data: Data.Builder){
            val parcel = Parcel.obtain()
            try {
                fileItem.writeToParcel(parcel, 0)
                data.putByteArray(key, parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }

        private fun addRemoteItemToData(key: String, remote: RemoteItem, data: Data.Builder){
            val parcel = Parcel.obtain()
            try {
                remote.writeToParcel(parcel, 0)
                data.putByteArray(key, parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }
    }


    protected fun work(inputData: Data, tag: String) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<EphemeralWorker>()
        uploadWorkRequest.setInputData(inputData)
        uploadWorkRequest.addTag(tag)
        WorkManager.getInstance(mContext).enqueue(uploadWorkRequest.build())
    }

    fun cancel() {
        WorkManager.getInstance(mContext)
            .cancelAllWorkByTag(EPHEMERAL_WORK_TAG)
    }
    fun cancel(tag: String) {
        WorkManager
            .getInstance(mContext)
            .cancelAllWorkByTag(tag)
    }
}
