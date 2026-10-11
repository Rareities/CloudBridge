package io.github.rareities.protonbridge.workmanager

import android.content.Context
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.rareities.protonbridge.Services.TriggerService

/** Short, durable dispatch only. The transfer is handled by the ordinary constrained task worker. */
class ScheduleDispatchWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val lock = io.github.rareities.protonbridge.util.ConfigurationExclusion.LOCK.readLock()
        lock.lock()
        try {
            val journal = java.io.File(applicationContext.filesDir, "restore-journal")
            check(!java.io.File(journal, "pending").exists() && !java.io.File(journal, "pending.bak").exists()) {
                "Configuration restoration must finish before schedules resume"
            }
            val scheduler = TriggerService(applicationContext)
            val trigger = inputData.getLong(TriggerService.TRIGGER_ID, -1)
            if (trigger < 0) scheduler.queueTrigger() else scheduler.receiveTrigger(trigger)
            return Result.success()
        } catch (_: Exception) {
            // AlarmManager and restore locks can be temporarily unavailable.
            // A finite retry budget silently strands schedules until the next boot.
            return Result.retry()
        } finally { lock.unlock() }
    }

    companion object {
        @JvmStatic fun reconcileAfterRestore(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScheduleDispatchWorker>()
                .setInputData(Data.Builder().putLong(TriggerService.TRIGGER_ID, -1).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("schedule-restore-reconcile",
                ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        @JvmStatic fun enqueue(context: Context, trigger: Long) {
            val request = OneTimeWorkRequestBuilder<ScheduleDispatchWorker>()
                .setInputData(Data.Builder().putLong(TriggerService.TRIGGER_ID, trigger).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("schedule-dispatch:$trigger", ExistingWorkPolicy.KEEP, request)
        }
    }
}
