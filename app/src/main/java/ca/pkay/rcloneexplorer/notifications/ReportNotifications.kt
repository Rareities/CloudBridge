package ca.pkay.rcloneexplorer.notifications

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ca.pkay.rcloneexplorer.BroadcastReceivers.ClearReportBroadcastReceiver
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.NotificationSinkPolicy
import ca.pkay.rcloneexplorer.util.NotificationUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "notifications")

class ReportNotifications(var mContext: Context) {

    companion object {
        const val CHANNEL_REPORT_ID = "ca.pkay.rcexplorer.sync_report"

        private const val NOTIFICATION_ID_SUCESS_REPORT = 90
        private const val NOTIFICATION_ID_FAIL_REPORT = 91

        const val REPORT_SUCCESS_DELETE_INTENT = "REPORT_SUCCESS_DELETE_INTENT"
        const val REPORT_FAIL_DELETE_INTENT = "REPORT_SUCCESS_DELETE_INTENT"

        val NOTIFICATION_CACHE_SUCCESS_PREFERENCE = stringPreferencesKey("NOTIFICATION_CACHE_SUCCESS")
        val NOTIFICATION_CACHE_FAIL_PREFERENCE = stringPreferencesKey("NOTIFICATION_CACHE_FAIL")
        val NOTIFICATION_LAST_SUCCESS_ID_PREFERENCE = intPreferencesKey("NOTIFICATION_LAST_SUCCESS_ID")
        val NOTIFICATION_LAST_FAIL_ID_PREFERENCE = intPreferencesKey("NOTIFICATION_LAST_FAIL_ID")
    }

    fun lastSuccededNotification(id: Int) {
        runBlocking {
            mContext.dataStore.edit { settings ->
                settings[NOTIFICATION_LAST_SUCCESS_ID_PREFERENCE] =  id
            }
        }
    }

    fun cancelLastSuccededNotification() {
        val prefMap = runBlocking { mContext.dataStore.data.first().asMap() }
        val notificationManager = NotificationManagerCompat.from(mContext)
        notificationManager.cancel((prefMap[NOTIFICATION_LAST_SUCCESS_ID_PREFERENCE] ?: 0) as Int)
    }

    fun addToSuccessReport(title: String, line: String) {
        appendToReport(NOTIFICATION_CACHE_SUCCESS_PREFERENCE, title, line)
    }

    fun showSuccessReport(title: String, line: String) {
        val notificationContent = appendToReport(NOTIFICATION_CACHE_SUCCESS_PREFERENCE, title, line)

        val builder = NotificationCompat.Builder(mContext, CHANNEL_REPORT_ID)
            .setSmallIcon(R.drawable.ic_twotone_cloud_done_24)
            .setContentTitle(mContext.getString(R.string.operation_report_success_title))
            .setContentText(mContext.getString(R.string.operation_report_success_short_content,
                NotificationSinkPolicy.reportEntryCount(notificationContent)))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    notificationContent
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(createDeleteIntent(REPORT_SUCCESS_DELETE_INTENT))

        val notificationManager = NotificationManagerCompat.from(mContext)
        notificationManager.cancel(NOTIFICATION_ID_SUCESS_REPORT)
        NotificationUtils.createNotification(mContext, NOTIFICATION_ID_SUCESS_REPORT, builder.build())
    }



    fun lastFailedNotification(id: Int) {
        val prefMap = runBlocking { mContext.dataStore.data.first().asMap() }
        runBlocking {
            mContext.dataStore.edit { settings ->
                settings[NOTIFICATION_LAST_FAIL_ID_PREFERENCE] =  id
            }
        }
    }

    fun cancelLastFailedNotification() {
        val prefMap = runBlocking { mContext.dataStore.data.first().asMap() }
        val notificationManager = NotificationManagerCompat.from(mContext)
        notificationManager.cancel((prefMap[NOTIFICATION_LAST_FAIL_ID_PREFERENCE] ?: 0) as Int)
    }

    fun addToFailureReport(title: String, line: String) {
        appendToReport(NOTIFICATION_CACHE_FAIL_PREFERENCE, title, line)
    }

    fun showFailReport(title: String, line: String) {
        val notificationContent = appendToReport(NOTIFICATION_CACHE_FAIL_PREFERENCE, title, line)

        val builder = NotificationCompat.Builder(mContext, CHANNEL_REPORT_ID)
            .setSmallIcon(R.drawable.ic_twotone_cloud_error_24)
            .setContentTitle(mContext.getString(R.string.operation_report_fail_title))
            .setContentText(mContext.getString(R.string.operation_report_fail_short_content,
                NotificationSinkPolicy.reportEntryCount(notificationContent)))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    notificationContent
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(createDeleteIntent(REPORT_FAIL_DELETE_INTENT))

        val notificationManager = NotificationManagerCompat.from(mContext)
        notificationManager.cancel(NOTIFICATION_ID_FAIL_REPORT)
        NotificationUtils.createNotification(mContext, NOTIFICATION_ID_FAIL_REPORT, builder.build())
    }


    private fun createDeleteIntent(action: String): PendingIntent? {
        val intent = Intent(mContext, ClearReportBroadcastReceiver::class.java)
        intent.action = action
        return PendingIntent.getBroadcast(
            mContext,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or FLAG_IMMUTABLE
        )
    }

    fun getFailures(): Int {
        val history = runBlocking {
            mContext.dataStore.data.first()[NOTIFICATION_CACHE_FAIL_PREFERENCE]
        }
        return NotificationSinkPolicy.aggregationLineCount(history)
    }

    fun getSucesses(): Int {
        val history = runBlocking {
            mContext.dataStore.data.first()[NOTIFICATION_CACHE_SUCCESS_PREFERENCE]
        }
        return NotificationSinkPolicy.aggregationLineCount(history)
    }

    private fun appendToReport(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        title: String,
        line: String
    ): String {
        val safeTitle = NotificationSinkPolicy.sanitizeTitle(title)
        val safeLine = NotificationSinkPolicy.sanitizeContent(line)
        val updatedPreferences = runBlocking {
            mContext.dataStore.edit { settings ->
                settings[key] = NotificationSinkPolicy.prependReport(
                    settings[key],
                    safeTitle,
                    safeLine
                )
            }
        }
        return updatedPreferences[key].orEmpty()
    }
}
