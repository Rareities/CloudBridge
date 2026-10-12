package io.github.rareities.protonbridge.updates.workmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class UpdateManager(private var mContext: Context) {

    companion object {
        private val TAG_REPEATING = "TAG_REPEATING"
    }

    fun queueRepeating() {
        val checkRequest = PeriodicWorkRequestBuilder<UpdateWorker>(14, TimeUnit.DAYS)
        checkRequest.setConstraints(getConstrains())
        work(checkRequest.build())
    }

    private fun getConstrains(): Constraints {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        return constraints
    }

    private fun work(request: PeriodicWorkRequest) {
        WorkManager.getInstance(mContext).enqueueUniquePeriodicWork(TAG_REPEATING, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll() {
        WorkManager.getInstance(mContext).cancelUniqueWork(TAG_REPEATING)
    }
}