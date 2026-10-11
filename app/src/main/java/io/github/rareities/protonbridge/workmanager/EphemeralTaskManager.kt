package io.github.rareities.protonbridge.workmanager

import android.content.Context
import android.os.Parcel
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.rareities.protonbridge.Items.FileItem
import io.github.rareities.protonbridge.Items.RemoteItem
import io.github.rareities.protonbridge.notifications.implementations.DeleteWorkerNotification
import io.github.rareities.protonbridge.notifications.implementations.DownloadWorkerNotification
import io.github.rareities.protonbridge.notifications.implementations.MoveWorkerNotification
import io.github.rareities.protonbridge.notifications.implementations.UploadWorkerNotification
import java.io.File
import java.util.UUID

class EphemeralTaskManager(private var mContext: Context) {

    companion object {

        const val EPHEMERAL_WORK_TAG = "ephemeral_work"

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

        fun queueUpload(
            context: Context,
            remote: RemoteItem,
            file: String,
            targetpath: String) {

            UploadWorkerNotification(context).generateChannels()

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.UPLOAD.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.UPLOAD_TARGETPATH, targetpath)
            data.putString(EphemeralWorker.UPLOAD_FILE, file)

            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
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

        /**
         * Queues one delete worker for the whole selection. WorkManager's `Data` is capped at
         * 10 KB, so large selections are handed over via a manifest file in the cache dir.
         */
        fun queueDelete(
            context: Context,
            remote: RemoteItem,
            files: List<FileItem>
        ) {

            DeleteWorkerNotification(context).generateChannels()

            // A queued job can be cancelled or lost (update, hard process kill) before its worker
            // ever runs; sweep leftovers older than a day so the cache dir stays clean.
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            context.cacheDir.listFiles { file ->
                (file.name.startsWith("delete-manifest-") || file.name.startsWith("delete-files-from-"))
                        && file.lastModified() < cutoff
            }?.forEach { it.delete() }

            val manifest = File(context.cacheDir, "delete-manifest-${UUID.randomUUID()}.txt")
            manifest.writeText(DeleteManifest.encode(files.map {
                DeleteEntry(path = it.path, name = it.name, isDir = it.isDir)
            }))

            val data = Data.Builder()
            data.putString(EphemeralWorker.EPHEMERAL_TYPE, Type.DELETE.name)
            addRemoteItemToData(EphemeralWorker.REMOTE, remote, data)

            data.putString(EphemeralWorker.DELETE_MANIFEST, manifest.absolutePath)
            EphemeralTaskManager(context).work(data.build(), EPHEMERAL_WORK_TAG)
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
