package ca.pkay.rcloneexplorer.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.AppShortcutsHelper
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NotificationSinkPolicy
import ca.pkay.rcloneexplorer.util.PermissionManager
import ca.pkay.rcloneexplorer.util.ShortcutCapabilities
import ca.pkay.rcloneexplorer.util.StableNotificationIdentity
import ca.pkay.rcloneexplorer.util.SyncLog

class AppErrorNotificationManager(var mContext: Context) {

    companion object {
        private const val APP_ERROR_CHANNEL_ID =
            "ca.pkay.rcloneexplorer.notifications.AppErrorNotificationManager"
        private const val APP_ERROR_ID = 51913
        private const val SESSION_EXPIRED_ID = 51914

        private const val AUTH_EXCEEDED_MAX_RETRIES = "auth exceeded max retries"
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val channel = NotificationChannel(
                APP_ERROR_CHANNEL_ID,
                mContext.getString(R.string.app_error_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description =
                mContext.getString(R.string.app_error_notification_channel_description)
            // Register the channel with the system
            val notificationManager =
                mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    fun showNotification() {


        /*val contentIntent = PendingIntent.getActivity(
            mContext,
            APP_ERROR_ID,
            PermissionManager.getNotificationSettingsIntent(mContext), FLAG_IMMUTABLE
        )*/

        val b = NotificationCompat.Builder(mContext, APP_ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_twotone_error_24)
            .setContentTitle(mContext.getString(R.string.app_error_notification_alarmpermission_missing))
            .setContentText(mContext.getString(R.string.app_error_notification_alarmpermission_missing_description))
            /*.addAction(
                R.drawable.ic_cancel_download,
                mContext.getString(R.string.cancel),
                contentIntent
            )*/
            .setOnlyAlertOnce(true)

        val notificationManager = NotificationManagerCompat.from(mContext)

        if(PermissionManager(mContext).grantedNotifications()) {
            notificationManager.notify(APP_ERROR_ID, b.build())
        } else {
            Log.e("AppErrorNotificationManager", "We dont have Notification Permission!")
        }
    }

    @SuppressLint("MissingPermission")
    fun showSessionExpiredNotification(remoteName: String) {
        // Keep per-remote PendingIntent and notification identity collision-resistant.
        // Intent extras alone do not participate in PendingIntent identity.
        val remoteIdentity = StableNotificationIdentity.forRemote(remoteName)
        val requestCode = SESSION_EXPIRED_ID
        val reauthAction = MainActivity.MAIN_ACTIVITY_START_REAUTH
        val capability = try {
            ShortcutCapabilities.issueOrGetForIntent(mContext, reauthAction, remoteName)
        } catch (failure: RuntimeException) {
            FLog.e("AppErrorNotificationManager", "Unable to persist re-authentication capability")
            return
        }
        val contentIntent = PendingIntent.getActivity(
            mContext,
            requestCode,
            Intent(mContext, MainActivity::class.java).apply {
                action = reauthAction
                data = Uri.parse("cloudbridge://session-expired/$remoteIdentity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AppShortcutsHelper.APP_SHORTCUT_REMOTE_NAME, remoteName)
                putExtra(ShortcutCapabilities.EXTRA_INTENT_CAPABILITY, capability)
            },
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
        )

        val safeRemoteName = NotificationSinkPolicy.sanitizeTitle(remoteName)
        val notificationText = mContext.getString(
            R.string.session_expired_notification_text,
            safeRemoteName
        )

        val b = NotificationCompat.Builder(mContext, APP_ERROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_twotone_error_24)
            .setContentTitle(mContext.getString(R.string.session_expired_notification_title))
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        val notificationManager = NotificationManagerCompat.from(mContext)

        if(PermissionManager(mContext).grantedNotifications()) {
            notificationManager.notify("session-expired:$remoteIdentity", requestCode, b.build())
        } else {
            Log.e("AppErrorNotificationManager", "We dont have Notification Permission!")
        }
    }

    fun checkAndNotifyAuthError(errorMessage: String?, remoteName: String?) {
        if (errorMessage != null && errorMessage.contains(AUTH_EXCEEDED_MAX_RETRIES) && remoteName != null) {
            showSessionExpiredNotification(remoteName)
        }
    }
}
